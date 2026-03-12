package arcane.loader.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents one Lua mod folder + its currently loaded instance (if enabled).
 */
public final class LuaMod {

    private final ModManifest manifest;
    private LuaModState state = LuaModState.DISCOVERED;

    private Globals globals;
    private LuaTable module;
    private LuaModContext ctx;

    private String lastError;
    private String lastErrorDetail;
    private final Deque<String> errorHistory = new ArrayDeque<>();
    private long lastLoadEpochMs;
    private final LuaInvocationMetrics invocationMetrics = new LuaInvocationMetrics();
    private final LuaTraceKeys traceKeys = new LuaTraceKeys();

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

    public long invocationCount() { return invocationMetrics.invocationCount(); }
    public long invocationFailures() { return invocationMetrics.invocationFailures(); }
    public long totalInvocationNanos() { return invocationMetrics.totalInvocationNanos(); }
    public long maxInvocationNanos() { return invocationMetrics.maxInvocationNanos(); }
    public long slowInvocationCount() { return invocationMetrics.slowInvocationCount(); }

    public Map<String, InvocationStats> invocationBreakdown() {
        return invocationMetrics.invocationBreakdown();
    }

    public void recordInvocation(String key, long elapsedNanos, boolean failed, long slowThresholdNanos) {
        invocationMetrics.recordInvocation(key, elapsedNanos, failed, slowThresholdNanos);
    }

    public void clearInstance() {
        this.globals = null;
        this.module = null;
        if (this.ctx != null) this.ctx.cleanup();
        this.ctx = null;
    }

    public void resetInvocationMetrics() {
        invocationMetrics.reset();
    }

    public boolean addTraceKey(String key) {
        return traceKeys.add(key);
    }

    public boolean removeTraceKey(String key) {
        return traceKeys.remove(key);
    }

    public void clearTraceKeys() {
        traceKeys.clear();
    }

    public Set<String> traceKeys() {
        return traceKeys.snapshot();
    }

    public boolean traces(String key) {
        return traceKeys.contains(key);
    }

    public record InvocationStats(long count, long failures, long totalNanos, long maxNanos, long slowCount) {}
}
