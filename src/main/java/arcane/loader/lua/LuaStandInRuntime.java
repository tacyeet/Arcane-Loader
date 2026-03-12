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

public final class LuaStandInRuntime {
    private final LuaModManager manager;
    private final Map<String, Map<String, StandInRecord>> standinsByMod = new ConcurrentHashMap<>();

    public LuaStandInRuntime(LuaModManager manager) {
        this.manager = manager;
    }

    public Map<String, Object> create(LuaModContext ctx, LuaTable options) {
        if (ctx == null) return null;
        LuaTable safeOptions = options == null ? new LuaTable() : options;
        String modId = normalize(ctx.modId());
        String standinId = nextStandInId(safeOptions);
        String kind = normalizeKind(safeOptions.get("kind").optjstring("standin"));
        Set<String> tags = readTags(safeOptions.get("tags"));
        LuaTable state = safeOptions.get("state").istable() ? (LuaTable) safeOptions.get("state") : new LuaTable();
        int updateEveryTicks = Math.max(1, safeOptions.get("updateEveryTicks").optint(1));
        LuaFunction onTick = asFunction(safeOptions.get("onTick"));
        LuaFunction onRemove = asFunction(safeOptions.get("onRemove"));

        String actorId = createActorComponent(ctx, safeOptions);
        String transformId = createTransformComponent(ctx, safeOptions, actorId);
        String volumeId = createVolumeComponent(ctx, safeOptions);
        String nodeId = createNodeComponent(ctx, safeOptions);

        LuaValue sync = safeOptions.get("sync");
        boolean syncTransformToVolume = volumeId != null && transformId != null && (!sync.istable() || sync.get("transformToVolume").optboolean(true));
        boolean syncTransformToNode = nodeId != null && transformId != null && (!sync.istable() || sync.get("transformToNode").optboolean(true));

        StandInRecord record = new StandInRecord(
                standinId,
                modId,
                kind == null ? "standin" : kind,
                tags,
                state,
                actorId,
                transformId,
                volumeId,
                nodeId,
                updateEveryTicks,
                syncTransformToVolume,
                syncTransformToNode,
                onTick,
                onRemove,
                System.currentTimeMillis(),
                0L
        );
        standinsByMod.computeIfAbsent(modId, ignored -> new ConcurrentHashMap<>()).put(standinId, record);
        return toMap(ctx, record);
    }

    public Map<String, Object> spawn(LuaModContext ctx, Object typeOrPrototype, LuaTable options) {
        LuaTable safeOptions = options == null ? new LuaTable() : options;
        LuaTable actor = safeOptions.get("actor").istable() ? (LuaTable) safeOptions.get("actor") : new LuaTable();
        actor.set("type", LuaEngine.javaToLuaValue(typeOrPrototype));
        safeOptions.set("actor", actor);
        return create(ctx, safeOptions);
    }

    public Map<String, Object> find(LuaModContext ctx, String standinId) {
        StandInRecord record = findRecord(ctx == null ? null : ctx.modId(), standinId);
        return ctx == null || record == null ? null : toMap(ctx, record);
    }

    public List<Map<String, Object>> list(LuaModContext ctx) {
        if (ctx == null) return List.of();
        Map<String, StandInRecord> standins = standinsByMod.get(normalize(ctx.modId()));
        if (standins == null || standins.isEmpty()) return List.of();
        ArrayList<StandInRecord> ordered = new ArrayList<>(standins.values());
        ordered.sort((a, b) -> a.standinId().compareToIgnoreCase(b.standinId()));
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (StandInRecord record : ordered) out.add(Collections.unmodifiableMap(toMap(ctx, record)));
        return Collections.unmodifiableList(out);
    }

