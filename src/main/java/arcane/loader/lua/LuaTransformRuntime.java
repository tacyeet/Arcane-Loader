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

public final class LuaTransformRuntime {
    private final LuaModManager manager;
    private final Map<String, Map<String, TransformRecord>> transformsByMod = new ConcurrentHashMap<>();

    public LuaTransformRuntime(LuaModManager manager) {
        this.manager = manager;
    }

    public Map<String, Object> create(LuaModContext ctx, LuaTable options) {
        if (ctx == null) return null;
        String transformId = nextId(options);
        String modId = normalize(ctx.modId());
        TransformRecord record = new TransformRecord(
                transformId,
                modId,
                normalize(options == null ? null : options.get("kind").optjstring("transform"), "transform"),
                readTags(options == null ? null : options.get("tags")),
                normalize(options == null ? null : options.get("parentId").optjstring(null), null),
                options != null && options.get("state").istable() ? (LuaTable) options.get("state") : new LuaTable(),
                readVec(options == null ? LuaValue.NIL : options.get("position"), new double[] {0.0, 0.0, 0.0}),
                readVec(options == null ? LuaValue.NIL : options.get("rotation"), new double[] {0.0, 0.0, 0.0}),
                readVec(options == null ? LuaValue.NIL : options.get("scale"), new double[] {1.0, 1.0, 1.0}),
                normalize(options == null ? null : options.get("worldUuid").optjstring(null), ""),
                normalize(options == null ? null : options.get("actorId").optjstring(null), null),
                options != null && options.get("entity").isuserdata() ? options.get("entity").touserdata() : null,
                asFunction(options == null ? LuaValue.NIL : options.get("onResolve"))
        );
        transformsByMod.computeIfAbsent(modId, ignored -> new ConcurrentHashMap<>()).put(transformId, record);
        return toMap(ctx, record);
    }

    public boolean remove(String modId, String transformId) {
        Map<String, TransformRecord> transforms = transformsByMod.get(normalize(modId));
        if (transforms == null) return false;
        TransformRecord removed = transforms.remove(normalize(transformId));
        if (transforms.isEmpty()) transformsByMod.remove(normalize(modId), transforms);
        return removed != null;
    }

    public int clear(String modId) {
        Map<String, TransformRecord> removed = transformsByMod.remove(normalize(modId));
        return removed == null ? 0 : removed.size();
    }

    public Map<String, Object> find(LuaModContext ctx, String transformId) {
        TransformRecord record = findRecord(ctx == null ? null : ctx.modId(), transformId);
        return record == null ? null : toMap(ctx, record);
    }

    public List<Map<String, Object>> list(LuaModContext ctx) {
        if (ctx == null) return List.of();
        Map<String, TransformRecord> transforms = transformsByMod.get(normalize(ctx.modId()));
        if (transforms == null || transforms.isEmpty()) return List.of();
        ArrayList<TransformRecord> ordered = new ArrayList<>(transforms.values());
        ordered.sort((a, b) -> a.transformId().compareToIgnoreCase(b.transformId()));
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (TransformRecord record : ordered) out.add(Collections.unmodifiableMap(toMap(ctx, record)));
        return Collections.unmodifiableList(out);
    }

    public LuaTable state(String modId, String transformId) {
        TransformRecord record = findRecord(modId, transformId);
        return record == null ? null : record.state();
    }

    public boolean setState(String modId, String transformId, LuaTable state) {
        TransformRecord record = findRecord(modId, transformId);
        if (record == null || state == null) return false;
        record.state(state);
        return true;
    }

    public boolean setParent(String modId, String transformId, String parentId) {
        TransformRecord record = findRecord(modId, transformId);
        if (record == null) return false;
        record.parentId(normalize(parentId, null));
        return true;
    }

    public boolean setPosition(String modId, String transformId, double x, double y, double z) {
        TransformRecord record = findRecord(modId, transformId);
        if (record == null) return false;
        record.localPosition(new double[] { x, y, z });
        return true;
    }

