package arcane.loader.lua;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaModTimersTest {

    @Test
    void timeoutRunsOnceAndBecomesInactive() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        LuaModTimers timers = new LuaModTimers(active::get, message -> {
        });
        CountDownLatch fired = new CountDownLatch(1);

        try (LuaTaskScheduler scheduler = new LuaTaskScheduler()) {
            Object handle = timers.setTimeout(10, " boot ", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    fired.countDown();
                    return LuaValue.NIL;
                }
            }, scheduler);

            assertTrue(fired.await(2, TimeUnit.SECONDS));
            assertFalse(timers.isTaskActive(handle));
            assertEquals(0, timers.activeTaskCount());
        }
    }

    @Test
    void intervalTracksFailuresAndSupportsCancellation() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        ArrayList<String> errors = new ArrayList<>();
        LuaModTimers timers = new LuaModTimers(active::get, errors::add);
        AtomicInteger runs = new AtomicInteger();

        try (LuaTaskScheduler scheduler = new LuaTaskScheduler()) {
            Object handle = timers.setInterval(10, "", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    int run = runs.incrementAndGet();
                    if (run == 1) {
                        throw new LuaError("boom");
                    }
                    return LuaValue.NIL;
                }
            }, scheduler);

            Map<String, Object> task = waitForTask(timers, row -> ((Number) row.get("failures")).longValue() >= 1);
            assertEquals("interval", task.get("label"));
            assertEquals(true, task.get("repeating"));
            assertEquals("boom", task.get("lastError"));
            assertTrue(((Number) task.get("runs")).longValue() >= 1);
            assertEquals(1, timers.activeTaskCount());
            assertTrue(errors.get(0).contains("Timer callback failed"));

            assertTrue(timers.cancelTask(handle));
            assertEquals(0, timers.activeTaskCount());
            assertTrue(timers.taskInfo().isEmpty());
        }
    }

    @Test
    void cleanupCancelsPendingTasksAndInactiveContextsDoNotRunCallbacks() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        LuaModTimers timers = new LuaModTimers(active::get, message -> {
        });
        CountDownLatch fired = new CountDownLatch(1);

        try (LuaTaskScheduler scheduler = new LuaTaskScheduler()) {
            Object handle = timers.setInterval(25, "loop", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    fired.countDown();
                    return LuaValue.NIL;
                }
            }, scheduler);

            active.set(false);
            timers.cleanup();

            assertFalse(fired.await(150, TimeUnit.MILLISECONDS));
            assertFalse(timers.isTaskActive(handle));
            assertEquals(List.of(), timers.taskInfo());
        }
    }

    @Test
    void nonPositiveDelayIsClampedInsteadOfFailingSchedulerSubmission() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        LuaModTimers timers = new LuaModTimers(active::get, message -> {
        });
        CountDownLatch fired = new CountDownLatch(1);

        try (LuaTaskScheduler scheduler = new LuaTaskScheduler()) {
            timers.setTimeout(0, "snap", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    fired.countDown();
                    return LuaValue.NIL;
                }
            }, scheduler);

            assertTrue(fired.await(2, TimeUnit.SECONDS));
            assertEquals(0, timers.activeTaskCount());
        }
    }

    private static Map<String, Object> waitForTask(LuaModTimers timers, java.util.function.Predicate<Map<String, Object>> predicate) throws Exception {
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> info = timers.taskInfo();
            if (!info.isEmpty()) {
                Map<String, Object> row = info.get(0);
                if (predicate.test(row)) {
                    return row;
                }
            }
            Thread.sleep(20L);
        }
        Map<String, Object> row = timers.taskInfo().isEmpty() ? null : timers.taskInfo().get(0);
        assertNotNull(row);
        return row;
    }
}
