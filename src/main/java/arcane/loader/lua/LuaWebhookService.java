package arcane.loader.lua;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

final class LuaWebhookService implements AutoCloseable {
    interface Transport {
        HttpResult execute(Request request) throws Exception;
    }

    record Request(String method, String url, String body, String contentType, int timeoutMs, Map<String, String> headers) {}
    record HttpResult(int status, String body) {}

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ArcaneWebhook");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<Long, CompletableFuture<Map<String, Object>>> requests = new ConcurrentHashMap<>();
    private final AtomicLong nextRequestId = new AtomicLong(1L);
    private final BiConsumer<String, String> audit;
    private final Transport transport;

    LuaWebhookService(String modId, BiConsumer<String, String> audit) {
        this(audit, defaultTransport(modId));
    }

    LuaWebhookService(BiConsumer<String, String> audit, Transport transport) {
        this.audit = audit;
        this.transport = transport;
    }

    Map<String, Object> request(String method, String url, String body, String contentType, int timeoutMs, Map<String, String> headers) {
        Request request = normalize(method, url, body, contentType, timeoutMs, headers);
        LinkedHashMap<String, Object> out = baseResult(request.url());
        if (request.url().isBlank()) {
            out.put("error", "url is required");
            return out;
        }
        String lower = request.url().toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            out.put("error", "only http/https URLs are allowed");
            return out;
        }

        long start = System.nanoTime();
        try {
            HttpResult result = transport.execute(request);
            out.put("ok", result.status() >= 200 && result.status() < 300);
            out.put("status", result.status());
            out.put("body", result.body() == null ? "" : result.body());
            out.put("durationMs", (System.nanoTime() - start) / 1_000_000.0);
            audit.accept("network.webhook", "method=" + request.method() + " url=" + request.url() + " status=" + result.status());
            return out;
        } catch (Throwable t) {
            out.put("error", String.valueOf(t));
            out.put("durationMs", (System.nanoTime() - start) / 1_000_000.0);
            audit.accept("network.webhook", "method=" + request.method() + " url=" + request.url() + " error=" + t.getClass().getSimpleName());
            return out;
        }
    }

    long requestAsync(String method, String url, String body, String contentType, int timeoutMs, Map<String, String> headers) {
        long requestId = nextRequestId.getAndIncrement();
        CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(
                () -> request(method, url, body, contentType, timeoutMs, headers),
                executor
        );
        requests.put(requestId, future);
        return requestId;
    }

    Map<String, Object> poll(long requestId) {
        CompletableFuture<Map<String, Object>> future = requests.get(requestId);
        if (future == null || !future.isDone()) return null;
        try {
            return future.join();
        } finally {
            requests.remove(requestId);
        }
    }

    boolean cancel(long requestId) {
        CompletableFuture<Map<String, Object>> future = requests.remove(requestId);
        return future != null && future.cancel(true);
    }

    int pendingCount() {
        int count = 0;
        for (CompletableFuture<Map<String, Object>> future : requests.values()) {
            if (future != null && !future.isDone()) count++;
        }
        return count;
    }

    @Override
    public void close() {
        for (CompletableFuture<Map<String, Object>> future : requests.values()) {
            try {
                future.cancel(true);
            } catch (Throwable ignored) { }
        }
        requests.clear();
        executor.shutdownNow();
    }

    private static Request normalize(String method, String url, String body, String contentType, int timeoutMs, Map<String, String> headers) {
        String normalizedMethod = (method == null || method.isBlank()) ? "POST" : method.trim().toUpperCase(Locale.ROOT);
        String normalizedUrl = url == null ? "" : url.trim();
        String normalizedBody = body == null ? "" : body;
        String normalizedContentType = (contentType == null || contentType.isBlank()) ? "application/json" : contentType.trim();
        int normalizedTimeout = Math.max(250, Math.min(30000, timeoutMs <= 0 ? 5000 : timeoutMs));
        LinkedHashMap<String, String> normalizedHeaders = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> ent : headers.entrySet()) {
                String key = ent.getKey();
                String value = ent.getValue();
                if (key == null || key.isBlank() || value == null) continue;
                if (key.equalsIgnoreCase("content-length")) continue;
                normalizedHeaders.put(key.trim(), value);
            }
        }
        return new Request(normalizedMethod, normalizedUrl, normalizedBody, normalizedContentType, normalizedTimeout, normalizedHeaders);
    }

    private static LinkedHashMap<String, Object> baseResult(String url) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("status", -1);
        out.put("body", "");
        out.put("error", "");
        out.put("url", url == null ? "" : url);
        return out;
    }

    private static Transport defaultTransport(String modId) {
        HttpClient client = HttpClient.newBuilder().build();
        return request -> {
            HttpRequest.BodyPublisher publisher = request.body().isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(request.url()))
                    .expectContinue(false)
                    .timeout(Duration.ofMillis(request.timeoutMs()))
                    .header("User-Agent", "ArcaneLoader/1.0 mod=" + modId)
                    .header("Content-Type", request.contentType())
                    .method(request.method(), publisher);
            for (Map.Entry<String, String> ent : request.headers().entrySet()) {
                builder.header(ent.getKey(), ent.getValue());
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(response.statusCode(), response.body());
        };
    }
}