    public boolean setRotation(String modId, String transformId, double yaw, double pitch, double roll) {
        TransformRecord record = findRecord(modId, transformId);
        if (record == null) return false;
        record.localRotation(new double[] { yaw, pitch, roll });
        return true;
    }

    public boolean bindActor(String modId, String transformId, String actorId) {
        TransformRecord record = findRecord(modId, transformId);
        if (record == null) return false;
        record.actorId(normalize(actorId, null));
        return true;
    }

    public boolean bindEntity(String modId, String transformId, Object entity) {
        TransformRecord record = findRecord(modId, transformId);
        if (record == null) return false;
        record.entity(entity);
        return true;
    }

    public Map<String, Object> resolve(LuaModContext ctx, String transformId) {
        TransformRecord record = findRecord(ctx == null ? null : ctx.modId(), transformId);
        if (ctx == null || record == null) return null;
        return resolveMap(ctx, record, new LinkedHashSet<>());
    }

    public void tick(long tick, long timestampMs) {
        for (LuaMod mod : manager.listMods()) {
            if (mod.state() != LuaModState.ENABLED || mod.ctx() == null) continue;
            LuaModContext ctx = mod.ctx();
            Map<String, TransformRecord> transforms = transformsByMod.get(normalize(mod.manifest().id()));
            if (transforms == null || transforms.isEmpty()) continue;
            ArrayList<TransformRecord> ordered = new ArrayList<>(transforms.values());
            ordered.sort((a, b) -> a.transformId().compareToIgnoreCase(b.transformId()));
            for (TransformRecord record : ordered) {
                tickTransform(ctx, mod, record, tick, timestampMs);
            }
        }
    }

    private void tickTransform(LuaModContext ctx, LuaMod mod, TransformRecord record, long tick, long timestampMs) {
        Map<String, Object> resolved = resolveMap(ctx, record, new LinkedHashSet<>());
        if (resolved == null) return;
        Object entity = targetEntity(ctx, record);
        if (entity != null) {
            Object worldValue = resolved.get("worldUuid");
            Object posValue = resolved.get("position");
            Object rotValue = resolved.get("rotation");
            String worldUuid = worldValue == null ? null : String.valueOf(worldValue);
            if (posValue instanceof Map<?, ?> pos) {
                double x = asDouble(pos.get("x"));
                double y = asDouble(pos.get("y"));
                double z = asDouble(pos.get("z"));
                ctx.teleportEntity(entity, worldUuid, x, y, z);
            }
            if (rotValue instanceof Map<?, ?> rot) {
                ctx.setEntityRotation(entity, asDouble(rot.get("x")), asDouble(rot.get("y")), asDouble(rot.get("z")));
            }
        }

        LuaFunction onResolve = record.onResolve();
        if (onResolve == null) return;
        LuaTable payload = new LuaTable();
        payload.set("transformId", LuaValue.valueOf(record.transformId()));
        payload.set("tick", LuaValue.valueOf(tick));
        payload.set("timestampMs", LuaValue.valueOf(timestampMs));
        payload.set("resolved", LuaEngine.javaToLuaValue(resolved));
        payload.set("state", record.state());
        long start = System.nanoTime();
        boolean failed = false;
        try {
            Varargs result = onResolve.invoke(payload);
            LuaValue first = result == null ? LuaValue.NIL : result.arg1();
            if (first != null && first.istable()) record.state((LuaTable) first);
        } catch (Throwable t) {
            failed = true;
            manager.recordRuntimeError(mod, "Transform resolve error (" + record.transformId() + ")", t);
            if (manager.isDebugLogging()) {
                ctx.plugin().getLogger().at(Level.WARNING).log(
                        "Transform resolve failed for mod=" + mod.manifest().id()
                                + " transformId=" + record.transformId()
                                + " error=" + t
                );
            }
        } finally {
            manager.recordRuntimeInvocation(mod, "transforms:resolve:" + record.transformId(), start, failed);
        }
    }

