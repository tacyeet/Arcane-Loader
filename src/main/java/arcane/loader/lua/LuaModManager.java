package arcane.loader.lua;

import arcane.loader.ArcaneLoaderPlugin;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final LuaTaskScheduler scheduler = new LuaTaskScheduler();

    private final ArcaneLoaderPlugin plugin;
    private final boolean devMode;
    private volatile boolean debugLogging;
    private final LuaAssetManager assetManager;

    // modId -> LuaMod (kept stable across reloadAll so we can rollback safely)
    private final Map<String, LuaMod> mods = new LinkedHashMap<>();
    private java.util.concurrent.ScheduledFuture<?> tickLoop;
    private long tickCounter;

    public LuaModManager(ArcaneLoaderPlugin plugin, boolean devMode) {
        this.plugin = plugin;
        this.devMode = devMode;
        this.assetManager = new LuaAssetManager(plugin);
    }

    public LuaAssetManager assetManager() { return assetManager; }

    public Collection<LuaMod> listMods() {
        return Collections.unmodifiableCollection(mods.values());
    }

    private List<LuaMod> enabledModsOrdered() {
        ArrayList<LuaMod> out = new ArrayList<>();
        for (LuaMod m : resolveLifecycleOrder()) {
            if (m.state() == LuaModState.ENABLED && m.ctx() != null) out.add(m);
        }
        return out;
    }

    private List<LuaMod> resolveLifecycleOrder() {
        // Nodes by modId
        Map<String, LuaMod> byId = new LinkedHashMap<>();
        for (LuaMod mod : mods.values()) {
            byId.put(mod.manifest().id(), mod);
        }
        if (byId.size() <= 1) return new ArrayList<>(byId.values());

        // Graph: edge A -> B means A should be ordered before B.
        Map<String, Set<String>> graph = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String id : byId.keySet()) {
            graph.put(id, new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
            indegree.put(id, 0);
        }

        for (LuaMod mod : byId.values()) {
            String src = mod.manifest().id();
            for (String before : mod.manifest().loadBefore()) {
                String dst = findCanonicalId(byId, before);
                if (dst == null || dst.equalsIgnoreCase(src)) continue;
                if (graph.get(src).add(dst)) {
                    indegree.put(dst, indegree.get(dst) + 1);
                }
            }
            for (String after : mod.manifest().loadAfter()) {
                String prev = findCanonicalId(byId, after);
                if (prev == null || prev.equalsIgnoreCase(src)) continue;
                if (graph.get(prev).add(src)) {
                    indegree.put(src, indegree.get(src) + 1);
                }
            }
        }

        // Kahn with deterministic tie-break by modId.
        PriorityQueue<String> queue = new PriorityQueue<>(String.CASE_INSENSITIVE_ORDER);
        for (var ent : indegree.entrySet()) {
            if (ent.getValue() == 0) queue.add(ent.getKey());
        }

        ArrayList<String> orderedIds = new ArrayList<>(byId.size());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            orderedIds.add(id);
            for (String nxt : graph.get(id)) {
                int d = indegree.get(nxt) - 1;
                indegree.put(nxt, d);
                if (d == 0) queue.add(nxt);
            }
        }

        if (orderedIds.size() != byId.size()) {
            // Cycle or unresolved constraints: append remaining IDs deterministically.
            TreeSet<String> remaining = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (String id : byId.keySet()) {
                if (!orderedIds.contains(id)) remaining.add(id);
            }
            orderedIds.addAll(remaining);
            plugin.getLogger().at(Level.WARNING).log(
                    "Lua mod dependency cycle/unresolved order detected. Falling back to ID order for: "
                            + String.join(", ", remaining)
            );
        }

        ArrayList<LuaMod> out = new ArrayList<>(orderedIds.size());
        for (String id : orderedIds) out.add(byId.get(id));
        return out;
    }

    private static String findCanonicalId(Map<String, LuaMod> byId, String requestedId) {
        if (requestedId == null || requestedId.isBlank()) return null;
        for (String id : byId.keySet()) {
            if (id.equalsIgnoreCase(requestedId)) return id;
        }
        return null;
    }

    public LuaMod get(String modId) {
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
    public void scanMods() {
        Path root = plugin.getLuaModsDir();
        if (!Files.isDirectory(root)) {
            plugin.getLogger().at(Level.WARNING).log("lua_mods directory missing: " + root);
            return;
        }

        // Discover manifests
        Map<String, ModManifest> found = new LinkedHashMap<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path dir : ds) {
                if (!Files.isDirectory(dir)) continue;
                Path manifestPath = dir.resolve("manifest.json");
                if (!Files.isRegularFile(manifestPath)) continue;

                try {
                    ModManifest mf = parseManifest(dir, manifestPath);
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

        // Remove mods that no longer exist on disk (disable them first)
        Iterator<Map.Entry<String, LuaMod>> it = mods.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, LuaMod> ent = it.next();
            if (!found.containsKey(ent.getKey())) {
                LuaMod mod = ent.getValue();
                if (mod.state() == LuaModState.ENABLED) {
                    disable(mod);
                }
                assetManager.removeMod(ent.getKey());
                it.remove();
            }
        }

        // Add new mods, update manifests for existing
        for (ModManifest mf : found.values()) {
            LuaMod existing = mods.get(mf.id());
            if (existing == null) {
                mods.put(mf.id(), new LuaMod(mf));
            } else {
                // Keep instance, but update manifest (dir/version/entry may change)
                // (LuaMod stores manifest final in this stage; simplest approach: replace only if disabled)
                if (existing.state() != LuaModState.ENABLED) {
                    mods.put(mf.id(), new LuaMod(mf));
                }
            }
            if (plugin.isAutoStageAssets()) {
                assetManager.stage(mf);
            }
        }
    }

    /** Enables all discovered mods. */
    public void enableAll() {
        scanMods();
        for (LuaMod mod : resolveLifecycleOrder()) {
            if (mod.state() != LuaModState.ENABLED) {
                enable(mod);
            }
        }
    }

    public void disableAll() {
        List<LuaMod> ordered = resolveLifecycleOrder();
        Collections.reverse(ordered);
        for (LuaMod mod : ordered) {
            if (mod.state() == LuaModState.ENABLED) {
                disable(mod);
            }
        }
    }

    public void enableMod(LuaMod mod) {
        if (mod != null) enable(mod);
    }

    public void reloadMod(LuaMod mod) {
        if (mod != null) reload(mod);
    }

    public void disableMod(LuaMod mod) {
        if (mod != null) disable(mod);
    }

    /**
     * Reloads all mods with rollback semantics.
     * For each enabled mod, it loads a new instance first and only swaps on successful enable.
     */
    public void reloadAll() {
        scanMods();
        for (LuaMod mod : resolveLifecycleOrder()) {
            if (mod.state() == LuaModState.ENABLED) {
                reload(mod);
            } else {
                // try enabling mods that were disabled/error
                enable(mod);
            }
        }
    }

    /** Returns a snapshot of mods that currently have errors. */
    public List<LuaMod> modsWithErrors() {
        List<LuaMod> out = new ArrayList<>();
        for (LuaMod m : mods.values()) {
            if (m.lastError() != null && !m.lastError().isBlank()) out.add(m);
        }
        return out;
    }

    private void enable(LuaMod mod) {
        long start = System.nanoTime();
        boolean failed = false;
        try {
            LoadedInstance inst = loadInstance(mod.manifest());
            // swap in loaded runtime first
            mod.ctx(inst.ctx);
            mod.globals(inst.globals);
            mod.module(inst.module);
            mod.state(LuaModState.LOADED);

            // then call lifecycle hook and transition to enabled
            callOptional(inst.module, "onEnable", inst.ctx);
            mod.lastError(null);
            mod.lastErrorDetail(null);
            mod.lastLoadEpochMs(System.currentTimeMillis());
            mod.state(LuaModState.ENABLED);

            plugin.getLogger().at(Level.INFO).log("Enabled Lua mod: " + mod.manifest().id());
        } catch (Exception e) {
            failed = true;
            setError(mod, "Failed enabling", e);
            mod.state(LuaModState.ERROR);
            plugin.getLogger().at(Level.WARNING).log("Failed enabling Lua mod " + mod.manifest().id() + ": " + e);
        } finally {
            recordInvocation(mod, "lifecycle:enable", start, failed);
        }
    }

    private void reload(LuaMod mod) {
        long start = System.nanoTime();
        boolean failed = false;
        // keep old references
        Globals oldG = mod.globals();
        LuaTable oldModule = mod.module();
        LuaModContext oldCtx = mod.ctx();

        try {
            LoadedInstance inst = loadInstance(mod.manifest());
            mod.state(LuaModState.LOADED);

            // new instance enabled successfully -> now disable old
            if (oldModule != null && oldG != null && oldCtx != null) {
                callOptional(oldModule, "onDisable", oldCtx);
            }

            callOptional(inst.module, "onEnable", inst.ctx);

            // swap
            mod.ctx(inst.ctx);
            mod.globals(inst.globals);
            mod.module(inst.module);
            mod.lastError(null);
            mod.lastErrorDetail(null);
            mod.lastLoadEpochMs(System.currentTimeMillis());
            mod.state(LuaModState.ENABLED);

            plugin.getLogger().at(Level.INFO).log("Reloaded Lua mod: " + mod.manifest().id());
        } catch (Exception e) {
            failed = true;
            // rollback: keep old instance running
            mod.ctx(oldCtx);
            mod.globals(oldG);
            mod.module(oldModule);
            mod.state(LuaModState.ENABLED);
            setError(mod, "Failed reloading (kept last-known-good)", e);
            plugin.getLogger().at(Level.WARNING).log("Failed reloading Lua mod " + mod.manifest().id() + " (kept last-known-good): " + e);
        } finally {
            recordInvocation(mod, "lifecycle:reload", start, failed);
        }
    }

    private void disable(LuaMod mod) {
        long start = System.nanoTime();
        boolean failed = false;
        try {
            if (mod.module() != null && mod.ctx() != null) {
                callOptional(mod.module(), "onDisable", mod.ctx());
            }
        } catch (Exception e) {
            failed = true;
            setError(mod, "Error disabling", e);
            plugin.getLogger().at(Level.WARNING).log("Error disabling Lua mod " + mod.manifest().id() + ": " + e);
        } finally {
            mod.clearInstance();
            mod.state(LuaModState.DISABLED);
            plugin.getLogger().at(Level.INFO).log("Disabled Lua mod: " + mod.manifest().id());
            recordInvocation(mod, "lifecycle:disable", start, failed);
        }
    }

    private LoadedInstance loadInstance(ModManifest mf) throws IOException {
        var dataDir = plugin.getLuaDataDir().resolve(mf.id());
        Files.createDirectories(dataDir);
        LuaModContext ctx = new LuaModContext(plugin, this, mf.id(), mf.dir(), dataDir);
        Globals g = new LuaEngine(plugin, scheduler).createGlobals(ctx);

        // Load entry
        Path entryPath = mf.dir().resolve(mf.entry());
        if (!Files.isRegularFile(entryPath)) {
            throw new IllegalStateException("Missing entry file: " + entryPath);
        }

        String code = Files.readString(entryPath, StandardCharsets.UTF_8);
        LuaValue chunk = g.load(code, mf.id() + "/" + mf.entry());

        // If chunk compile fails, this throws (caught by caller)
        LuaValue ret = chunk.call();

        if (!ret.istable()) {
            throw new IllegalStateException("init.lua must return a table (got " + ret.typename() + ")");
        }

        LuaTable module = (LuaTable) ret;

        return new LoadedInstance(g, module, ctx);
    }

    private void callOptional(LuaTable module, String fnName, LuaModContext ctx) {
        LuaValue fn = module.get(fnName);
        if (fn.isfunction()) {
            LuaValue luaCtx = CoerceJavaToLua.coerce(ctx);
            ((LuaFunction) fn).call(luaCtx);
        }
    }

    private void setError(LuaMod mod, String prefix, Throwable t) {
        String msg = prefix + ": " + t;
        mod.lastError(msg);
        mod.lastErrorDetail(stacktrace(t));
        mod.recordError(Instant.now() + " " + msg);
    }

    private static String stacktrace(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static final Pattern FIELD = Pattern.compile("\"(id|name|version|entry)\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ARRAY_FIELD = Pattern.compile("\"(loadBefore|loadAfter)\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);

    private static ModManifest parseManifest(Path dir, Path manifestPath) throws IOException {
        String json = Files.readString(manifestPath, StandardCharsets.UTF_8);

        Map<String, String> map = new HashMap<>();
        Matcher m = FIELD.matcher(json);
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }

        String id = map.get("id");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("manifest missing id");
        String name = map.getOrDefault("name", id);
        String version = map.getOrDefault("version", "0.0.0");
        String entry = map.getOrDefault("entry", "init.lua");
        Set<String> loadBefore = parseArrayField(json, "loadBefore");
        Set<String> loadAfter = parseArrayField(json, "loadAfter");
        return new ModManifest(id, name, version, entry, loadBefore, loadAfter, dir);
    }

    private static Set<String> parseArrayField(String json, String key) {
        Matcher m = ARRAY_FIELD.matcher(json);
        while (m.find()) {
            String k = m.group(1);
            if (!key.equals(k)) continue;
            String body = m.group(2);
            Matcher v = Pattern.compile("\"([^\"]+)\"").matcher(body);
            TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            while (v.find()) {
                String val = v.group(1).trim();
                if (!val.isEmpty()) out.add(val);
            }
            return Collections.unmodifiableSet(out);
        }
        return Collections.emptySet();
    }


    public LuaMod findById(String id) {
        if (id == null) return null;
        for (LuaMod m : mods.values()) {
            if (m.manifest().id().equalsIgnoreCase(id)) return m;
        }
        return null;
    }

    public boolean invokeModCommand(String modId, String command, Object senderObj, java.util.List<String> args) {
        LuaMod mod = findById(modId);
        if (mod == null || mod.ctx() == null) return false;
        if (mod.state() != LuaModState.ENABLED) return false;

        LuaSender sender = new LuaSender(senderObj);

        LuaTable argTable = new LuaTable();
        int i = 1;
        for (String a : args) {
            argTable.set(i++, LuaValue.valueOf(a));
        }

        long start = System.nanoTime();
        boolean failed = false;
        String invocationKey = "command:" + command;
        try {
            LuaValue ret = mod.ctx().invokeCommand(command, sender, argTable);
            boolean ok = !ret.isnil();
            failed = !ok;
            if (ok && mod.traces(invocationKey)) {
                plugin.getLogger().at(Level.INFO).log(
                        "Lua trace: mod=" + mod.manifest().id() + " key=" + invocationKey + " argCount=" + args.size()
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

    public void emitToEnabled(String event) {
        emitToEnabled(event, new LuaValue[0]);
    }

    public void emitToEnabled(String event, LuaValue... args) {
        if (debugLogging) {
            plugin.getLogger().at(Level.INFO).log("Emitting Lua event " + event + " to enabled mods (" + (args == null ? 0 : args.length) + " args).");
        }
        String invocationKey = "event:" + event;
        for (LuaMod m : enabledModsOrdered()) {
            long start = System.nanoTime();
            boolean failed = false;
            try {
                m.ctx().emit(event, args);
                if (m.traces(invocationKey)) {
                    plugin.getLogger().at(Level.INFO).log(
                            "Lua trace: mod=" + m.manifest().id() + " key=" + invocationKey + " argCount=" + (args == null ? 0 : args.length)
                    );
                }
            } catch (Throwable t) {
                failed = true;
                setError(m, "Event error (" + event + ")", t);
                if (debugLogging) {
                    plugin.getLogger().at(Level.WARNING).log("Lua event " + event + " failed for mod " + m.manifest().id() + ": " + t);
                }
            } finally {
                recordInvocation(m, invocationKey, start, failed);
            }
        }
    }

    public int dispatchNetworkMessage(String fromModId, String channel, LuaValue payload) {
        if (channel == null || channel.isBlank()) return 0;
        String normalizedChannel = channel.trim().toLowerCase(Locale.ROOT);
        String event = "network:" + normalizedChannel;
        LuaTable envelope = new LuaTable();
        envelope.set("channel", LuaValue.valueOf(channel));
        envelope.set("fromModId", LuaValue.valueOf(fromModId == null ? "" : fromModId));
        envelope.set("payload", payload == null ? LuaValue.NIL : payload);
        envelope.set("timestampMs", LuaValue.valueOf(System.currentTimeMillis()));

        int delivered = 0;
        for (LuaMod m : mods.values()) {
            if (m.state() != LuaModState.ENABLED || m.ctx() == null) continue;
            long start = System.nanoTime();
            boolean failed = false;
            try {
                m.ctx().emit(event, envelope);
                delivered++;
                if (m.traces(event)) {
                    plugin.getLogger().at(Level.INFO).log(
                            "Lua trace: mod=" + m.manifest().id() + " key=" + event + " fromMod=" + (fromModId == null ? "" : fromModId)
                    );
                }
            } catch (Throwable t) {
                failed = true;
                setError(m, "Network channel error (" + event + ")", t);
                if (debugLogging) {
                    plugin.getLogger().at(Level.WARNING).log("Lua network event " + event + " failed for mod " + m.manifest().id() + ": " + t);
                }
            } finally {
                recordInvocation(m, "network:" + normalizedChannel, start, failed);
            }
        }
        return delivered;
    }

    public void startTickLoop() {
        if (tickLoop != null && !tickLoop.isCancelled()) return;
        tickLoop = scheduler.setInterval(50L, this::emitTickPhases);
        plugin.getLogger().at(Level.INFO).log("Lua tick loop started (50ms phases: pre_tick/tick/post_tick).");
    }

    public void stopTickLoop() {
        if (tickLoop == null) return;
        try {
            tickLoop.cancel(false);
        } catch (Throwable ignored) { }
        tickLoop = null;
        plugin.getLogger().at(Level.INFO).log("Lua tick loop stopped.");
    }

    private void emitTickPhases() {
        long now = System.currentTimeMillis();
        long tick = ++tickCounter;

        LuaTable payload = new LuaTable();
        payload.set("tick", LuaValue.valueOf(tick));
        payload.set("timestampMs", LuaValue.valueOf(now));

        emitToEnabled("pre_tick", payload);
        emitToEnabled("tick", payload);
        emitToEnabled("post_tick", payload);
        applyQueuedBlockEditsBudget();
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

    public void close() {
        stopTickLoop();
        try { disableAll(); } catch (Throwable ignored) { }
        try { scheduler.close(); } catch (Throwable ignored) { }
    }

    public void resetProfileMetrics() {
        for (LuaMod m : mods.values()) {
            m.resetInvocationMetrics();
        }
    }

    public boolean resetProfileMetrics(String modId) {
        LuaMod mod = findById(modId);
        if (mod == null) return false;
        mod.resetInvocationMetrics();
        return true;
    }

    public Path dumpProfileSnapshot(Path logsDir) throws IOException {
        Objects.requireNonNull(logsDir, "logsDir");
        Files.createDirectories(logsDir);
        String ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());
        Path out = logsDir.resolve("arcane-lua-profile-" + ts + ".log");

        StringBuilder sb = new StringBuilder(4096);
        sb.append("Arcane Loader Lua Profile Snapshot").append('\n');
        sb.append("generatedAtUtc=").append(java.time.Instant.now()).append('\n');
        sb.append("slowCallWarnMs=").append(plugin.getSlowCallWarnMs()).append('\n');
        sb.append("debugLogging=").append(debugLogging).append('\n');
        sb.append('\n');

        List<LuaMod> ordered = new ArrayList<>(mods.values());
        ordered.sort(Comparator.comparing(m -> m.manifest().id(), String.CASE_INSENSITIVE_ORDER));
        for (LuaMod mod : ordered) {
            long invocations = mod.invocationCount();
            double avgMs = invocations == 0 ? 0.0 : (mod.totalInvocationNanos() / 1_000_000.0) / invocations;
            double maxMs = mod.maxInvocationNanos() / 1_000_000.0;
            sb.append("mod=").append(mod.manifest().id())
                    .append(" state=").append(mod.state())
                    .append(" commands=").append(mod.ctx() == null ? 0 : mod.ctx().commandNames().size())
                    .append(" errors=").append(mod.errorHistory().size())
                    .append(" invocations=").append(invocations)
                    .append(" failures=").append(mod.invocationFailures())
                    .append(" slowCalls=").append(mod.slowInvocationCount())
                    .append(" avgMs=").append(String.format(Locale.ROOT, "%.3f", avgMs))
                    .append(" maxMs=").append(String.format(Locale.ROOT, "%.3f", maxMs))
                    .append(" lastLoadMs=").append(mod.lastLoadEpochMs())
                    .append('\n');
            var breakdown = new ArrayList<>(mod.invocationBreakdown().entrySet());
            breakdown.sort((a, b) -> Long.compare(b.getValue().totalNanos(), a.getValue().totalNanos()));
            int limit = Math.min(20, breakdown.size());
            for (int i = 0; i < limit; i++) {
                var ent = breakdown.get(i);
                var stats = ent.getValue();
                double bucketAvgMs = stats.count() == 0 ? 0.0 : (stats.totalNanos() / 1_000_000.0) / stats.count();
                double bucketMaxMs = stats.maxNanos() / 1_000_000.0;
                sb.append("  key=").append(ent.getKey())
                        .append(" count=").append(stats.count())
                        .append(" fail=").append(stats.failures())
                        .append(" slow=").append(stats.slowCount())
                        .append(" avgMs=").append(String.format(Locale.ROOT, "%.3f", bucketAvgMs))
                        .append(" maxMs=").append(String.format(Locale.ROOT, "%.3f", bucketMaxMs))
                        .append('\n');
            }
            if (!mod.traceKeys().isEmpty()) {
                sb.append("  traces=").append(String.join(",", mod.traceKeys())).append('\n');
            }
            sb.append('\n');
        }

        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return out;
    }

    public boolean addTraceKey(String modId, String key) {
        LuaMod mod = findById(modId);
        if (mod == null) return false;
        String normalized = normalizeTraceKey(key);
        if (normalized == null) return false;
        return mod.addTraceKey(normalized);
    }

    public boolean removeTraceKey(String modId, String key) {
        LuaMod mod = findById(modId);
        if (mod == null) return false;
        String normalized = normalizeTraceKey(key);
        if (normalized == null) return false;
        return mod.removeTraceKey(normalized);
    }

    public boolean clearTraceKeys(String modId) {
        LuaMod mod = findById(modId);
        if (mod == null) return false;
        mod.clearTraceKeys();
        return true;
    }

    public Set<String> traceKeys(String modId) {
        LuaMod mod = findById(modId);
        if (mod == null) return Collections.emptySet();
        return mod.traceKeys();
    }

    /** Dev helper: evaluate a Lua snippet in a mod's globals. */
    public boolean evalInMod(String modId, String code, Object senderObj) {
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

    private record LoadedInstance(Globals globals, LuaTable module, LuaModContext ctx) {}
}
