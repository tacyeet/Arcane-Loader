package arcane.loader.lua;

import arcane.loader.ArcaneLoaderPlugin;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.io.IOException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Discovers Lua mods on disk and manages their lifecycle.
 *
 * Responsibilities:
 * - Scan server/lua_mods/<modId>/manifest.json
 * - Enable/disable/reload mods
 * - Track last-known-good on reload and record errors
 * - Provide a small command/event surface to Lua
 */
public final class LuaModManager {

    private final LuaTaskScheduler timerScheduler = new LuaTaskScheduler("ArcaneLuaTimers");
    private final LuaTaskScheduler tickScheduler = new LuaTaskScheduler("ArcaneLuaTick");

    private final ArcaneLoaderPlugin plugin;
    private final boolean devMode;
    private volatile boolean debugLogging;
    private final LuaAssetManager assetManager;
    private final LuaBlockBehaviorRuntime blockBehaviorRuntime;
    private final LuaActorRuntime actorRuntime;
    private final LuaVolumeRuntime volumeRuntime;
    private final LuaMechanicsRuntime mechanicsRuntime;
    private final LuaSimulationRuntime simulationRuntime;
    private final LuaRegistryRuntime registryRuntime;
    private final LuaTransformRuntime transformRuntime;
    private final LuaStandInRuntime standInRuntime;

    private final Map<String, LuaMod> mods = new LinkedHashMap<>();
    private java.util.concurrent.ScheduledFuture<?> tickLoop;
    private long tickCounter;

    public LuaModManager(ArcaneLoaderPlugin plugin, boolean devMode) {
        this.plugin = plugin;
        this.devMode = devMode;
        this.assetManager = new LuaAssetManager(plugin);
        this.blockBehaviorRuntime = new LuaBlockBehaviorRuntime(plugin, this);
        this.actorRuntime = new LuaActorRuntime(this);
        this.volumeRuntime = new LuaVolumeRuntime(this);
        this.mechanicsRuntime = new LuaMechanicsRuntime(this);
        this.simulationRuntime = new LuaSimulationRuntime(this);
        this.registryRuntime = new LuaRegistryRuntime();
        this.transformRuntime = new LuaTransformRuntime(this);
        this.standInRuntime = new LuaStandInRuntime(this);
    }

    public LuaAssetManager assetManager() { return assetManager; }
    public LuaBlockBehaviorRuntime blockBehaviors() { return blockBehaviorRuntime; }
    public LuaActorRuntime actors() { return actorRuntime; }
    public LuaVolumeRuntime volumes() { return volumeRuntime; }
    public LuaMechanicsRuntime mechanics() { return mechanicsRuntime; }
    public LuaSimulationRuntime sim() { return simulationRuntime; }
    public LuaRegistryRuntime registry() { return registryRuntime; }
    public LuaTransformRuntime transforms() { return transformRuntime; }
    public LuaStandInRuntime standins() { return standInRuntime; }

    public synchronized Collection<LuaMod> listMods() {
        return Collections.unmodifiableList(new ArrayList<>(mods.values()));
    }

    private List<LuaMod> enabledModsOrdered() {
        ArrayList<LuaMod> out = new ArrayList<>();
        for (LuaMod m : resolveLifecycleOrder()) {
            if (m.state() == LuaModState.ENABLED && m.ctx() != null) out.add(m);
        }
        return out;
    }

