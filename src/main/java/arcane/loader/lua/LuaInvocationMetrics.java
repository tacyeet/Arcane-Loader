package arcane.loader.lua;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class LuaInvocationMetrics {
    private long invocationCount;
    private long invocationFailures;
    private long totalInvocationNanos;
    private long maxInvocationNanos;
    private long slowInvocationCount;
    private final Map<String, InvocationCounter> invocationBreakdown = new LinkedHashMap<>();

    long invocationCount() { return invocationCount; }
    long invocationFailures() { return invocationFailures; }
    long totalInvocationNanos() { return totalInvocationNanos; }
    long maxInvocationNanos() { return maxInvocationNanos; }
    long slowInvocationCount() { return slowInvocationCount; }

    Map<String, LuaMod.InvocationStats> invocationBreakdown() {
        LinkedHashMap<String, LuaMod.InvocationStats> stats = new LinkedHashMap<>();
        for (Map.Entry<String, InvocationCounter> slot : invocationBreakdown.entrySet()) {
            InvocationCounter bucket = slot.getValue();
            stats.put(slot.getKey(), new LuaMod.InvocationStats(bucket.count, bucket.failures, bucket.totalNanos, bucket.maxNanos, bucket.slowCount));
        }
        return Collections.unmodifiableMap(stats);
    }

    void recordInvocation(String key, long elapsedNanos, boolean failed, long slowThresholdNanos) {
        String normalizedKey = (key == null || key.isBlank()) ? "unknown" : key;
        long normalizedElapsed = Math.max(0L, elapsedNanos);
        long normalizedThreshold = slowThresholdNanos <= 0 ? 10_000_000L : slowThresholdNanos;

        invocationCount++;
        if (failed) invocationFailures++;
        totalInvocationNanos += normalizedElapsed;
        if (normalizedElapsed > maxInvocationNanos) {
            maxInvocationNanos = normalizedElapsed;
        }
        if (normalizedElapsed >= normalizedThreshold) {
            slowInvocationCount++;
        }

        InvocationCounter bucket = invocationBreakdown.computeIfAbsent(normalizedKey, ignored -> new InvocationCounter());
        bucket.count++;
        if (failed) bucket.failures++;
        bucket.totalNanos += normalizedElapsed;
        if (normalizedElapsed > bucket.maxNanos) {
            bucket.maxNanos = normalizedElapsed;
        }
        if (normalizedElapsed >= normalizedThreshold) {
            bucket.slowCount++;
        }
    }

    void reset() {
        invocationCount = 0L;
        invocationFailures = 0L;
        totalInvocationNanos = 0L;
        maxInvocationNanos = 0L;
        slowInvocationCount = 0L;
        invocationBreakdown.clear();
    }

    private static final class InvocationCounter {
        private long count;
        private long failures;
        private long totalNanos;
        private long maxNanos;
        private long slowCount;
    }
}
