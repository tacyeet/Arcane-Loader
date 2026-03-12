package arcane.loader.lua;

import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class LuaSimulationRuntime {
    private final LuaModManager manager;
    private final Map<String, Map<String, SystemRecord>> systemsByMod = new ConcurrentHashMap<>();

    public LuaSimulationRuntime(LuaModManager manager) {
        this.manager = manager;
    }

    public Map<String, Object> register(LuaModContext ctx, LuaTable options) {
        if (ctx == null) return null;
        String systemId = nextSystemId(options);
        String modId = normalize(ctx.modId());
        SystemRecord record = new SystemRecord(
                systemId,
                modId,
                normalize(options == null ? null : options.get("kind").optjstring("system"), "system"),
                normalize(options == null ? null : options.get("phase").optjstring("tick"), "tick"),
                Math.max(1, options == null ? 1 : options.get("updateEveryTicks").optint(1)),
                Math.max(1, options == null ? 1 : options.get("maxCatchUpSteps").optint(1)),
                readTags(options == null ? null : options.get("tags")),
                options != null && options.get("state").istable() ? (LuaTable) options.get("state") : new LuaTable(),
                asFunction(options == null ? LuaValue.NIL : options.get("onStep")),
                asFunction(options == null ? LuaValue.NIL : options.get("onDirty")),
                false,
                0L
        );
        systemsByMod.computeIfAbsent(modId, ignored -> new ConcurrentHashMap<>()).put(systemId, record);
        return toMap(record);
    }

    public boolean unregister(String modId, String systemId) {
        Map<String, SystemRecord> systems = systemsByMod.get(normalize(modId));
        if (systems == null) return false;
        SystemRecord removed = systems.remove(normalize(systemId));
        if (systems.isEmpty()) systemsByMod.remove(normalize(modId), systems);
        return removed != null;
    }

    public int clear(String modId) {
        Map<String, SystemRecord> removed = systemsByMod.remove(normalize(modId));
        return removed == null ? 0 : removed.size();
    }

    public Map<String, Object> find(LuaModContext ctx, String systemId) {
        SystemRecord record = findRecord(ctx == null ? null : ctx.modId(), systemId);
        return record == null ? null : toMap(record);
    }

    public List<Map<String, Object>> list(LuaModContext ctx) {
        if (ctx == null) return List.of();
        Map<String, SystemRecord> systems = systemsByMod.get(normalize(ctx.modId()));
        if (systems == null || systems.isEmpty()) return List.of();
        ArrayList<SystemRecord> ordered = new ArrayList<>(systems.values());
        ordered.sort((a, b) -> a.systemId().compareToIgnoreCase(b.systemId()));
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (SystemRecord record : ordered) out.add(Collections.unmodifiableMap(toMap(record)));
        return Collections.unmodifiableList(out);
    }

    public List<Map<String, Object>> listByKind(LuaModContext ctx, String kind) {
        if (ctx == null) return List.of();
        String normalizedKind = normalize(kind);
        if (normalizedKind == null) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : list(ctx)) {
            if (normalizedKind.equals(normalize(String.valueOf(row.get("kind"))))) out.add(row);
        }
        return Collections.unmodifiableList(out);
    }

    public List<Map<String, Object>> listByTag(LuaModContext ctx, String tag) {
        if (ctx == null) return List.of();
        String normalizedTag = normalize(tag);
        if (normalizedTag == null) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : list(ctx)) {
            Object tags = row.get("tags");
            if (!(tags instanceof List<?> list)) continue;
            for (Object value : list) {
                if (normalizedTag.equals(normalize(String.valueOf(value)))) {
                    out.add(row);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    public LuaTable state(String modId, String systemId) {
        SystemRecord record = findRecord(modId, systemId);
        return record == null ? null : record.state();
    }

    public boolean setState(String modId, String systemId, LuaTable state) {
        SystemRecord record = findRecord(modId, systemId);
        if (record == null || state == null) return false;
        record.state(state);
        return true;
    }

    public boolean markDirty(String modId, String systemId) {
        SystemRecord record = findRecord(modId, systemId);
        if (record == null) return false;
        record.dirty(true);
        return true;
    }

    public void tickPhase(String phase, long tick, long timestampMs) {
        String normalizedPhase = normalize(phase, "tick");
        for (LuaMod mod : manager.listMods()) {
            if (mod.state() != LuaModState.ENABLED || mod.ctx() == null) continue;
            LuaModContext ctx = mod.ctx();
            Map<String, SystemRecord> systems = systemsByMod.get(normalize(mod.manifest().id()));
            if (systems == null || systems.isEmpty()) continue;
            ArrayList<SystemRecord> ordered = new ArrayList<>(systems.values());
            ordered.sort((a, b) -> a.systemId().compareToIgnoreCase(b.systemId()));
            for (SystemRecord record : ordered) {
                if (!normalizedPhase.equals(record.phase())) continue;
                tickSystem(ctx, mod, record, tick, timestampMs);
            }
        }
    }

    private void tickSystem(LuaModContext ctx, LuaMod mod, SystemRecord record, long tick, long timestampMs) {
        boolean dirtyNow = record.dirty();
        if (dirtyNow && record.onDirty() != null) {
            invokeSystemHandler(ctx, mod, record, record.onDirty(), "dirty", 1, tick, timestampMs, true);
        }
        if (dirtyNow) record.dirty(false);
        if (record.onStep() == null) return;
        if (tick % record.updateEveryTicks() != 0L) return;
        long elapsedTicks = record.lastStepTick() <= 0L ? record.updateEveryTicks() : Math.max(record.updateEveryTicks(), tick - record.lastStepTick());
        int requestedSteps = (int) Math.max(1L, elapsedTicks / record.updateEveryTicks());
        int steps = Math.min(record.maxCatchUpSteps(), requestedSteps);
        for (int stepIndex = 1; stepIndex <= steps; stepIndex++) {
            invokeSystemHandler(ctx, mod, record, record.onStep(), "step", stepIndex, tick, timestampMs, dirtyNow);
        }
        record.lastStepTick(tick);
    }

    private void invokeSystemHandler(LuaModContext ctx, LuaMod mod, SystemRecord record, LuaFunction fn, String reason, int stepIndex, long tick, long timestampMs, boolean dirtyEvent) {
        LuaTable payload = new LuaTable();
        payload.set("systemId", LuaValue.valueOf(record.systemId()));
        payload.set("kind", LuaValue.valueOf(record.kind()));
        payload.set("phase", LuaValue.valueOf(record.phase()));
        payload.set("tags", LuaEngine.javaToLuaValue(new ArrayList<>(record.tags())));
        payload.set("tick", LuaValue.valueOf(tick));
        payload.set("timestampMs", LuaValue.valueOf(timestampMs));
        payload.set("stepIndex", LuaValue.valueOf(stepIndex));
        payload.set("reason", LuaValue.valueOf(reason));
        payload.set("dirty", LuaValue.valueOf(dirtyEvent));
        payload.set("state", record.state());
        long start = System.nanoTime();
        boolean failed = false;
        try {
            Varargs result = fn.invoke(payload);
            LuaValue first = result == null ? LuaValue.NIL : result.arg1();
            if (first != null && first.istable()) {
                record.state((LuaTable) first);
            }
        } catch (Throwable t) {
            failed = true;
            manager.recordRuntimeError(mod, "Simulation system error (" + record.systemId() + ")", t);
            if (manager.isDebugLogging()) {
                ctx.plugin().getLogger().at(Level.WARNING).log(
                        "Simulation handler failed for mod=" + mod.manifest().id()
                                + " systemId=" + record.systemId()
                                + " reason=" + reason
                                + " error=" + t
                );
            }
        } finally {
            manager.recordRuntimeInvocation(mod, "sim:" + reason + ":" + record.systemId(), start, failed);
        }
    }

    private SystemRecord findRecord(String modId, String systemId) {
        Map<String, SystemRecord> systems = systemsByMod.get(normalize(modId));
        if (systems == null) return null;
        return systems.get(normalize(systemId));
    }

    private static LinkedHashMap<String, Object> toMap(SystemRecord record) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("systemId", record.systemId());
        out.put("kind", record.kind());
        out.put("phase", record.phase());
        out.put("updateEveryTicks", record.updateEveryTicks());
        out.put("maxCatchUpSteps", record.maxCatchUpSteps());
        out.put("tags", new ArrayList<>(record.tags()));
        out.put("dirty", record.dirty());
        out.put("lastStepTick", record.lastStepTick());
        out.put("state", record.state());
        return out;
    }

    private static String nextSystemId(LuaTable options) {
        if (options != null) {
            String explicit = normalize(options.get("id").optjstring(null));
            if (explicit != null) return explicit;
        }
        return normalize("system-" + UUID.randomUUID());
    }

    private static String normalize(String value) {
        return normalize(value, null);
    }

    private static String normalize(String value, String fallback) {
        if (value == null) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static LuaFunction asFunction(LuaValue value) {
        return value != null && value.isfunction() ? (LuaFunction) value : null;
    }

    private static Set<String> readTags(LuaValue value) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (value == null || value.isnil()) return out;
        if (value.isstring()) {
            String single = normalize(value.tojstring());
            if (single != null) out.add(single);
            return out;
        }
        if (!value.istable()) return out;
        LuaTable table = (LuaTable) value;
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            key = next.arg1();
            if (key.isnil()) break;
            LuaValue current = next.arg(2);
            if (current.isstring()) {
                String tag = normalize(current.tojstring());
                if (tag != null) out.add(tag);
            }
        }
        return out;
    }

    private static final class SystemRecord {
        private final String systemId;
        private final String modId;
        private final String kind;
        private final String phase;
        private final int updateEveryTicks;
        private final int maxCatchUpSteps;
        private final Set<String> tags;
        private LuaTable state;
        private final LuaFunction onStep;
        private final LuaFunction onDirty;
        private boolean dirty;
        private long lastStepTick;

        private SystemRecord(String systemId, String modId, String kind, String phase, int updateEveryTicks, int maxCatchUpSteps, Set<String> tags, LuaTable state, LuaFunction onStep, LuaFunction onDirty, boolean dirty, long lastStepTick) {
            this.systemId = systemId;
            this.modId = modId;
            this.kind = kind;
            this.phase = phase;
            this.updateEveryTicks = updateEveryTicks;
            this.maxCatchUpSteps = maxCatchUpSteps;
            this.tags = tags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tags);
            this.state = state == null ? new LuaTable() : state;
            this.onStep = onStep;
            this.onDirty = onDirty;
            this.dirty = dirty;
            this.lastStepTick = lastStepTick;
        }

        private String systemId() { return systemId; }
        private String modId() { return modId; }
        private String kind() { return kind; }
        private String phase() { return phase; }
        private int updateEveryTicks() { return updateEveryTicks; }
        private int maxCatchUpSteps() { return maxCatchUpSteps; }
        private Set<String> tags() { return tags; }
        private LuaTable state() { return state; }
        private void state(LuaTable next) { this.state = next == null ? new LuaTable() : next; }
        private LuaFunction onStep() { return onStep; }
        private LuaFunction onDirty() { return onDirty; }
        private boolean dirty() { return dirty; }
        private void dirty(boolean next) { this.dirty = next; }
        private long lastStepTick() { return lastStepTick; }
        private void lastStepTick(long next) { this.lastStepTick = next; }
    }
}
