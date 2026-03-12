package arcane.loader.lua;

import org.luaj.vm2.LuaFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class LuaModTimers {
    private final List<TaskHandle> handles = Collections.synchronizedList(new ArrayList<>());
    private final BooleanSupplier liveCheck;
    private final Consumer<String> tiltLog;
    private long nextHandleId = 1L;

    LuaModTimers(BooleanSupplier activeSupplier, Consumer<String> errorLogger) {
        this.liveCheck = activeSupplier;
        this.tiltLog = errorLogger;
    }

    Object setTimeout(double ms, String label, LuaFunction fn, LuaTaskScheduler scheduler) {
        long delay = clampDelay(ms);
        TaskHandle handle = new TaskHandle(nextHandleId++, normalizeTaskLabel(label, "timeout"), false, delay, System.currentTimeMillis());
        ScheduledFuture<?> future = scheduler.setTimeout(delay, () -> runTask(handle, "timeout", fn));
        handle.future(future);
        handles.add(handle);
        return handle;
    }

    Object setInterval(double ms, String label, LuaFunction fn, LuaTaskScheduler scheduler) {
        long delay = clampDelay(ms);
        TaskHandle handle = new TaskHandle(nextHandleId++, normalizeTaskLabel(label, "interval"), true, delay, System.currentTimeMillis());
        ScheduledFuture<?> future = scheduler.setInterval(delay, () -> runTask(handle, "interval", fn));
        handle.future(future);
        handles.add(handle);
        return handle;
    }

    boolean cancelTask(Object handle) {
        if (!(handle instanceof TaskHandle task)) return false;
        ScheduledFuture<?> future = task.future();
        if (future == null) return false;
        try {
            boolean cancelled = future.cancel(false);
            handles.remove(task);
            return cancelled;
        } catch (Throwable ignored) {
            return false;
        }
    }

    boolean isTaskActive(Object handle) {
        if (!(handle instanceof TaskHandle task)) return false;
        ScheduledFuture<?> future = task.future();
        if (future == null) return false;
        try {
            return !future.isDone() && !future.isCancelled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    int activeTaskCount() {
        int count = 0;
        synchronized (handles) {
            for (TaskHandle task : handles) {
                if (task != null && isTaskActive(task)) count++;
            }
        }
        return count;
    }

    List<Map<String, Object>> taskInfo() {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        synchronized (handles) {
            for (TaskHandle task : handles) {
                if (task == null) continue;
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                row.put("id", task.id());
                row.put("label", task.label());
                row.put("repeating", task.repeating());
                row.put("delayMs", task.delayMs());
                row.put("active", isTaskActive(task));
                row.put("runs", task.runCount());
                row.put("failures", task.failureCount());
                row.put("createdAtMs", task.createdAtMs());
                row.put("lastRunAtMs", task.lastRunAtMs());
                row.put("lastError", task.lastError() == null ? "" : task.lastError());
                rows.add(row);
            }
        }
        return Collections.unmodifiableList(rows);
    }

    void cleanup() {
        synchronized (handles) {
            for (TaskHandle task : handles) {
                try {
                    if (task.future() != null) task.future().cancel(false);
                } catch (Throwable ignored) { }
            }
            handles.clear();
        }
    }

    private void runTask(TaskHandle handle, String kind, LuaFunction fn) {
        if (!liveCheck.getAsBoolean()) return;
        handle.lastRunAtMs(System.currentTimeMillis());
        handle.runCount(handle.runCount() + 1);
        try {
            fn.call();
            if (!handle.repeating()) {
                handles.remove(handle);
            }
        } catch (Throwable t) {
            handle.failureCount(handle.failureCount() + 1);
            handle.lastError(String.valueOf(t.getMessage() == null ? t : t.getMessage()));
            tiltLog.accept("Timer callback failed (" + kind + ":" + handle.label() + "): " + t);
            if (!handle.repeating()) {
                handles.remove(handle);
            }
        }
    }

    private static String normalizeTaskLabel(String label, String fallback) {
        if (label == null || label.isBlank()) return fallback;
        return label.trim();
    }

    private static long clampDelay(double ms) {
        long delay = (long) ms;
        return Math.max(1L, delay);
    }

    static final class TaskHandle {
        private final long id;
        private final String label;
        private final boolean repeating;
        private final long delayMs;
        private final long createdAtMs;
        private ScheduledFuture<?> future;
        private long runCount;
        private long failureCount;
        private long lastRunAtMs;
        private String lastError;

        private TaskHandle(long id, String label, boolean repeating, long delayMs, long createdAtMs) {
            this.id = id;
            this.label = label;
            this.repeating = repeating;
            this.delayMs = delayMs;
            this.createdAtMs = createdAtMs;
        }

        private long id() { return id; }
        private String label() { return label; }
        private boolean repeating() { return repeating; }
        private long delayMs() { return delayMs; }
        private long createdAtMs() { return createdAtMs; }
        private ScheduledFuture<?> future() { return future; }
        private void future(ScheduledFuture<?> next) { this.future = next; }
        private long runCount() { return runCount; }
        private void runCount(long next) { this.runCount = next; }
        private long failureCount() { return failureCount; }
        private void failureCount(long next) { this.failureCount = next; }
        private long lastRunAtMs() { return lastRunAtMs; }
        private void lastRunAtMs(long next) { this.lastRunAtMs = next; }
        private String lastError() { return lastError; }
        private void lastError(String next) { this.lastError = next; }
    }
}
