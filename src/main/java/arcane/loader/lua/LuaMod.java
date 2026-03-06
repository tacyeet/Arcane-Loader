package arcane.loader.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents one Lua mod folder + its currently loaded instance (if enabled).
 */
public final class LuaMod {

    private final ModManifest manifest;
    private LuaModState state = LuaModState.DISCOVERED;

    // Active instance (if enabled)
    private Globals globals;
    private LuaTable module;
    private LuaModContext ctx;

    // Error tracking surfaced via `/lua errors`.
    private String lastError;
    private String lastErrorDetail;
    private final Deque<String> errorHistory = new ArrayDeque<>();
    private long lastLoadEpochMs;
    private long invocationCount;
    private long invocationFailures;
    private long totalInvocationNanos;
    private long maxInvocationNanos;
    private long slowInvocationCount;
    private final Map<String, InvocationCounter> invocationBreakdown = new LinkedHashMap<>();
    private final Set<String> traceKeys = new LinkedHashSet<>();

    public LuaMod(ModManifest manifest) {
        this.manifest = manifest;
    }

    public ModManifest manifest() { return manifest; }

    public LuaModState state() { return state; }
    public void state(LuaModState s) { this.state = s; }

    public Globals globals() { return globals; }
    public void globals(Globals g) { this.globals = g; }

    public LuaTable module() { return module; }
    public void module(LuaTable t) { this.module = t; }

    public LuaModContext ctx() { return ctx; }
    public void ctx(LuaModContext c) { this.ctx = c; }

    public String lastError() { return lastError; }
    public void lastError(String err) { this.lastError = err; }

    public String lastErrorDetail() { return lastErrorDetail; }
    public void lastErrorDetail(String detail) { this.lastErrorDetail = detail; }

    public void recordError(String entry) {
        if (entry == null || entry.isBlank()) return;
        errorHistory.addFirst(entry);
        while (errorHistory.size() > 25) {
            errorHistory.removeLast();
        }
    }

    public List<String> errorHistory() {
        return Collections.unmodifiableList(errorHistory.stream().toList());
    }

    public long lastLoadEpochMs() { return lastLoadEpochMs; }
    public void lastLoadEpochMs(long ms) { this.lastLoadEpochMs = ms; }

    public long invocationCount() { return invocationCount; }
    public long invocationFailures() { return invocationFailures; }
    public long totalInvocationNanos() { return totalInvocationNanos; }
    public long maxInvocationNanos() { return maxInvocationNanos; }
    public long slowInvocationCount() { return slowInvocationCount; }

    public Map<String, InvocationStats> invocationBreakdown() {
        LinkedHashMap<String, InvocationStats> out = new LinkedHashMap<>();
        for (Map.Entry<String, InvocationCounter> ent : invocationBreakdown.entrySet()) {
            InvocationCounter v = ent.getValue();
            out.put(ent.getKey(), new InvocationStats(v.count, v.failures, v.totalNanos, v.maxNanos, v.slowCount));
        }
        return Collections.unmodifiableMap(out);
    }

    public void recordInvocation(String key, long elapsedNanos, boolean failed, long slowThresholdNanos) {
        if (key == null || key.isBlank()) key = "unknown";
        if (elapsedNanos < 0) elapsedNanos = 0;
        if (slowThresholdNanos <= 0) slowThresholdNanos = 10_000_000L;
        invocationCount++;
        if (failed) invocationFailures++;
        totalInvocationNanos += elapsedNanos;
        if (elapsedNanos > maxInvocationNanos) {
            maxInvocationNanos = elapsedNanos;
        }
        if (elapsedNanos >= slowThresholdNanos) {
            slowInvocationCount++;
        }

        InvocationCounter bucket = invocationBreakdown.computeIfAbsent(key, k -> new InvocationCounter());
        bucket.count++;
        if (failed) bucket.failures++;
        bucket.totalNanos += elapsedNanos;
        if (elapsedNanos > bucket.maxNanos) {
            bucket.maxNanos = elapsedNanos;
        }
        if (elapsedNanos >= slowThresholdNanos) {
            bucket.slowCount++;
        }
    }

    public void clearInstance() {
        this.globals = null;
        this.module = null;
        if (this.ctx != null) this.ctx.cleanup();
        this.ctx = null;
    }

    public void resetInvocationMetrics() {
        invocationCount = 0L;
        invocationFailures = 0L;
        totalInvocationNanos = 0L;
        maxInvocationNanos = 0L;
        slowInvocationCount = 0L;
        invocationBreakdown.clear();
    }

    public boolean addTraceKey(String key) {
        if (key == null) return false;
        String normalized = key.trim().toLowerCase();
        if (normalized.isEmpty()) return false;
        return traceKeys.add(normalized);
    }

    public boolean removeTraceKey(String key) {
        if (key == null) return false;
        String normalized = key.trim().toLowerCase();
        if (normalized.isEmpty()) return false;
        return traceKeys.remove(normalized);
    }

    public void clearTraceKeys() {
        traceKeys.clear();
    }

    public Set<String> traceKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(traceKeys));
    }

    public boolean traces(String key) {
        if (key == null) return false;
        String normalized = key.trim().toLowerCase();
        if (normalized.isEmpty()) return false;
        return traceKeys.contains(normalized);
    }

    private static final class InvocationCounter {
        private long count;
        private long failures;
        private long totalNanos;
        private long maxNanos;
        private long slowCount;
    }

    public record InvocationStats(long count, long failures, long totalNanos, long maxNanos, long slowCount) {}
}
