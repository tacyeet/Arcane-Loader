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

public final class LuaActorRuntime {
    private final LuaModManager manager;
    private final Map<String, Map<String, ActorRecord>> actorsByMod = new ConcurrentHashMap<>();

    public LuaActorRuntime(LuaModManager manager) {
        this.manager = manager;
    }

    public Map<String, Object> spawn(
            LuaModContext ctx,
            Object typeOrPrototype,
            String worldUuid,
            double x,
            double y,
            double z,
            double yaw,
            double pitch,
            double roll,
            LuaTable options
    ) {
        if (ctx == null) return null;
        Object world = (worldUuid == null || worldUuid.isBlank()) ? ctx.defaultWorld() : ctx.findWorldByUuid(worldUuid);
        Object entity = ctx.spawnEntity(world, typeOrPrototype, x, y, z, yaw, pitch, roll);
        if (entity == null) return null;
        return bindInternal(ctx, entity, true, options, typeOrPrototype);
    }

    public Map<String, Object> bind(LuaModContext ctx, Object entity, LuaTable options) {
        if (ctx == null || entity == null) return null;
        return bindInternal(ctx, entity, false, options, entity);
    }

    public boolean exists(String modId, String actorId) {
        return findRecord(modId, actorId) != null;
    }

    public Map<String, Object> find(LuaModContext ctx, String actorId) {
        ActorRecord record = findRecord(ctx == null ? null : ctx.modId(), actorId);
        if (record == null || ctx == null) return null;
        return toMap(ctx, record);
    }

    public List<Map<String, Object>> list(LuaModContext ctx) {
        if (ctx == null) return List.of();
        Map<String, ActorRecord> actors = actorsByMod.get(normalizeModId(ctx.modId()));
        if (actors == null || actors.isEmpty()) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>(actors.keySet());
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        for (String id : ids) {
            ActorRecord record = actors.get(id);
            if (record == null) continue;
            out.add(Collections.unmodifiableMap(toMap(ctx, record)));
        }
        return Collections.unmodifiableList(out);
    }

