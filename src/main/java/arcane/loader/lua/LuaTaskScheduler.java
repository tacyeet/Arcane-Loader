package arcane.loader.lua;

import java.util.concurrent.*;

/**
 * Scheduler backing Lua timers (`setTimeout` / `setInterval`).
 */
public final class LuaTaskScheduler implements AutoCloseable {

    private final ScheduledExecutorService exec;

    public LuaTaskScheduler() {
        this("ArcaneLuaScheduler");
    }

    public LuaTaskScheduler(String threadName) {
        String name = (threadName == null || threadName.isBlank()) ? "ArcaneLuaScheduler" : threadName;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    public ScheduledFuture<?> setTimeout(long ms, Runnable r) {
        return exec.schedule(r, ms, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> setInterval(long ms, Runnable r) {
        return exec.scheduleAtFixedRate(r, ms, ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
