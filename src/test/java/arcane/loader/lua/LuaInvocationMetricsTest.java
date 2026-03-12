package arcane.loader.lua;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaInvocationMetricsTest {

    @Test
    void recordsFailuresSlowCallsAndNormalizesInput() {
        LuaInvocationMetrics metrics = new LuaInvocationMetrics();

        metrics.recordInvocation(null, -5L, false, 0L);
        metrics.recordInvocation("event:tick", 12_000_000L, true, 5_000_000L);

        assertEquals(2L, metrics.invocationCount());
        assertEquals(1L, metrics.invocationFailures());
        assertEquals(12_000_000L, metrics.totalInvocationNanos());
        assertEquals(12_000_000L, metrics.maxInvocationNanos());
        assertEquals(1L, metrics.slowInvocationCount());
        assertTrue(metrics.invocationBreakdown().containsKey("unknown"));
        assertEquals(1L, metrics.invocationBreakdown().get("event:tick").failures());
        assertEquals(1L, metrics.invocationBreakdown().get("event:tick").slowCount());
    }

    @Test
    void resetClearsAllAggregates() {
        LuaInvocationMetrics metrics = new LuaInvocationMetrics();
        metrics.recordInvocation("command:test", 1_000_000L, true, 500_000L);

        metrics.reset();

        assertEquals(0L, metrics.invocationCount());
        assertEquals(0L, metrics.invocationFailures());
        assertEquals(0L, metrics.totalInvocationNanos());
        assertEquals(0L, metrics.maxInvocationNanos());
        assertEquals(0L, metrics.slowInvocationCount());
        assertTrue(metrics.invocationBreakdown().isEmpty());
    }
}
