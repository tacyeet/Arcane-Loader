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

public final class LuaVolumeRuntime {
    private final LuaModManager manager;
    private final Map<String, Map<String, VolumeRecord>> volumesByMod = new ConcurrentHashMap<>();

    public LuaVolumeRuntime(LuaModManager manager) {
        this.manager = manager;
    }

    public Map<String, Object> addBox(
            LuaModContext ctx,
            String worldUuid,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            LuaTable options
    ) {
        if (ctx == null) return null;
        Bounds bounds = new Bounds(
                Math.min(minX, maxX),
                Math.min(minY, maxY),
                Math.min(minZ, maxZ),
                Math.max(minX, maxX),
                Math.max(minY, maxY),
                Math.max(minZ, maxZ)
        );
        VolumeRecord record = new VolumeRecord(
                nextVolumeId(options),
                normalizeModId(ctx.modId()),
                readKind(options, "volume"),
                "box",
                worldUuid == null ? "" : worldUuid,
                bounds,
                0.0,
                affectsPlayers(options),
                affectsEntities(options),
                forceVec(options),
                readTags(options == null ? null : options.get("tags")),
                options != null && options.get("state").istable() ? (LuaTable) options.get("state") : new LuaTable(),
                asFunction(options == null ? LuaValue.NIL : options.get("onEnter")),
                asFunction(options == null ? LuaValue.NIL : options.get("onTick")),
                asFunction(options == null ? LuaValue.NIL : options.get("onLeave")),
                ConcurrentHashMap.newKeySet()
        );
        Map<String, VolumeRecord> byId = volumesByMod.computeIfAbsent(normalizeModId(ctx.modId()), ignored -> new ConcurrentHashMap<>());
        VolumeRecord previous = byId.put(record.volumeId(), record);
        if (previous != null) {
            previous.currentTargets().clear();
            invokeHandler(ctx, previous, previous.onLeave(), null, "volume_replaced", "replaced");
        }
        return toMap(record);
    }

    public Map<String, Object> addSphere(
            LuaModContext ctx,
            String worldUuid,
            double x,
            double y,
            double z,
            double radius,
            LuaTable options
    ) {
        if (ctx == null || radius < 0.0) return null;
        Bounds bounds = new Bounds(x, y, z, x, y, z);
        VolumeRecord record = new VolumeRecord(
                nextVolumeId(options),
                normalizeModId(ctx.modId()),
                readKind(options, "volume"),
                "sphere",
                worldUuid == null ? "" : worldUuid,
                bounds,
                radius,
                affectsPlayers(options),
                affectsEntities(options),
                forceVec(options),
                readTags(options == null ? null : options.get("tags")),
                options != null && options.get("state").istable() ? (LuaTable) options.get("state") : new LuaTable(),
                asFunction(options == null ? LuaValue.NIL : options.get("onEnter")),
                asFunction(options == null ? LuaValue.NIL : options.get("onTick")),
                asFunction(options == null ? LuaValue.NIL : options.get("onLeave")),
                ConcurrentHashMap.newKeySet()
        );
        Map<String, VolumeRecord> byId = volumesByMod.computeIfAbsent(normalizeModId(ctx.modId()), ignored -> new ConcurrentHashMap<>());
        VolumeRecord previous = byId.put(record.volumeId(), record);
        if (previous != null) {
            previous.currentTargets().clear();
            invokeHandler(ctx, previous, previous.onLeave(), null, "volume_replaced", "replaced");
        }
        return toMap(record);
    }