    public List<Map<String, Object>> listByKind(LuaModContext ctx, String kind) {
        if (ctx == null) return List.of();
        String normalized = normalizeKind(kind);
        if (normalized == null) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : list(ctx)) {
            if (normalized.equals(normalizeKind(String.valueOf(row.get("kind"))))) out.add(row);
        }
        return Collections.unmodifiableList(out);
    }

    public List<Map<String, Object>> listByTag(LuaModContext ctx, String tag) {
        if (ctx == null) return List.of();
        String normalized = normalizeKind(tag);
        if (normalized == null) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : list(ctx)) {
            Object tags = row.get("tags");
            if (!(tags instanceof List<?> list)) continue;
            for (Object value : list) {
                if (value != null && normalized.equals(normalizeKind(String.valueOf(value)))) {
                    out.add(row);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    public LuaTable state(String modId, String standinId) {
        StandInRecord record = findRecord(modId, standinId);
        return record == null ? null : record.state();
    }

    public int count(LuaModContext ctx) {
        return list(ctx).size();
    }

    public boolean setState(String modId, String standinId, LuaTable state) {
        StandInRecord record = findRecord(modId, standinId);
        if (record == null || state == null) return false;
        record.state(state);
        return true;
    }

    public Map<String, Object> actor(LuaModContext ctx, String standinId) {
        StandInRecord record = findRecord(ctx == null ? null : ctx.modId(), standinId);
        return ctx == null || record == null || record.actorId() == null ? null : manager.actors().find(ctx, record.actorId());
    }

    public Map<String, Object> transform(LuaModContext ctx, String standinId) {
        StandInRecord record = findRecord(ctx == null ? null : ctx.modId(), standinId);
        return ctx == null || record == null || record.transformId() == null ? null : manager.transforms().find(ctx, record.transformId());
    }

    public Map<String, Object> volume(LuaModContext ctx, String standinId) {
        StandInRecord record = findRecord(ctx == null ? null : ctx.modId(), standinId);
        return ctx == null || record == null || record.volumeId() == null ? null : manager.volumes().find(ctx, record.volumeId());
    }

    public Map<String, Object> node(LuaModContext ctx, String standinId) {
        StandInRecord record = findRecord(ctx == null ? null : ctx.modId(), standinId);
        return ctx == null || record == null || record.nodeId() == null ? null : manager.mechanics().find(ctx, record.nodeId());
    }

    public boolean hasComponent(String modId, String standinId, String component) {
        StandInRecord record = findRecord(modId, standinId);
        if (record == null || component == null) return false;
        return switch (component.trim().toLowerCase(Locale.ROOT)) {
            case "actor" -> record.actorId() != null;
            case "transform" -> record.transformId() != null;
            case "volume" -> record.volumeId() != null;
            case "node", "mechanics" -> record.nodeId() != null;
            default -> false;
        };
    }

    public boolean move(LuaModContext ctx, String standinId, double x, double y, double z) {
        StandInRecord record = findRecord(ctx == null ? null : ctx.modId(), standinId);
        if (ctx == null || record == null) return false;
        if (record.transformId() != null) {
            return manager.transforms().setPosition(ctx.modId(), record.transformId(), x, y, z);
        }
        if (record.actorId() != null) {
            Object entity = manager.actors().entity(ctx.modId(), record.actorId());
            return entity != null && ctx.teleportEntity(entity, null, x, y, z);
        }
        return false;
    }

    public boolean rotate(LuaModContext ctx, String standinId, double yaw, double pitch, double roll) {
        StandInRecord record = findRecord(ctx == null ? null : ctx.modId(), standinId);
        if (ctx == null || record == null) return false;
        if (record.transformId() != null) {
            return manager.transforms().setRotation(ctx.modId(), record.transformId(), yaw, pitch, roll);
        }
        if (record.actorId() != null) {
            Object entity = manager.actors().entity(ctx.modId(), record.actorId());
            return entity != null && ctx.setEntityRotation(entity, yaw, pitch, roll);
        }
        return false;
    }


    public boolean attach(String modId, String standinId, String parentStandinId) {
        StandInRecord record = findRecord(modId, standinId);
        StandInRecord parent = findRecord(modId, parentStandinId);
        if (record == null || parent == null || record.transformId() == null || parent.transformId() == null) return false;
        return manager.transforms().setParent(modId, record.transformId(), parent.transformId());
    }

    public Map<String, Object> resolve(LuaModContext ctx, String standinId) {
        StandInRecord record = findRecord(ctx == null ? null : ctx.modId(), standinId);
        return ctx == null || record == null ? null : resolvedMap(ctx, record);
    }

    public boolean remove(LuaModContext ctx, String standinId, String reason) {
        if (ctx == null) return false;
        String modId = normalize(ctx.modId());
        String id = normalize(standinId);
        if (modId == null || id == null) return false;
        Map<String, StandInRecord> standins = standinsByMod.get(modId);
        if (standins == null) return false;
        StandInRecord record = standins.remove(id);
        if (standins.isEmpty()) standinsByMod.remove(modId, standins);
        if (record == null) return false;
        invokeOnRemove(ctx, record, reason == null ? "removed" : reason);
        cleanupComponents(ctx, record);
        return true;
    }

    public int clear(String modId) {
        String normalized = normalize(modId);
        Map<String, StandInRecord> removed = standinsByMod.remove(normalized);
        if (removed == null || removed.isEmpty()) return 0;
        LuaMod mod = manager.findById(modId);
        LuaModContext ctx = mod == null ? null : mod.ctx();
        int count = 0;
        if (ctx != null) {
            for (StandInRecord record : removed.values()) {
                invokeOnRemove(ctx, record, "cleanup");
                cleanupComponents(ctx, record);
                count++;
            }
        } else {
            count = removed.size();
        }
        return count;
    }

    public void tick(long tick, long timestampMs) {
        for (LuaMod mod : manager.listMods()) {
            if (mod.state() != LuaModState.ENABLED || mod.ctx() == null) continue;
            LuaModContext ctx = mod.ctx();
            Map<String, StandInRecord> standins = standinsByMod.get(normalize(mod.manifest().id()));
            if (standins == null || standins.isEmpty()) continue;
            ArrayList<StandInRecord> ordered = new ArrayList<>(standins.values());
            ordered.sort((a, b) -> a.standinId().compareToIgnoreCase(b.standinId()));
            for (StandInRecord record : ordered) {
                StandInRecord current = findRecord(mod.manifest().id(), record.standinId());
                if (current == null) continue;
                if (tick % current.updateEveryTicks() != 0L) continue;
                tickStandIn(ctx, mod, current, tick, timestampMs);
            }
        }
    }

    private void tickStandIn(LuaModContext ctx, LuaMod mod, StandInRecord record, long tick, long timestampMs) {
        Map<String, Object> resolved = resolvedMap(ctx, record);
        syncComponents(ctx, record, resolved);
        LuaFunction onTick = record.onTick();
        if (onTick == null) {
            record.lastTick(tick);
            return;
        }
        LuaTable payload = payload(ctx, record, resolved, tick, timestampMs);
        long start = System.nanoTime();
        boolean failed = false;
        try {
            Varargs result = onTick.invoke(payload);
            LuaValue first = result == null ? LuaValue.NIL : result.arg1();
            if (first != null && first.istable()) {
                record.state((LuaTable) first);
            }
            record.lastTick(tick);
        } catch (Throwable t) {
            failed = true;
            manager.recordRuntimeError(mod, "Stand-in tick error (" + record.standinId() + ")", t);
            if (manager.isDebugLogging()) {
                ctx.plugin().getLogger().at(Level.WARNING).log(
                        "Stand-in tick failed for mod=" + mod.manifest().id()
                                + " standinId=" + record.standinId()
                                + " error=" + t
                );
            }
        } finally {
            manager.recordRuntimeInvocation(mod, "standins:tick:" + record.standinId(), start, failed);
        }
    }

    private void syncComponents(LuaModContext ctx, StandInRecord record, Map<String, Object> resolved) {
        if (ctx == null || record == null || resolved == null) return;
        Object position = resolved.get("position");
        if (!(position instanceof Map<?, ?> pos)) return;
        double x = asDouble(pos.get("x"));
        double y = asDouble(pos.get("y"));
        double z = asDouble(pos.get("z"));
        if (record.syncTransformToVolume() && record.volumeId() != null) {
            manager.volumes().moveCenter(ctx.modId(), record.volumeId(), x, y, z);
        }
        if (record.syncTransformToNode() && record.nodeId() != null) {
            manager.mechanics().move(ctx.modId(), record.nodeId(), x, y, z);
        }
    }

    private void cleanupComponents(LuaModContext ctx, StandInRecord record) {
        if (ctx == null || record == null) return;
        if (record.transformId() != null) manager.transforms().remove(ctx.modId(), record.transformId());
        if (record.volumeId() != null) manager.volumes().remove(ctx, record.volumeId(), "standin_cleanup");
        if (record.nodeId() != null) manager.mechanics().unregister(ctx.modId(), record.nodeId());
        if (record.actorId() != null) manager.actors().remove(ctx, record.actorId(), false, "standin_cleanup");
    }

    private void invokeOnRemove(LuaModContext ctx, StandInRecord record, String reason) {
        LuaFunction onRemove = record.onRemove();
        if (ctx == null || record == null || onRemove == null) return;
        LuaMod mod = manager.findById(ctx.modId());
        if (mod == null) return;
        LuaTable payload = payload(ctx, record, resolvedMap(ctx, record), record.lastTick(), System.currentTimeMillis());
        payload.set("reason", LuaValue.valueOf(reason == null ? "removed" : reason));
        long start = System.nanoTime();
        boolean failed = false;
        try {
            onRemove.invoke(payload);
        } catch (Throwable t) {
            failed = true;
            manager.recordRuntimeError(mod, "Stand-in remove error (" + record.standinId() + ")", t);
        } finally {
            manager.recordRuntimeInvocation(mod, "standins:onRemove:" + record.standinId(), start, failed);
        }
    }

    private LuaTable payload(LuaModContext ctx, StandInRecord record, Map<String, Object> resolved, long tick, long timestampMs) {
        LuaTable payload = new LuaTable();
        payload.set("standinId", LuaValue.valueOf(record.standinId()));
        payload.set("kind", LuaValue.valueOf(record.kind()));
        payload.set("tags", LuaEngine.javaToLuaValue(new ArrayList<>(record.tags())));
        payload.set("state", record.state());
        payload.set("tick", LuaValue.valueOf(tick));
        payload.set("timestampMs", LuaValue.valueOf(timestampMs));
        payload.set("updateEveryTicks", LuaValue.valueOf(record.updateEveryTicks()));
        payload.set("syncTransformToVolume", LuaValue.valueOf(record.syncTransformToVolume()));
        payload.set("syncTransformToNode", LuaValue.valueOf(record.syncTransformToNode()));
        if (resolved != null) payload.set("resolved", LuaEngine.javaToLuaValue(resolved));
        if (record.actorId() != null) payload.set("actor", LuaEngine.javaToLuaValue(manager.actors().find(ctx, record.actorId())));
        if (record.transformId() != null) payload.set("transform", LuaEngine.javaToLuaValue(manager.transforms().find(ctx, record.transformId())));
        if (record.volumeId() != null) payload.set("volume", LuaEngine.javaToLuaValue(manager.volumes().find(ctx, record.volumeId())));
        if (record.nodeId() != null) payload.set("node", LuaEngine.javaToLuaValue(manager.mechanics().find(ctx, record.nodeId())));
        return payload;
    }

    private Map<String, Object> resolvedMap(LuaModContext ctx, StandInRecord record) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>(toMap(ctx, record));
        if (record.transformId() != null) {
            Map<String, Object> resolved = manager.transforms().resolve(ctx, record.transformId());
            if (resolved != null) out.put("resolved", resolved);
        }
        return out;
    }

    private String createActorComponent(LuaModContext ctx, LuaTable options) {
        LuaValue actorValue = options.get("actor");
        if (!actorValue.istable()) return null;
        LuaTable actor = (LuaTable) actorValue;
        LuaTable actorOptions = new LuaTable();
        copyCommonComponentOptions(options, actor, actorOptions);
        Object entity = actor.get("entity").isuserdata() ? actor.get("entity").touserdata() : null;
        Map<String, Object> created;
        if (entity != null) {
            created = manager.actors().bind(ctx, entity, actorOptions);
        } else {
            Object type = actor.get("type").isnil() ? null : LuaEngine.luaToJava(actor.get("type"));
            if (type == null) type = actor.get("prototype").isnil() ? null : LuaEngine.luaToJava(actor.get("prototype"));
            if (type == null) return null;
            String worldUuid = firstNonBlank(actor.get("worldUuid").optjstring(null), options.get("worldUuid").optjstring(null));
            double[] position = firstVec(actor.get("position"), options.get("position"), new double[] {0.0, 0.0, 0.0});
            double[] rotation = firstVec(actor.get("rotation"), options.get("rotation"), new double[] {0.0, 0.0, 0.0});
            created = manager.actors().spawn(ctx, type, worldUuid, position[0], position[1], position[2], rotation[0], rotation[1], rotation[2], actorOptions);
        }
        return created == null ? null : stringValue(created.get("actorId"));
    }

    private String createTransformComponent(LuaModContext ctx, LuaTable options, String actorId) {
        LuaValue transformValue = options.get("transform");
        boolean shouldCreate = transformValue.istable() || transformValue.optboolean(false) || actorId != null || options.get("position").istable();
        if (!shouldCreate) return null;
        LuaTable transformOptions = transformValue.istable() ? (LuaTable) transformValue : new LuaTable();
        if (transformOptions.get("position").isnil() && options.get("position").istable()) transformOptions.set("position", options.get("position"));
        if (transformOptions.get("rotation").isnil() && options.get("rotation").istable()) transformOptions.set("rotation", options.get("rotation"));
        if (transformOptions.get("worldUuid").isnil() && options.get("worldUuid").isstring()) transformOptions.set("worldUuid", options.get("worldUuid"));
        if (transformOptions.get("kind").isnil() && options.get("kind").isstring()) transformOptions.set("kind", options.get("kind"));
        if (transformOptions.get("tags").isnil() && options.get("tags").istable()) transformOptions.set("tags", options.get("tags"));
        if (actorId != null && transformOptions.get("actorId").isnil()) transformOptions.set("actorId", LuaValue.valueOf(actorId));
        Map<String, Object> created = manager.transforms().create(ctx, transformOptions);
        return created == null ? null : stringValue(created.get("transformId"));
    }

    private String createVolumeComponent(LuaModContext ctx, LuaTable options) {
        LuaValue volumeValue = options.get("volume");
        if (!volumeValue.istable()) return null;
        LuaTable volume = (LuaTable) volumeValue;
        String shape = volume.get("shape").optjstring("box").trim().toLowerCase(Locale.ROOT);
        String worldUuid = firstNonBlank(volume.get("worldUuid").optjstring(null), options.get("worldUuid").optjstring(null));
        Map<String, Object> created;
        if ("sphere".equals(shape)) {
            double[] center = firstVec(volume.get("center"), volume.get("position"), options.get("position"), new double[] {0.0, 0.0, 0.0});
            double radius = Math.max(0.1, volume.get("radius").optdouble(1.0));
            created = manager.volumes().addSphere(ctx, worldUuid, center[0], center[1], center[2], radius, volume);
        } else {
            double[] min = extractVec(volume.get("min"));
            double[] max = extractVec(volume.get("max"));
            if (min == null || max == null) {
                double[] center = firstVec(volume.get("center"), volume.get("position"), options.get("position"), new double[] {0.0, 0.0, 0.0});
                double[] size = firstVec(volume.get("size"), null, new double[] {1.0, 1.0, 1.0});
                min = new double[] { center[0] - size[0] * 0.5, center[1] - size[1] * 0.5, center[2] - size[2] * 0.5 };
                max = new double[] { center[0] + size[0] * 0.5, center[1] + size[1] * 0.5, center[2] + size[2] * 0.5 };
            }
            created = manager.volumes().addBox(ctx, worldUuid, min[0], min[1], min[2], max[0], max[1], max[2], volume);
        }
        return created == null ? null : stringValue(created.get("volumeId"));
    }

    private String createNodeComponent(LuaModContext ctx, LuaTable options) {
        LuaValue nodeValue = options.get("node");
        if (!nodeValue.istable()) return null;
        LuaTable node = (LuaTable) nodeValue;
        String worldUuid = firstNonBlank(node.get("worldUuid").optjstring(null), options.get("worldUuid").optjstring(null));
        double[] position = firstVec(node.get("position"), options.get("position"), new double[] {0.0, 0.0, 0.0});
        Map<String, Object> created = manager.mechanics().register(ctx, worldUuid, position[0], position[1], position[2], node);
        return created == null ? null : stringValue(created.get("nodeId"));
    }

    private StandInRecord findRecord(String modId, String standinId) {
        Map<String, StandInRecord> standins = standinsByMod.get(normalize(modId));
        if (standins == null) return null;
        return standins.get(normalize(standinId));
    }

    private static LinkedHashMap<String, Object> toMap(LuaModContext ctx, StandInRecord record) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("standinId", record.standinId());
        out.put("kind", record.kind());
        out.put("tags", new ArrayList<>(record.tags()));
        out.put("state", record.state());
        out.put("actorId", record.actorId());
        out.put("transformId", record.transformId());
        out.put("volumeId", record.volumeId());
        out.put("nodeId", record.nodeId());
        out.put("updateEveryTicks", record.updateEveryTicks());
        out.put("syncTransformToVolume", record.syncTransformToVolume());
        out.put("syncTransformToNode", record.syncTransformToNode());
        out.put("createdAtMs", record.createdAtMs());
        out.put("lastTick", record.lastTick());
        if (ctx != null) {
            out.put("hasActor", record.actorId() != null);
            out.put("hasTransform", record.transformId() != null);
            out.put("hasVolume", record.volumeId() != null);
            out.put("hasNode", record.nodeId() != null);
        }
        return out;
    }

    private static void copyCommonComponentOptions(LuaTable root, LuaTable component, LuaTable out) {
        if (component.get("id").isstring()) out.set("id", component.get("id"));
        if (component.get("kind").isstring()) out.set("kind", component.get("kind"));
        else if (root.get("kind").isstring()) out.set("kind", root.get("kind"));
        if (component.get("tags").istable()) out.set("tags", component.get("tags"));
        else if (root.get("tags").istable()) out.set("tags", root.get("tags"));
        if (component.get("state").istable()) out.set("state", component.get("state"));
        if (component.get("updateEveryTicks").isnumber()) out.set("updateEveryTicks", component.get("updateEveryTicks"));
        if (component.get("onTick").isfunction()) out.set("onTick", component.get("onTick"));
        if (component.get("onRemove").isfunction()) out.set("onRemove", component.get("onRemove"));
    }

    private static double[] extractVec(LuaValue value) {
        if (!value.istable()) return null;
        return new double[] {
                value.get("x").optdouble(value.get(1).optdouble(0.0)),
                value.get("y").optdouble(value.get(2).optdouble(0.0)),
                value.get("z").optdouble(value.get(3).optdouble(0.0))
        };
    }

    private static double[] firstVec(LuaValue first, LuaValue fallback, double[] defaultValue) {
        double[] value = extractVec(first);
        if (value != null) return value;
        value = extractVec(fallback);
        return value == null ? defaultValue : value;
    }

    private static double[] firstVec(LuaValue first, LuaValue second, LuaValue third, double[] defaultValue) {
        double[] value = extractVec(first);
        if (value != null) return value;
        value = extractVec(second);
        if (value != null) return value;
        value = extractVec(third);
        return value == null ? defaultValue : value;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeKind(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized;
    }

    private static String nextStandInId(LuaTable options) {
        if (options != null) {
            String explicit = normalize(options.get("id").optjstring(null));
            if (explicit != null) return explicit;
        }
        return normalize("standin-" + UUID.randomUUID());
    }

    private static LuaFunction asFunction(LuaValue value) {
        return value != null && value.isfunction() ? (LuaFunction) value : null;
    }

    private static Set<String> readTags(LuaValue value) {
        if (!value.istable()) return Collections.emptySet();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs n = ((LuaTable) value).next(k);
            k = n.arg1();
            if (k.isnil()) break;
            String tag = normalizeKind(String.valueOf(n.arg(2)));
            if (tag != null) out.add(tag);
        }
        return Collections.unmodifiableSet(out);
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static final class StandInRecord {
        private final String standinId;
        private final String modId;
        private final String kind;
        private final Set<String> tags;
        private LuaTable state;
        private final String actorId;
        private final String transformId;
        private final String volumeId;
        private final String nodeId;
        private final int updateEveryTicks;
        private final boolean syncTransformToVolume;
        private final boolean syncTransformToNode;
        private final LuaFunction onTick;
        private final LuaFunction onRemove;
        private final long createdAtMs;
        private long lastTick;

        private StandInRecord(
                String standinId,
                String modId,
                String kind,
                Set<String> tags,
                LuaTable state,
                String actorId,
                String transformId,
                String volumeId,
                String nodeId,
                int updateEveryTicks,
                boolean syncTransformToVolume,
                boolean syncTransformToNode,
                LuaFunction onTick,
                LuaFunction onRemove,
                long createdAtMs,
                long lastTick
        ) {
            this.standinId = standinId;
            this.modId = modId;
            this.kind = kind;
            this.tags = tags == null ? Collections.emptySet() : new LinkedHashSet<>(tags);
            this.state = state == null ? new LuaTable() : state;
            this.actorId = actorId;
            this.transformId = transformId;
            this.volumeId = volumeId;
            this.nodeId = nodeId;
            this.updateEveryTicks = updateEveryTicks;
            this.syncTransformToVolume = syncTransformToVolume;
            this.syncTransformToNode = syncTransformToNode;
            this.onTick = onTick;
            this.onRemove = onRemove;
            this.createdAtMs = createdAtMs;
            this.lastTick = lastTick;
        }

        private String standinId() { return standinId; }
        private String modId() { return modId; }
        private String kind() { return kind; }
        private Set<String> tags() { return tags; }
        private LuaTable state() { return state; }
        private void state(LuaTable next) { state = next == null ? new LuaTable() : next; }
        private String actorId() { return actorId; }
        private String transformId() { return transformId; }
        private String volumeId() { return volumeId; }
        private String nodeId() { return nodeId; }
        private int updateEveryTicks() { return updateEveryTicks; }
        private boolean syncTransformToVolume() { return syncTransformToVolume; }
        private boolean syncTransformToNode() { return syncTransformToNode; }
        private LuaFunction onTick() { return onTick; }
        private LuaFunction onRemove() { return onRemove; }
        private long createdAtMs() { return createdAtMs; }
        private long lastTick() { return lastTick; }
        private void lastTick(long next) { lastTick = next; }
    }
}