    private Map<String, Object> resolveMap(LuaModContext ctx, TransformRecord record, Set<String> visited) {
        if (ctx == null || record == null) return null;
        if (!visited.add(record.transformId())) return null;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        String worldUuid = record.worldUuid();
        double[] position = record.localPosition().clone();
        double[] rotation = record.localRotation().clone();

        if (record.parentId() != null) {
            TransformRecord parent = findRecord(record.modId(), record.parentId());
            if (parent != null && !record.transformId().equals(parent.transformId())) {
                Map<String, Object> parentResolved = resolveMap(ctx, parent, visited);
                if (parentResolved != null) {
                    worldUuid = String.valueOf(parentResolved.getOrDefault("worldUuid", worldUuid));
                    Object parentPos = parentResolved.get("position");
                    if (parentPos instanceof Map<?, ?> pos) {
                        position[0] += asDouble(pos.get("x"));
                        position[1] += asDouble(pos.get("y"));
                        position[2] += asDouble(pos.get("z"));
                    }
                    Object parentRot = parentResolved.get("rotation");
                    if (parentRot instanceof Map<?, ?> rot) {
                        rotation[0] += asDouble(rot.get("x"));
                        rotation[1] += asDouble(rot.get("y"));
                        rotation[2] += asDouble(rot.get("z"));
                    }
                }
            }
        }

        Object anchor = targetEntity(ctx, record);
        if (anchor != null) {
            String anchorWorld = ctx.entityWorldUuid(anchor);
            double[] anchorPos = ctx.entityPosition(anchor);
            double[] anchorRot = ctx.entityRotation(anchor);
            if (anchorWorld != null && !anchorWorld.isBlank()) worldUuid = anchorWorld;
            if (anchorPos != null && anchorPos.length >= 3) {
                position[0] += anchorPos[0];
                position[1] += anchorPos[1];
                position[2] += anchorPos[2];
            }
            if (anchorRot != null && anchorRot.length >= 3) {
                rotation[0] += anchorRot[0];
                rotation[1] += anchorRot[1];
                rotation[2] += anchorRot[2];
            }
        }

        out.put("transformId", record.transformId());
        out.put("kind", record.kind());
        out.put("worldUuid", worldUuid == null ? "" : worldUuid);
        out.put("position", Map.of("x", position[0], "y", position[1], "z", position[2]));
        out.put("rotation", Map.of("x", rotation[0], "y", rotation[1], "z", rotation[2]));
        out.put("scale", Map.of("x", record.localScale()[0], "y", record.localScale()[1], "z", record.localScale()[2]));
        out.put("parentId", record.parentId());
        out.put("actorId", record.actorId());
        out.put("entityId", anchor == null ? null : ctx.entityId(anchor));
        out.put("tags", new ArrayList<>(record.tags()));
        out.put("state", record.state());
        visited.remove(record.transformId());
        return out;
    }

    private Object targetEntity(LuaModContext ctx, TransformRecord record) {
        if (ctx == null || record == null) return null;
        if (record.entity() != null && ctx.isValidEntity(record.entity())) return record.entity();
        if (record.actorId() != null) {
            Object entity = ctx.manager().actors().entity(record.modId(), record.actorId());
            if (entity != null && ctx.isValidEntity(entity)) return entity;
        }
        return null;
    }

    private TransformRecord findRecord(String modId, String transformId) {
        Map<String, TransformRecord> transforms = transformsByMod.get(normalize(modId));
        if (transforms == null) return null;
        return transforms.get(normalize(transformId));
    }