    public List<Map<String, Object>> list(LuaModContext ctx) {
        if (ctx == null) return List.of();
        Map<String, VolumeRecord> volumes = volumesByMod.get(normalizeModId(ctx.modId()));
        if (volumes == null || volumes.isEmpty()) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>(volumes.keySet());
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        for (String id : ids) {
            VolumeRecord record = volumes.get(id);
            if (record == null) continue;
            out.add(Collections.unmodifiableMap(toMap(record)));
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
            if (current != null && normalizedKind.equals(normalizeKind(String.valueOf(current)))) out.add(row);
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

    public Map<String, Object> find(LuaModContext ctx, String volumeId) {
        VolumeRecord record = findRecord(ctx == null ? null : ctx.modId(), volumeId);
        return record == null ? null : toMap(record);
    }

    public boolean setState(String modId, String volumeId, LuaTable state) {
        VolumeRecord record = findRecord(modId, volumeId);
        if (record == null || state == null) return false;
        record.state(state);
        return true;
    }

    public boolean moveCenter(String modId, String volumeId, double x, double y, double z) {
        VolumeRecord record = findRecord(modId, volumeId);
        if (record == null) return false;
        if ("sphere".equals(record.shape())) {
            record.bounds(new Bounds(x, y, z, x, y, z));
            return true;
        }
        Bounds bounds = record.bounds();
        double halfX = (bounds.bx() - bounds.ax()) * 0.5;
        double halfY = (bounds.by() - bounds.ay()) * 0.5;
        double halfZ = (bounds.bz() - bounds.az()) * 0.5;
        record.bounds(new Bounds(x - halfX, y - halfY, z - halfZ, x + halfX, y + halfY, z + halfZ));
        return true;
    }

    public LuaTable state(String modId, String volumeId) {
        VolumeRecord record = findRecord(modId, volumeId);
        return record == null ? null : record.state();
    }

    public boolean containsPoint(String modId, String volumeId, double x, double y, double z) {
        VolumeRecord record = findRecord(modId, volumeId);
        return record != null && inside(record, new double[] { x, y, z });
    }

    public boolean containsEntity(LuaModContext ctx, String volumeId, Object entity) {
        if (ctx == null || entity == null) return false;
        double[] pos = ctx.entityPosition(entity);
        return pos != null && containsPoint(ctx.modId(), volumeId, pos[0], pos[1], pos[2]);
    }

    public boolean containsPlayer(LuaModContext ctx, String volumeId, Object player) {
        if (ctx == null || player == null) return false;
        double[] pos = ctx.playerPosition(player);
        return pos != null && containsPoint(ctx.modId(), volumeId, pos[0], pos[1], pos[2]);
    }

    public boolean remove(LuaModContext ctx, String volumeId, String reason) {
        if (ctx == null) return false;
        String modId = normalizeModId(ctx.modId());
        String normalizedVolumeId = normalizeVolumeId(volumeId);
        if (modId == null || normalizedVolumeId == null) return false;
        Map<String, VolumeRecord> volumes = volumesByMod.get(modId);
        if (volumes == null) return false;
        VolumeRecord record = volumes.remove(normalizedVolumeId);
        if (volumes.isEmpty()) volumesByMod.remove(modId, volumes);
        if (record == null) return false;
        record.currentTargets().clear();
        invokeHandler(ctx, record, record.onLeave(), null, "volume_removed", reason == null ? "removed" : reason);
        return true;
    }

    public int clear(LuaModContext ctx) {
        if (ctx == null) return 0;
        String modId = normalizeModId(ctx.modId());
        if (modId == null) return 0;
        Map<String, VolumeRecord> removed = volumesByMod.remove(modId);
        if (removed == null || removed.isEmpty()) return 0;
        for (VolumeRecord record : removed.values()) {
            record.currentTargets().clear();
            invokeHandler(ctx, record, record.onLeave(), null, "volume_cleanup", "cleanup");
        }
        return removed.size();
    }

    public void tick(long tick, long timestampMs) {
        for (LuaMod mod : manager.listMods()) {
            if (mod.state() != LuaModState.ENABLED || mod.ctx() == null) continue;
            LuaModContext ctx = mod.ctx();
            Map<String, VolumeRecord> volumes = volumesByMod.get(normalizeModId(mod.manifest().id()));
            if (volumes == null || volumes.isEmpty()) continue;
            ArrayList<VolumeRecord> snapshot = new ArrayList<>(volumes.values());
            snapshot.sort((a, b) -> a.volumeId().compareToIgnoreCase(b.volumeId()));
            for (VolumeRecord record : snapshot) {
                tickVolume(ctx, mod, record, tick, timestampMs);
            }
        }
    }

    private void tickVolume(LuaModContext ctx, LuaMod mod, VolumeRecord record, long tick, long timestampMs) {
        ArrayList<TargetRef> targets = new ArrayList<>();
        if (record.affectsPlayers()) {
            List<Object> players = record.worldUuid().isBlank() ? ctx.onlinePlayers() : ctx.onlinePlayersInWorld(record.worldUuid());
            for (Object player : players) {
                double[] pos = ctx.playerPosition(player);
                if (inside(record, pos)) {
                    targets.add(new TargetRef("player", player, ctx.playerUuid(player), pos));
                }
            }
        }
        if (record.affectsEntities()) {
            List<Object> entities = record.worldUuid().isBlank() ? ctx.entities() : ctx.entitiesInWorld(record.worldUuid());
            for (Object entity : entities) {
                double[] pos = ctx.entityPosition(entity);
                if (inside(record, pos)) {
                    targets.add(new TargetRef("entity", entity, ctx.entityId(entity), pos));
                }
            }
        }

        Set<String> current = record.currentTargets();
        Set<String> next = ConcurrentHashMap.newKeySet();
        for (TargetRef target : targets) {
            String targetKey = target.kind() + ":" + normalizeTargetId(target.id());
            if (targetKey.endsWith(":null")) continue;
            next.add(targetKey);
            boolean entering = current.add(targetKey);
            if (entering) {
                invokeHandler(ctx, record, record.onEnter(), target, "volume_enter", "enter");
            }
            invokeHandler(ctx, record, record.onTick(), target, "volume_tick", "tick");
            applyForce(ctx, record, target);
        }

        ArrayList<String> leaving = new ArrayList<>();
        for (String existing : current) {
            if (!next.contains(existing)) leaving.add(existing);
        }
        for (String key : leaving) {
            current.remove(key);
            invokeHandler(ctx, record, record.onLeave(), null, "volume_leave", "leave");
        }
    }

    private void applyForce(LuaModContext ctx, VolumeRecord record, TargetRef target) {
        double[] force = record.force();
        if (force == null || force.length < 3) return;
        if ("entity".equals(target.kind())) {
            ctx.setEntityVelocity(target.ref(), force[0], force[1], force[2]);
            return;
        }
        if ("player".equals(target.kind()) && ctx.hasCapability("player-movement")) {
            double[] pos = target.position();
            if (pos != null && pos.length >= 3) {
                ctx.teleportPlayer(target.ref(), ctx.playerWorldUuid(target.ref()), pos[0] + force[0], pos[1] + force[1], pos[2] + force[2]);
            }
        }
    }

    private void invokeHandler(LuaModContext ctx, VolumeRecord record, LuaFunction fn, TargetRef target, String metricKey, String reason) {
        if (ctx == null || record == null || fn == null) return;
        LuaMod mod = manager.findById(ctx.modId());
        if (mod == null) return;
        LuaTable payload = volumePayload(ctx, record, target, reason);
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
            manager.recordRuntimeError(mod, "Volume handler error (" + record.volumeId() + ")", t);
            if (manager.isDebugLogging()) {
                ctx.plugin().getLogger().at(Level.WARNING).log(
                        "Volume handler failed for mod=" + mod.manifest().id()
                                + " volumeId=" + record.volumeId()
                                + " metric=" + metricKey
                                + " error=" + t
                );
            }
        } finally {
            manager.recordRuntimeInvocation(mod, "volumes:" + metricKey + ":" + record.volumeId(), start, failed);
        }
    }

    private LuaTable volumePayload(LuaModContext ctx, VolumeRecord record, TargetRef target, String reason) {
        LuaTable payload = new LuaTable();
        payload.set("volumeId", LuaValue.valueOf(record.volumeId()));
        payload.set("kind", LuaValue.valueOf(record.kind()));
        payload.set("tags", tagsValue(record.tags()));
        payload.set("shape", LuaValue.valueOf(record.shape()));
        payload.set("worldUuid", LuaValue.valueOf(record.worldUuid()));
        payload.set("state", record.state());
        payload.set("reason", LuaValue.valueOf(reason == null ? "" : reason));
        if (record.force() != null) {
            payload.set("force", vec3(record.force()[0], record.force()[1], record.force()[2]));
        }
        if ("box".equals(record.shape())) {
            payload.set("min", vec3(record.bounds().ax(), record.bounds().ay(), record.bounds().az()));
            payload.set("max", vec3(record.bounds().bx(), record.bounds().by(), record.bounds().bz()));
        } else {
            payload.set("center", vec3(record.bounds().ax(), record.bounds().ay(), record.bounds().az()));
            payload.set("radius", LuaValue.valueOf(record.radius()));
        }
        if (target != null) {
            payload.set("targetType", LuaValue.valueOf(target.kind()));
            if (target.id() != null) payload.set("targetId", LuaValue.valueOf(target.id()));
            payload.set("target", LuaEngine.javaToLuaValue(target.ref()));
            if (target.position() != null && target.position().length >= 3) {
                payload.set("position", vec3(target.position()[0], target.position()[1], target.position()[2]));
            }
        }
        return payload;
    }

    private static boolean inside(VolumeRecord record, double[] pos) {
        if (record == null || pos == null || pos.length < 3) return false;
        if ("sphere".equals(record.shape())) {
            double dx = pos[0] - record.bounds().ax();
            double dy = pos[1] - record.bounds().ay();
            double dz = pos[2] - record.bounds().az();
            return (dx * dx + dy * dy + dz * dz) <= (record.radius() * record.radius());
        }
        return pos[0] >= record.bounds().ax() && pos[0] <= record.bounds().bx()
                && pos[1] >= record.bounds().ay() && pos[1] <= record.bounds().by()
                && pos[2] >= record.bounds().az() && pos[2] <= record.bounds().bz();
    }

    private VolumeRecord findRecord(String modId, String volumeId) {
        String normalizedModId = normalizeModId(modId);
        String normalizedVolumeId = normalizeVolumeId(volumeId);
        if (normalizedModId == null || normalizedVolumeId == null) return null;
        Map<String, VolumeRecord> volumes = volumesByMod.get(normalizedModId);
        if (volumes == null) return null;
        return volumes.get(normalizedVolumeId);
    }

    private static LinkedHashMap<String, Object> toMap(VolumeRecord record) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("volumeId", record.volumeId());
        out.put("kind", record.kind());
        out.put("tags", new ArrayList<>(record.tags()));
        out.put("shape", record.shape());
        out.put("worldUuid", record.worldUuid());
        out.put("affectsPlayers", record.affectsPlayers());
        out.put("affectsEntities", record.affectsEntities());
        out.put("state", record.state());
        if (record.force() != null) out.put("force", record.force());
        if ("box".equals(record.shape())) {
            out.put("min", new double[] { record.bounds().ax(), record.bounds().ay(), record.bounds().az() });
            out.put("max", new double[] { record.bounds().bx(), record.bounds().by(), record.bounds().bz() });
        } else {
            out.put("center", new double[] { record.bounds().ax(), record.bounds().ay(), record.bounds().az() });
            out.put("radius", record.radius());
        }
        return out;
    }

