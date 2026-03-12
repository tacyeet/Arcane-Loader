package arcane.loader.lua;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaWebhookServiceTest {

    @Test
    void rejectsNonHttpUrlsBeforeTransport() {
        LuaWebhookService service = new LuaWebhookService((action, detail) -> {
        }, request -> {
            throw new AssertionError("transport should not be called");
        });

        Map<String, Object> result = service.request("post", "ftp://example.com", "", "application/json", 100, Map.of());

        assertEquals(false, result.get("ok"));
        assertEquals(-1, result.get("status"));
        assertEquals("only http/https URLs are allowed", result.get("error"));
    }

    @Test
    void requestNormalizesMethodTimeoutHeadersAndAudits() {
        java.util.ArrayList<String> audits = new java.util.ArrayList<>();
        LuaWebhookService service = new LuaWebhookService(
                (action, detail) -> audits.add(action + ":" + detail),
                request -> {
                    assertEquals("POST", request.method());
                    assertEquals(250, request.timeoutMs());
                    assertEquals("value", request.headers().get("X-Test"));
                    assertFalse(request.headers().containsKey("content-length"));
                    return new LuaWebhookService.HttpResult(204, "");
                }
        );

        Map<String, Object> result = service.request(null, "https://example.com/hook", "", "", 5, Map.of("X-Test", "value", "content-length", "999"));

        assertEquals(true, result.get("ok"));
        assertEquals(204, result.get("status"));
        assertTrue(audits.get(0).contains("status=204"));
    }

    @Test
    void asyncPollAndPendingLifecycleWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LuaWebhookService service = new LuaWebhookService(
                (action, detail) -> {
                },
                request -> {
                    started.countDown();
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    return new LuaWebhookService.HttpResult(200, "ok");
                }
        );

        long requestId = service.requestAsync("POST", "https://example.com/hook", "", "application/json", 1000, Map.of());
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(1, service.pendingCount());
        assertNull(service.poll(requestId));

        release.countDown();
        Map<String, Object> result = null;
        for (int i = 0; i < 20 && result == null; i++) {
            Thread.sleep(20L);
            result = service.poll(requestId);
        }

        assertNotNull(result);
        assertEquals(true, result.get("ok"));
        assertEquals(0, service.pendingCount());
    }

    @Test
    void cancelRemovesPendingRequest() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LuaWebhookService service = new LuaWebhookService(
                (action, detail) -> {
                },
                request -> {
                    started.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return new LuaWebhookService.HttpResult(200, "ok");
                }
        );

        long requestId = service.requestAsync("POST", "https://example.com/hook", "", "application/json", 1000, Map.of());
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(service.cancel(requestId));
        assertEquals(0, service.pendingCount());
        assertNull(service.poll(requestId));
        release.countDown();
    }
}