    private static LinkedHashMap<String, Object> toMap(LuaModContext ctx, TransformRecord record) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("transformId", record.transformId());
        out.put("kind", record.kind());
        out.put("tags", new ArrayList<>(record.tags()));
        out.put("parentId", record.parentId());
        out.put("worldUuid", record.worldUuid());
        out.put("actorId", record.actorId());
        out.put("entityId", ctx == null || record.entity() == null ? null : ctx.entityId(record.entity()));
        out.put("position", Map.of("x", record.localPosition()[0], "y", record.localPosition()[1], "z", record.localPosition()[2]));
        out.put("rotation", Map.of("x", record.localRotation()[0], "y", record.localRotation()[1], "z", record.localRotation()[2]));
        out.put("scale", Map.of("x", record.localScale()[0], "y", record.localScale()[1], "z", record.localScale()[2]));
        out.put("state", record.state());
        return out;
    }

    private static String nextId(LuaTable options) {
        if (options != null) {
            String explicit = normalize(options.get("id").optjstring(null), null);
            if (explicit != null) return explicit;
        }
        return normalize("transform-" + UUID.randomUUID(), null);
    }

    private static String normalize(String value) {
        return normalize(value, null);
    }

    private static String normalize(String value, String fallback) {
        if (value == null) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? fallback : normalized;
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

    private static double[] readVec(LuaValue value, double[] fallback) {
        if (value != null && value.istable()) {
            double[] vec = new double[3];
            LuaValue x = value.get("x");
            LuaValue y = value.get("y");
            LuaValue z = value.get("z");
            if (x.isnumber() && y.isnumber() && z.isnumber()) {
                vec[0] = x.todouble();
                vec[1] = y.todouble();
                vec[2] = z.todouble();
                return vec;
            }
        }
        return fallback.clone();
    }

    private static LuaFunction asFunction(LuaValue value) {
        return value != null && value.isfunction() ? (LuaFunction) value : null;
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static final class TransformRecord {
        private final String transformId;
        private final String modId;
        private final String kind;
        private final Set<String> tags;
        private String parentId;
        private LuaTable state;
        private double[] localPosition;
        private double[] localRotation;
        private final double[] localScale;
        private final String worldUuid;
        private String actorId;
        private Object entity;
        private final LuaFunction onResolve;

        private TransformRecord(String transformId, String modId, String kind, Set<String> tags, String parentId, LuaTable state, double[] localPosition, double[] localRotation, double[] localScale, String worldUuid, String actorId, Object entity, LuaFunction onResolve) {
            this.transformId = transformId;
            this.modId = modId;
            this.kind = kind;
            this.tags = tags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tags);
            this.parentId = parentId;
            this.state = state == null ? new LuaTable() : state;
            this.localPosition = localPosition == null ? new double[] {0.0, 0.0, 0.0} : localPosition;
            this.localRotation = localRotation == null ? new double[] {0.0, 0.0, 0.0} : localRotation;
            this.localScale = localScale == null ? new double[] {1.0, 1.0, 1.0} : localScale;
            this.worldUuid = worldUuid == null ? "" : worldUuid;
            this.actorId = actorId;
            this.entity = entity;
            this.onResolve = onResolve;
        }

        private String transformId() { return transformId; }
        private String modId() { return modId; }
        private String kind() { return kind; }
        private Set<String> tags() { return tags; }
        private String parentId() { return parentId; }
        private void parentId(String next) { this.parentId = next; }
        private LuaTable state() { return state; }
        private void state(LuaTable next) { this.state = next == null ? new LuaTable() : next; }
        private double[] localPosition() { return localPosition; }
        private void localPosition(double[] next) { this.localPosition = next == null ? new double[] {0.0, 0.0, 0.0} : next; }
        private double[] localRotation() { return localRotation; }
        private void localRotation(double[] next) { this.localRotation = next == null ? new double[] {0.0, 0.0, 0.0} : next; }
        private double[] localScale() { return localScale; }
        private String worldUuid() { return worldUuid; }
        private String actorId() { return actorId; }
        private void actorId(String next) { this.actorId = next; }
        private Object entity() { return entity; }
        private void entity(Object next) { this.entity = next; }
        private LuaFunction onResolve() { return onResolve; }
    }
}
