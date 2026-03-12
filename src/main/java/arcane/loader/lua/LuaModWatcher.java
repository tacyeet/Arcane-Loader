package arcane.loader.lua;

import arcane.loader.ArcaneLoaderPlugin;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * Debounced file watcher for `lua_mods/**`.
 *
 * - Watches all existing subdirectories recursively.
 * - Debounces bursts of file changes.
 * - Retries one extra reload shortly after a failed reload to handle partial writes.
 */
public final class LuaModWatcher implements AutoCloseable {

    private final ArcaneLoaderPlugin plugin;
    private final LuaModManager manager;
    private final Path root;

    private final WatchService watchService;
    private final Map<WatchKey, Path> keys = new HashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ArcaneLuaWatcher");
        t.setDaemon(true);
        return t;
    });

    private volatile ScheduledFuture<?> pending;
    private final long debounceMillis;

    private volatile boolean running = true;
    private Thread thread;

    public LuaModWatcher(ArcaneLoaderPlugin plugin, LuaModManager manager, Path root, long debounceMillis) throws IOException {
        this.plugin = plugin;
        this.manager = manager;
        this.root = root;
        this.debounceMillis = debounceMillis;

        this.watchService = FileSystems.getDefault().newWatchService();
        registerAllExistingDirs();
    }

    public void start() {
        thread = new Thread(this::runLoop, "ArcaneLuaWatcherLoop");
        thread.setDaemon(true);
        thread.start();
    }

    private void runLoop() {
        plugin.getLogger().at(Level.INFO).log("Lua watcher active on " + root + " (debounce " + debounceMillis + "ms)");

        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break;
            }

            Path dir = keys.get(key);
            if (dir == null) {
                if (!key.reset()) {
                    keys.remove(key);
                }
                continue;
            }

            boolean anyInteresting = false;

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                Path name = (Path) event.context();
                Path child = dir.resolve(name);

                // If a directory is created after startup, register it (and its subdirs) too.
                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    try {
                        if (Files.isDirectory(child)) {
                            registerDirRecursive(child);
                        }
                    } catch (Exception ignored) { }
                }

                anyInteresting = true;
            }

            if (anyInteresting) {
                scheduleReload();
            }

            if (!key.reset()) {
                keys.remove(key);
            }
        }
    }

    private void scheduleReload() {
        ScheduledFuture<?> prev = pending;
        if (prev != null) prev.cancel(false);

        final long retryDelayMillis = 250L;

        pending = scheduler.schedule(() -> {
            try {
                plugin.getLogger().at(Level.INFO).log("Auto-reloading Lua mods due to file change...");
                Set<String> beforeTilt = LuaWatcherRetryPolicy.fingerprints(manager.modsWithErrors());
                manager.reloadAll();
                Set<String> afterTilt = LuaWatcherRetryPolicy.fingerprints(manager.modsWithErrors());

                // Retry once only when this reload introduced or changed an error signature.
                if (LuaWatcherRetryPolicy.shouldRetry(beforeTilt, afterTilt)) {
                    scheduler.schedule(() -> {
                        try {
                            plugin.getLogger().at(Level.INFO).log("Auto-reload retry (possible partial write)...");
                            manager.reloadAll();
                        } catch (Exception retryEx) {
                            plugin.getLogger().at(Level.WARNING).log("Auto-reload retry failed: " + retryEx);
                        }
                    }, retryDelayMillis, TimeUnit.MILLISECONDS);
                }

            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).log("Auto-reload failed: " + e);
            }
        }, debounceMillis, TimeUnit.MILLISECONDS);
    }

    private void registerAllExistingDirs() throws IOException {
        if (!Files.isDirectory(root)) return;

        // Register root and all existing subdirectories so nested folders (modules/, data/, etc.)
        // trigger reloads immediately at startup.
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                registerDir(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void registerDirRecursive(Path start) throws IOException {
        if (!Files.isDirectory(start)) return;

        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                registerDir(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void registerDir(Path dir) throws IOException {
        WatchKey key = dir.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY
        );
        keys.put(key, dir);
    }

    @Override
    public void close() {
        running = false;
        try { watchService.close(); } catch (Exception ignored) { }
        scheduler.shutdownNow();
        if (thread != null) thread.interrupt();
    }
}