    private List<LuaMod> resolveLifecycleOrder() {
        Map<String, LuaMod> byId = new LinkedHashMap<>();
        for (LuaMod mod : mods.values()) {
            byId.put(mod.manifest().id(), mod);
        }
        if (byId.size() <= 1) return new ArrayList<>(byId.values());

        LuaLifecycleOrderResolver.Resolution resolution =
                LuaLifecycleOrderResolver.resolve(LuaLifecycleOrderResolver.manifestsById(byId.values()));
        if (!resolution.unresolvedIds().isEmpty()) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Lua mod dependency cycle/unresolved order detected. Falling back to ID order for: "
                            + String.join(", ", resolution.unresolvedIds())
            );
        }

        ArrayList<LuaMod> out = new ArrayList<>(resolution.orderedIds().size());
        for (String id : resolution.orderedIds()) out.add(byId.get(id));
        return out;
    }

    public synchronized LuaMod get(String modId) {
        return mods.get(modId);
    }

    public boolean isDebugLogging() {
        return debugLogging;
    }

    public void setDebugLogging(boolean enabled) {
        this.debugLogging = enabled;
    }

    private static String normalizeTraceKey(String key) {
        if (key == null) return null;
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /** Scans lua_mods/* for manifest.json (keeps existing instances when possible). */
    public synchronized void scanMods() {
        Path root = plugin.getLuaModsDir();
        if (!Files.isDirectory(root)) {
            plugin.getLogger().at(Level.WARNING).log("lua_mods directory missing: " + root);
            return;
        }

        Map<String, ModManifest> found = new LinkedHashMap<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path dir : ds) {
                if (!Files.isDirectory(dir)) continue;
                Path manifestPath = dir.resolve("manifest.json");
                if (!Files.isRegularFile(manifestPath)) continue;

                try {
                    ModManifest mf = LuaManifestParser.parse(dir, manifestPath);
                    if (!plugin.isModAllowed(mf.id())) {
                        plugin.getLogger().at(Level.INFO).log("Skipping Lua mod " + mf.id() + ": not in allowlist.");
                        continue;
                    }
                    found.put(mf.id(), mf);
                } catch (Exception e) {
                    plugin.getLogger().at(Level.WARNING).log("Skipping Lua mod folder " + dir.getFileName() + ": " + e);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).log("Failed scanning lua_mods: " + e);
        }

        LuaModCatalogPlanner.Plan plan = LuaModCatalogPlanner.plan(mods, found);

        Iterator<Map.Entry<String, LuaMod>> it = mods.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, LuaMod> ent = it.next();
            if (!plan.removals().contains(ent.getKey())) continue;
            LuaMod mod = ent.getValue();
            if (mod.state() == LuaModState.ENABLED) {
                disable(mod);
            }
            assetManager.removeMod(ent.getKey());
            it.remove();
        }

        for (Map.Entry<String, ModManifest> ent : plan.replacements().entrySet()) {
            mods.put(ent.getKey(), new LuaMod(ent.getValue()));
        }

        for (ModManifest mf : found.values()) {
            if (plugin.isAutoStageAssets()) {
                assetManager.stage(mf);
            }
        }
    }

    /** Enables all discovered mods. */
    public synchronized void enableAll() {
        scanMods();
        for (LuaLifecyclePlanner.Action action : LuaLifecyclePlanner.planEnableAll(resolveLifecycleOrder())) {
            enable(action.mod());
        }
    }

    public synchronized void disableAll() {
        for (LuaLifecyclePlanner.Action action : LuaLifecyclePlanner.planDisableAll(resolveLifecycleOrder())) {
            disable(action.mod());
        }
    }

    public synchronized void enableMod(LuaMod mod) {
        if (mod != null) enable(mod);
    }

    public synchronized void reloadMod(LuaMod mod) {
        if (mod != null) reload(mod);
    }

    public synchronized void disableMod(LuaMod mod) {
        if (mod != null) disable(mod);
    }

    /**
     * Reloads all mods with rollback semantics.
     * For each enabled mod, it loads a new instance first and only swaps on successful enable.
     */
    public synchronized void reloadAll() {
        scanMods();
        for (LuaLifecyclePlanner.Action action : LuaLifecyclePlanner.planReloadAll(resolveLifecycleOrder())) {
            if (action.type() == LuaLifecyclePlanner.Type.RELOAD) reload(action.mod());
            else enable(action.mod());
        }
    }

    /** Returns a snapshot of mods that currently have errors. */
    public synchronized List<LuaMod> modsWithErrors() {
        List<LuaMod> out = new ArrayList<>();
        for (LuaMod m : mods.values()) {
            if (m.lastError() != null && !m.lastError().isBlank()) out.add(m);
        }
        return out;
    }

    private void enable(LuaMod mod) {
        long start = System.nanoTime();
        boolean success = LuaLifecycleOperations.enable(
                mod,
                this::loadInstance,
                this::setError,
                message -> plugin.getLogger().at(Level.INFO).log(message),
                message -> plugin.getLogger().at(Level.WARNING).log(message)
        );
        recordInvocation(mod, "lifecycle:enable", start, !success);
    }

    private void reload(LuaMod mod) {
        long start = System.nanoTime();
        boolean success = LuaLifecycleOperations.reload(
                mod,
                this::loadInstance,
                this::setError,
                message -> plugin.getLogger().at(Level.INFO).log(message),
                message -> plugin.getLogger().at(Level.WARNING).log(message)
        );
        recordInvocation(mod, "lifecycle:reload", start, !success);
    }

    private void disable(LuaMod mod) {
        long start = System.nanoTime();
        boolean success = LuaLifecycleOperations.disable(
                mod,
                this::setError,
                message -> plugin.getLogger().at(Level.INFO).log(message),
                message -> plugin.getLogger().at(Level.WARNING).log(message)
        );
        recordInvocation(mod, "lifecycle:disable", start, !success);
    }

    private LuaLoadedInstance loadInstance(ModManifest mf) throws IOException {
        var dataDir = plugin.getLuaDataDir().resolve(mf.id());
        Files.createDirectories(dataDir);
        LuaModContext ctx = new LuaModContext(plugin, this, mf.id(), mf.dir(), dataDir);
        Globals g = new LuaEngine(plugin, timerScheduler).createGlobals(ctx);

        Path entryPath = mf.dir().resolve(mf.entry());
        LuaTable module = LuaModuleLoader.loadModule(g, mf.id(), mf.entry(), entryPath);
        return new LuaLoadedInstance(g, module, ctx);
    }

    void recordRuntimeError(LuaMod mod, String prefix, Throwable t) {
        LuaRuntimeErrors.record(mod, prefix, t);
    }

    private void setError(LuaMod mod, String prefix, Throwable t) {
        recordRuntimeError(mod, prefix, t);
    }

    public synchronized LuaMod findById(String id) {
        if (id == null) return null;
        for (LuaMod m : mods.values()) {
            if (m.manifest().id().equalsIgnoreCase(id)) return m;
        }
        return null;
    }

    public synchronized boolean invokeModCommand(String modId, String command, Object senderObj, java.util.List<String> args) {
        LuaMod mod = findById(modId);
        if (mod == null || mod.ctx() == null) return false;
        if (mod.state() != LuaModState.ENABLED) return false;

        LuaSender sender = new LuaSender(senderObj);

        LuaTable argTable = LuaDispatchSupport.commandArgs(args);
        int argCount = args == null ? 0 : args.size();

        long start = System.nanoTime();
        boolean failed = false;
        String invocationKey = "command:" + command;
        try {
            LuaValue ret = mod.ctx().invokeCommand(command, sender, argTable);
            boolean ok = !ret.isnil();
            failed = !ok;
            if (ok && mod.traces(invocationKey)) {
                plugin.getLogger().at(Level.INFO).log(
                        "Lua trace: mod=" + mod.manifest().id() + " key=" + invocationKey + " argCount=" + argCount
                );
            }
            return ok;
        } catch (Throwable t) {
            failed = true;
            setError(mod, "Command error", t);
            return false;
        } finally {
            recordInvocation(mod, invocationKey, start, failed);
        }
    }

    public synchronized void emitToEnabled(String event) {
        emitToEnabled(event, new LuaValue[0]);
    }

    public synchronized void emitToEnabled(String event, LuaValue... args) {
        if (debugLogging) {
            plugin.getLogger().at(Level.INFO).log("Emitting Lua event " + event + " to enabled mods (" + (args == null ? 0 : args.length) + " args).");
        }
        String invocationKey = "event:" + event;
        LuaDispatchSupport.dispatch(
                enabledModsOrdered(),
                mod -> mod.ctx() != null,
                m -> {
                    long start = System.nanoTime();
                    boolean[] failed = {false};
                    try {
                        m.ctx().emit(event, args);
                        if (m.traces(invocationKey)) {
                            plugin.getLogger().at(Level.INFO).log(
                                    "Lua trace: mod=" + m.manifest().id() + " key=" + invocationKey + " argCount=" + (args == null ? 0 : args.length)
                            );
                        }
                    } catch (Throwable t) {
                        failed[0] = true;
                        setError(m, "Event error (" + event + ")", t);
                        if (debugLogging) {
                            plugin.getLogger().at(Level.WARNING).log("Lua event " + event + " failed for mod " + m.manifest().id() + ": " + t);
                        }
                        throw t;
                    } finally {
                        recordInvocation(m, invocationKey, start, failed[0]);
                    }
                },
                null,
                null
        );
    }

    public void dispatchNativeBlockBehavior(String eventName, Object rawEvent) {
        blockBehaviorRuntime.handleNativeBlockEvent(eventName, rawEvent);
    }

    public synchronized int dispatchNetworkMessage(String fromModId, String channel, LuaValue payload) {
        String normalizedChannel = LuaDispatchSupport.normalizedNetworkChannel(channel);
        if (normalizedChannel == null) return 0;
        String event = "network:" + normalizedChannel;
        LuaTable envelope = LuaDispatchSupport.networkEnvelope(fromModId, channel, payload, System.currentTimeMillis());

        return LuaDispatchSupport.dispatch(
                mods.values(),
                m -> m.state() == LuaModState.ENABLED && m.ctx() != null,
                m -> {
                    long start = System.nanoTime();
                    boolean[] failed = {false};
                    try {
                        m.ctx().emit(event, envelope);
                        if (m.traces(event)) {
                            plugin.getLogger().at(Level.INFO).log(
                                    "Lua trace: mod=" + m.manifest().id() + " key=" + event + " fromMod=" + (fromModId == null ? "" : fromModId)
                            );
                        }
                    } catch (Throwable t) {
                        failed[0] = true;
                        setError(m, "Network channel error (" + event + ")", t);
                        if (debugLogging) {
                            plugin.getLogger().at(Level.WARNING).log("Lua network event " + event + " failed for mod " + m.manifest().id() + ": " + t);
                        }
                        throw t;
                    } finally {
                        recordInvocation(m, "network:" + normalizedChannel, start, failed[0]);
                    }
                },
                null,
                null
        );
    }

    public synchronized void startTickLoop() {
        if (tickLoop != null && !tickLoop.isCancelled()) return;
        tickLoop = tickScheduler.setInterval(50L, this::runTickLoopSafely);
        plugin.getLogger().at(Level.INFO).log("Lua tick loop started (50ms phases: pre_tick/tick/post_tick).");
    }

    public synchronized void stopTickLoop() {
        if (tickLoop == null) return;
        try {
            tickLoop.cancel(false);
        } catch (Throwable ignored) { }
        tickLoop = null;
        plugin.getLogger().at(Level.INFO).log("Lua tick loop stopped.");
    }

    private synchronized void emitTickPhases() {
        long now = System.currentTimeMillis();
        long tick = ++tickCounter;

        LuaTable payload = new LuaTable();
        payload.set("tick", LuaValue.valueOf(tick));
        payload.set("timestampMs", LuaValue.valueOf(now));

        emitToEnabled("pre_tick", payload);
        simulationRuntime.tickPhase("pre_tick", tick, now);
        emitToEnabled("tick", payload);
        simulationRuntime.tickPhase("tick", tick, now);
        emitToEnabled("post_tick", payload);
        simulationRuntime.tickPhase("post_tick", tick, now);
        actorRuntime.tick(tick, now);
        standInRuntime.tick(tick, now);
        volumeRuntime.tick(tick, now);
        mechanicsRuntime.tick(tick, now);
        transformRuntime.tick(tick, now);
        applyQueuedBlockEditsBudget();
    }

    private void runTickLoopSafely() {
        try {
            emitTickPhases();
        } catch (Throwable t) {
            plugin.getLogger().at(Level.SEVERE).log("Lua tick loop iteration failed but will continue: " + t);
        }
    }

    private void applyQueuedBlockEditsBudget() {
        int remaining = Math.max(0, plugin.getBlockEditBudgetPerTick());
        if (remaining <= 0) return;
        int applied = 0;
        for (LuaMod mod : enabledModsOrdered()) {
            if (remaining <= 0) break;
            if (mod.ctx() == null) continue;
            int n = mod.ctx().applyBlockEdits(remaining);
            if (n > 0) {
                applied += n;
                remaining -= n;
            }
        }
        if (applied > 0 && debugLogging) {
            plugin.getLogger().at(Level.INFO).log("Applied queued block edits this tick: " + applied + " (remaining budget " + remaining + ")");
        }
    }

    public synchronized void close() {
        stopTickLoop();
        try { disableAll(); } catch (Throwable ignored) { }
        try { tickScheduler.close(); } catch (Throwable ignored) { }
        try { timerScheduler.close(); } catch (Throwable ignored) { }
    }

    public synchronized void resetProfileMetrics() {
        for (LuaMod m : mods.values()) {
            m.resetInvocationMetrics();
        }
    }

    public synchronized boolean resetProfileMetrics(String modId) {
        LuaMod mod = findById(modId);
        if (mod == null) return false;
        mod.resetInvocationMetrics();
        return true;
    }

    public synchronized Path dumpProfileSnapshot(Path logsDir) throws IOException {
        Objects.requireNonNull(logsDir, "logsDir");
        Files.createDirectories(logsDir);
        String ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());
        Path out = logsDir.resolve("arcane-lua-profile-" + ts + ".log");
        String snapshot = LuaProfileSnapshotRenderer.render(
                mods.values(),
                plugin.getSlowCallWarnMs(),
                debugLogging,
                java.time.Instant.now(),
                mod -> mod.ctx() == null ? 0 : mod.ctx().commandNames().size()
        );
        Files.writeString(out, snapshot, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return out;
    }

    public synchronized boolean addTraceKey(String modId, String key) {
        LuaMod mod = findById(modId);
        if (mod == null) return false;
        String normalized = normalizeTraceKey(key);
        if (normalized == null) return false;
        return mod.addTraceKey(normalized);
    }

    public synchronized boolean removeTraceKey(String modId, String key) {
        LuaMod mod = findById(modId);
        if (mod == null) return false;
        String normalized = normalizeTraceKey(key);
        if (normalized == null) return false;
        return mod.removeTraceKey(normalized);
    }

    public synchronized boolean clearTraceKeys(String modId) {
        LuaMod mod = findById(modId);
        if (mod == null) return false;
        mod.clearTraceKeys();
        return true;
    }

    public synchronized Set<String> traceKeys(String modId) {
        LuaMod mod = findById(modId);
        if (mod == null) return Collections.emptySet();
        return mod.traceKeys();
    }

    /** Dev helper: evaluate a Lua snippet in a mod's globals. */
    public synchronized boolean evalInMod(String modId, String code, Object senderObj) {
        LuaMod mod = findById(modId);
        if (mod == null || mod.state() != LuaModState.ENABLED || mod.globals() == null) return false;

        LuaSender sender = new LuaSender(senderObj);
        long start = System.nanoTime();
        boolean failed = false;
        try {
            mod.globals().set("sender", org.luaj.vm2.LuaValue.userdataOf(sender));
            org.luaj.vm2.LuaValue chunk = mod.globals().load(code, "eval");
            chunk.call();
            return true;
        } catch (Throwable t) {
            failed = true;
            setError(mod, "Eval error", t);
            return false;
        } finally {
            recordInvocation(mod, "eval:dev", start, failed);
        }
    }

    void recordRuntimeInvocation(LuaMod mod, String key, long startNanos, boolean failed) {
        recordInvocation(mod, key, startNanos, failed);
    }

    private void recordInvocation(LuaMod mod, String key, long startNanos, boolean failed) {
        long elapsedNanos = Math.max(0L, System.nanoTime() - startNanos);
        long thresholdNanos = Math.max(1L, (long) (plugin.getSlowCallWarnMs() * 1_000_000.0));
        mod.recordInvocation(key, elapsedNanos, failed, thresholdNanos);
        if (elapsedNanos >= thresholdNanos && (debugLogging || failed)) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Slow Lua call: mod=" + mod.manifest().id()
                            + " key=" + key
                            + " tookMs=" + String.format(java.util.Locale.ROOT, "%.2f", elapsedNanos / 1_000_000.0)
            );
        }
    }
}