    private static double[] forceVec(LuaTable options) {
        if (options == null) return null;
        LuaValue force = options.get("force");
        if (!force.istable()) return null;
        return new double[] {
                force.get("x").optdouble(0.0),
                force.get("y").optdouble(0.0),
                force.get("z").optdouble(0.0)
        };
    }

    private static boolean affectsPlayers(LuaTable options) {
        if (options == null) return true;
        LuaValue affects = options.get("affects");
        if (affects.isstring()) {
            String v = affects.tojstring().trim().toLowerCase(Locale.ROOT);
            return v.equals("players") || v.equals("both");
        }
        return options.get("players").optboolean(true);
    }

    private static boolean affectsEntities(LuaTable options) {
        if (options == null) return true;
        LuaValue affects = options.get("affects");
        if (affects.isstring()) {
            String v = affects.tojstring().trim().toLowerCase(Locale.ROOT);
            return v.equals("entities") || v.equals("both");
        }
        return options.get("entities").optboolean(true);
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

    private static String nextVolumeId(LuaTable options) {
        if (options != null) {
            String explicit = normalizeVolumeId(options.get("id").optjstring(null));
            if (explicit != null) return explicit;
        }
        return normalizeVolumeId("volume-" + UUID.randomUUID());
    }

    private static String normalizeModId(String modId) {
        if (modId == null) return null;
        String normalized = modId.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeVolumeId(String volumeId) {
        if (volumeId == null) return null;
        String normalized = volumeId.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeTargetId(String targetId) {
        if (targetId == null || targetId.isBlank()) return "null";
        return targetId.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeKind(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String readKind(LuaTable options, String fallback) {
        if (options == null) return fallback;
        String kind = normalizeKind(options.get("kind").optjstring(null));
        return kind == null ? fallback : kind;
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
            LuaValue current = next.arg(2);
            if (current.isstring()) {
                String tag = normalizeKind(current.tojstring());
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

    private record Bounds(double ax, double ay, double az, double bx, double by, double bz) {}
    private record TargetRef(String kind, Object ref, String id, double[] position) {}

    private static final class VolumeRecord {
        private final String volumeId;
        private final String modId;
        private final String kind;
        private final String shape;
        private final String worldUuid;
        private Bounds bounds;
        private final double radius;
        private final boolean affectsPlayers;
        private final boolean affectsEntities;
        private final double[] force;
        private final Set<String> tags;
        private LuaTable state;
        private final LuaFunction onEnter;
        private final LuaFunction onTick;
        private final LuaFunction onLeave;
        private final Set<String> currentTargets;

        private VolumeRecord(
                String volumeId,
                String modId,
                String kind,
                String shape,
                String worldUuid,
                Bounds bounds,
                double radius,
                boolean affectsPlayers,
                boolean affectsEntities,
                double[] force,
                Set<String> tags,
                LuaTable state,
                LuaFunction onEnter,
                LuaFunction onTick,
                LuaFunction onLeave,
                Set<String> currentTargets
        ) {
            this.volumeId = volumeId;
            this.modId = modId;
            this.kind = kind;
            this.shape = shape;
            this.worldUuid = worldUuid;
            this.bounds = bounds;
            this.radius = radius;
            this.affectsPlayers = affectsPlayers;
            this.affectsEntities = affectsEntities;
            this.force = force;
            this.tags = tags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tags);
            this.state = state == null ? new LuaTable() : state;
            this.onEnter = onEnter;
            this.onTick = onTick;
            this.onLeave = onLeave;
            this.currentTargets = currentTargets;
        }

        private String volumeId() { return volumeId; }
        private String modId() { return modId; }
        private String kind() { return kind; }
        private String shape() { return shape; }
        private String worldUuid() { return worldUuid; }
        private Bounds bounds() { return bounds; }
        private void bounds(Bounds next) { this.bounds = next == null ? this.bounds : next; }
        private double radius() { return radius; }
        private boolean affectsPlayers() { return affectsPlayers; }
        private boolean affectsEntities() { return affectsEntities; }
        private double[] force() { return force; }
        private Set<String> tags() { return tags; }
        private LuaTable state() { return state; }
        private void state(LuaTable next) { this.state = next == null ? new LuaTable() : next; }
        private LuaFunction onEnter() { return onEnter; }
        private LuaFunction onTick() { return onTick; }
        private LuaFunction onLeave() { return onLeave; }
        private Set<String> currentTargets() { return currentTargets; }
    }
}