    public List<Map<String, Object>> listByKind(LuaModContext ctx, String kind) {
        if (ctx == null) return List.of();
        String normalizedKind = normalizeKind(kind);
        if (normalizedKind == null) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : list(ctx)) {
            Object current = row.get("kind");
            if (current != null && normalizedKind.equals(normalizeKind(String.valueOf(current)))) {
                out.add(row);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public List<Map<String, Object>> listByTag(LuaModContext ctx, String tag) {
        if (ctx == null) return List.of();
        String normalizedTag = normalizeKind(tag);
        if (normalizedTag == null) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : list(ctx)) {
            Object tags = row.get("tags");
            if (!(tags instanceof List<?> list)) continue;
            for (Object tagValue : list) {
                if (tagValue != null && normalizedTag.equals(normalizeKind(String.valueOf(tagValue)))) {
                    out.add(row);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    public Map<String, Object> findByEntity(LuaModContext ctx, Object entity) {
        if (ctx == null || entity == null) return null;
        String entityId = ctx.entityId(entity);
        if (entityId == null) return null;
        Map<String, ActorRecord> actors = actorsByMod.get(normalizeModId(ctx.modId()));
        if (actors == null) return null;
        for (ActorRecord record : actors.values()) {
            String currentId = ctx.entityId(record.entity());
            if (currentId != null && currentId.equalsIgnoreCase(entityId)) {
                return toMap(ctx, record);
            }
        }
        return null;
    }

    public Object entity(String modId, String actorId) {
        ActorRecord record = findRecord(modId, actorId);
        return record == null ? null : record.entity();
    }

    public LuaTable state(String modId, String actorId) {
        ActorRecord record = findRecord(modId, actorId);
        return record == null ? null : record.state();
    }

    public boolean setState(String modId, String actorId, LuaTable state) {
        ActorRecord record = findRecord(modId, actorId);
        if (record == null || state == null) return false;
        record.state(state);
        return true;
    }

    public boolean setKind(String modId, String actorId, String kind) {
        ActorRecord record = findRecord(modId, actorId);
        String normalized = normalizeKind(kind);
        if (record == null || normalized == null) return false;
        record.kind(normalized);
        return true;
    }

    public boolean setTags(String modId, String actorId, LuaTable tags) {
        ActorRecord record = findRecord(modId, actorId);
        if (record == null || tags == null) return false;
        record.tags(readTags(tags));
        return true;
    }

    public boolean remove(LuaModContext ctx, String actorId, boolean despawnEntity, String reason) {
        if (ctx == null) return false;
        String normalizedModId = normalizeModId(ctx.modId());
        String normalizedActorId = normalizeActorId(actorId);
        if (normalizedModId == null || normalizedActorId == null) return false;
        Map<String, ActorRecord> actors = actorsByMod.get(normalizedModId);
        if (actors == null) return false;
        ActorRecord record = actors.remove(normalizedActorId);
        if (actors.isEmpty()) actorsByMod.remove(normalizedModId, actors);
        if (record == null) return false;
        invokeOnRemove(ctx, record, reason == null ? "removed" : reason);
        if ((despawnEntity || record.ownedEntity()) && ctx.isValidEntity(record.entity())) {
            ctx.removeEntity(record.entity());
        }
        return true;
    }

    public int clear(LuaModContext ctx) {
        if (ctx == null) return 0;
        String normalizedModId = normalizeModId(ctx.modId());
        if (normalizedModId == null) return 0;
        Map<String, ActorRecord> removed = actorsByMod.remove(normalizedModId);
        if (removed == null || removed.isEmpty()) return 0;
        int count = 0;
        for (ActorRecord record : removed.values()) {
            invokeOnRemove(ctx, record, "cleanup");
            if (record.ownedEntity() && ctx.isValidEntity(record.entity())) {
                ctx.removeEntity(record.entity());
            }
            count++;
        }
        return count;
    }

    public void tick(long tick, long timestampMs) {
        for (LuaMod mod : manager.listMods()) {
            if (mod.state() != LuaModState.ENABLED || mod.ctx() == null) continue;
            LuaModContext ctx = mod.ctx();
            Map<String, ActorRecord> actors = actorsByMod.get(normalizeModId(mod.manifest().id()));
            if (actors == null || actors.isEmpty()) continue;
            ArrayList<ActorRecord> snapshot = new ArrayList<>(actors.values());
            snapshot.sort((a, b) -> a.actorId().compareToIgnoreCase(b.actorId()));
            for (ActorRecord record : snapshot) {
                ActorRecord current = findRecord(mod.manifest().id(), record.actorId());
                if (current == null) continue;
                if (!ctx.isValidEntity(current.entity())) {
                    remove(ctx, current.actorId(), false, "entity_invalid");
                    continue;
                }
                LuaFunction onTick = current.onTick();
                if (onTick == null) continue;
                if (tick % current.updateEveryTicks() != 0L) continue;
                LuaTable payload = actorPayload(ctx, current, tick, timestampMs);
                long start = System.nanoTime();
                boolean failed = false;
                try {
                    Varargs result = onTick.invoke(payload);
                    LuaValue first = result == null ? LuaValue.NIL : result.arg1();
                    if (first != null && first.istable()) {
                        current.state((LuaTable) first);
                    }
                    current.lastTick(tick);
                } catch (Throwable t) {
                    failed = true;
                    manager.recordRuntimeError(mod, "Actor tick error (" + current.actorId() + ")", t);
                    if (manager.isDebugLogging()) {
                        ctx.plugin().getLogger().at(Level.WARNING).log(
                                "Actor tick failed for mod=" + mod.manifest().id()
                                        + " actorId=" + current.actorId()
                                        + " error=" + t
                        );
                    }
                } finally {
                    manager.recordRuntimeInvocation(mod, "actors:tick:" + current.actorId(), start, failed);
                }
            }
        }
    }

    private Map<String, Object> bindInternal(LuaModContext ctx, Object entity, boolean ownedEntity, LuaTable options, Object sourceType) {
        String actorId = nextActorId(ctx, options);
        if (actorId == null) return null;
        String worldUuid = ctx.entityWorldUuid(entity);
        LuaFunction onTick = options == null ? null : asFunction(options.get("onTick"));
        LuaFunction onRemove = options == null ? null : asFunction(options.get("onRemove"));
        LuaTable state = options != null && options.get("state").istable() ? (LuaTable) options.get("state") : new LuaTable();
        String type = sourceType == null ? ctx.entityType(entity) : String.valueOf(sourceType);
        String kind = options == null ? null : normalizeKind(options.get("kind").optjstring(null));
        if (kind == null) kind = normalizeKind(type);
        int updateEveryTicks = options == null ? 1 : Math.max(1, options.get("updateEveryTicks").optint(1));
        ActorRecord record = new ActorRecord(
                actorId,
                normalizeModId(ctx.modId()),
                entity,
                ownedEntity,
                kind == null ? "actor" : kind,
                type == null ? "" : type,
                worldUuid == null ? "" : worldUuid,
                readTags(options == null ? null : options.get("tags")),
                state,
                onTick,
                onRemove,
                System.currentTimeMillis(),
                0L,
                updateEveryTicks
        );
        Map<String, ActorRecord> byId = actorsByMod.computeIfAbsent(normalizeModId(ctx.modId()), ignored -> new ConcurrentHashMap<>());
        ActorRecord previous = byId.put(actorId, record);
        if (previous != null) {
            invokeOnRemove(ctx, previous, "replaced");
            if (previous.ownedEntity() && previous.entity() != null && previous.entity() != entity && ctx.isValidEntity(previous.entity())) {
                ctx.removeEntity(previous.entity());
            }
        }
        return toMap(ctx, record);
    }

    private void invokeOnRemove(LuaModContext ctx, ActorRecord record, String reason) {
        LuaFunction onRemove = record.onRemove();
        if (ctx == null || record == null || onRemove == null) return;
        LuaTable payload = actorPayload(ctx, record, record.lastTick(), System.currentTimeMillis());
        payload.set("reason", LuaValue.valueOf(reason == null ? "removed" : reason));
        LuaMod mod = manager.findById(ctx.modId());
        long start = System.nanoTime();
        boolean failed = false;
        try {
            onRemove.invoke(payload);
        } catch (Throwable t) {
            failed = true;
            if (mod != null) manager.recordRuntimeError(mod, "Actor remove error (" + record.actorId() + ")", t);
        } finally {
            if (mod != null) manager.recordRuntimeInvocation(mod, "actors:onRemove:" + record.actorId(), start, failed);
        }
    }

    private LuaTable actorPayload(LuaModContext ctx, ActorRecord record, long tick, long timestampMs) {
        LuaTable payload = new LuaTable();
        payload.set("actorId", LuaValue.valueOf(record.actorId()));
        payload.set("modId", LuaValue.valueOf(record.modId()));
        payload.set("ownedEntity", LuaValue.valueOf(record.ownedEntity()));
        payload.set("kind", LuaValue.valueOf(record.kind()));
        payload.set("tags", tagsValue(record.tags()));
        payload.set("createdAtMs", LuaValue.valueOf(record.createdAtMs()));
        payload.set("lastTick", LuaValue.valueOf(record.lastTick()));
        payload.set("updateEveryTicks", LuaValue.valueOf(record.updateEveryTicks()));
        payload.set("tick", LuaValue.valueOf(tick));
        payload.set("timestampMs", LuaValue.valueOf(timestampMs));
        payload.set("state", record.state());
        if (record.entity() != null) {
            payload.set("entity", LuaEngine.javaToLuaValue(record.entity()));
            String entityId = ctx.entityId(record.entity());
            if (entityId != null) payload.set("entityId", LuaValue.valueOf(entityId));
            String entityType = ctx.entityType(record.entity());
            if (entityType != null) payload.set("entityType", LuaValue.valueOf(entityType));
            String worldUuid = ctx.entityWorldUuid(record.entity());
            if (worldUuid != null) payload.set("worldUuid", LuaValue.valueOf(worldUuid));
            double[] pos = ctx.entityPosition(record.entity());
            if (pos != null && pos.length >= 3) {
                payload.set("position", vec3(pos[0], pos[1], pos[2]));
            }
            double[] vel = ctx.entityVelocity(record.entity());
            if (vel != null && vel.length >= 3) {
                payload.set("velocity", vec3(vel[0], vel[1], vel[2]));
            }
        }
        return payload;
    }

    private LinkedHashMap<String, Object> toMap(LuaModContext ctx, ActorRecord record) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("actorId", record.actorId());
        out.put("modId", record.modId());
        out.put("ownedEntity", record.ownedEntity());
        out.put("kind", record.kind());
        out.put("tags", new ArrayList<>(record.tags()));
        out.put("createdAtMs", record.createdAtMs());
        out.put("lastTick", record.lastTick());
        out.put("updateEveryTicks", record.updateEveryTicks());
        out.put("state", record.state());
        Object entity = record.entity();
        if (entity != null) {
            out.put("entity", entity);
            out.put("entityId", ctx.entityId(entity));
            out.put("entityType", ctx.entityType(entity));
            out.put("worldUuid", ctx.entityWorldUuid(entity));
        }
        return out;
    }

    private ActorRecord findRecord(String modId, String actorId) {
        String normalizedModId = normalizeModId(modId);
        String normalizedActorId = normalizeActorId(actorId);
        if (normalizedModId == null || normalizedActorId == null) return null;
        Map<String, ActorRecord> actors = actorsByMod.get(normalizedModId);
        if (actors == null) return null;
        return actors.get(normalizedActorId);
    }

    private static String nextActorId(LuaModContext ctx, LuaTable options) {
        if (ctx == null) return null;
        if (options != null) {
            String explicit = normalizeActorId(options.get("id").optjstring(null));
            if (explicit != null) return explicit;
        }
        return normalizeActorId("actor-" + UUID.randomUUID());
    }

    private static LuaTable vec3(double x, double y, double z) {
        LuaTable out = new LuaTable();
        out.set("x", LuaValue.valueOf(x));
        out.set("y", LuaValue.valueOf(y));
        out.set("z", LuaValue.valueOf(z));
        return out;
    }

    private static LuaFunction asFunction(LuaValue value) {
        return value != null && value.isfunction() ? (LuaFunction) value : null;
    }

    private static String normalizeModId(String modId) {
        if (modId == null) return null;
        String normalized = modId.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeActorId(String actorId) {
        if (actorId == null) return null;
        String normalized = actorId.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeKind(String kind) {
        if (kind == null) return null;
        String normalized = kind.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static LinkedHashSet<String> readTags(LuaValue value) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (value == null || value.isnil()) return out;
        if (value.isstring()) {
            String single = normalizeKind(value.tojstring());
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
            LuaValue v = next.arg(2);
            if (v.isstring()) {
                String tag = normalizeKind(v.tojstring());
                if (tag != null) out.add(tag);
            }
        }
        return out;
    }

    private static LuaTable tagsValue(Set<String> tags) {
        LuaTable out = new LuaTable();
        int i = 1;
        for (String tag : tags) out.set(i++, LuaValue.valueOf(tag));
        return out;
    }

    private static final class ActorRecord {
        private final String actorId;
        private final String modId;
        private final Object entity;
        private final boolean ownedEntity;
        private String kind;
        private final String type;
        private final String initialWorldUuid;
        private Set<String> tags;
        private LuaTable state;
        private final LuaFunction onTick;
        private final LuaFunction onRemove;
        private final long createdAtMs;
        private long lastTick;
        private final int updateEveryTicks;

        private ActorRecord(
                String actorId,
                String modId,
                Object entity,
                boolean ownedEntity,
                String kind,
                String type,
                String initialWorldUuid,
                Set<String> tags,
                LuaTable state,
                LuaFunction onTick,
                LuaFunction onRemove,
                long createdAtMs,
                long lastTick,
                int updateEveryTicks
        ) {
            this.actorId = actorId;
            this.modId = modId;
            this.entity = entity;
            this.ownedEntity = ownedEntity;
            this.kind = kind;
            this.type = type;
            this.initialWorldUuid = initialWorldUuid;
            this.tags = tags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tags);
            this.state = state;
            this.onTick = onTick;
            this.onRemove = onRemove;
            this.createdAtMs = createdAtMs;
            this.lastTick = lastTick;
            this.updateEveryTicks = updateEveryTicks;
        }

        private String actorId() { return actorId; }
        private String modId() { return modId; }
        private Object entity() { return entity; }
        private boolean ownedEntity() { return ownedEntity; }
        private String kind() { return kind; }
        private void kind(String next) { this.kind = next == null ? "actor" : next; }
        private String type() { return type; }
        private String initialWorldUuid() { return initialWorldUuid; }
        private Set<String> tags() { return tags; }
        private void tags(Set<String> next) { this.tags = next == null ? new LinkedHashSet<>() : new LinkedHashSet<>(next); }
        private LuaTable state() { return state; }
        private void state(LuaTable next) { this.state = next == null ? new LuaTable() : next; }
        private LuaFunction onTick() { return onTick; }
        private LuaFunction onRemove() { return onRemove; }
        private long createdAtMs() { return createdAtMs; }
        private long lastTick() { return lastTick; }
        private void lastTick(long next) { this.lastTick = next; }
        private int updateEveryTicks() { return updateEveryTicks; }
    }
}
