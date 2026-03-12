package arcane.loader.lua;

import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.ArrayDeque;
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

public final class LuaMechanicsRuntime {
    private final LuaModManager manager;
    private final Map<String, Map<String, NodeRecord>> nodesByMod = new ConcurrentHashMap<>();
    private final Map<String, Map<String, LinkRecord>> linksByMod = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> dirtyNodesByMod = new ConcurrentHashMap<>();

    public LuaMechanicsRuntime(LuaModManager manager) {
        this.manager = manager;
    }

    public Map<String, Object> register(
            LuaModContext ctx,
            String worldUuid,
            double x,
            double y,
            double z,
            LuaTable options
    ) {
        if (ctx == null) return null;
        String nodeId = nextNodeId(options);
        String kind = options == null ? "node" : normalizeNodeId(options.get("kind").optjstring("node"));
        double radius = options == null ? 1.5 : Math.max(0.1, options.get("radius").optdouble(1.5));
        LuaTable state = options != null && options.get("state").istable() ? (LuaTable) options.get("state") : new LuaTable();
        LuaFunction onUpdate = asFunction(options == null ? LuaValue.NIL : options.get("onUpdate"));
        NodeRecord record = new NodeRecord(
                nodeId,
                normalizeModId(ctx.modId()),
                worldUuid == null ? "" : worldUuid,
                x,
                y,
                z,
                kind == null ? "node" : kind,
                radius,
                readTags(options == null ? null : options.get("tags")),
                state,
                onUpdate
        );
        Map<String, NodeRecord> byId = nodesByMod.computeIfAbsent(normalizeModId(ctx.modId()), ignored -> new ConcurrentHashMap<>());
        byId.put(nodeId, record);
        markDirty(ctx.modId(), nodeId);
        return toMap(record);
    }

    public boolean unregister(String modId, String nodeId) {
        String normalizedModId = normalizeModId(modId);
        String normalizedNodeId = normalizeNodeId(nodeId);
        if (normalizedModId == null || normalizedNodeId == null) return false;
        Map<String, NodeRecord> nodes = nodesByMod.get(normalizedModId);
        if (nodes == null) return false;
        NodeRecord removed = nodes.remove(normalizedNodeId);
        if (removed == null) return false;
        if (nodes.isEmpty()) nodesByMod.remove(normalizedModId, nodes);
        Map<String, LinkRecord> links = linksByMod.get(normalizedModId);
        if (links != null) {
            ArrayList<String> removeIds = new ArrayList<>();
            for (LinkRecord link : links.values()) {
                if (normalizedNodeId.equals(link.fromNodeId()) || normalizedNodeId.equals(link.toNodeId())) {
                    removeIds.add(link.linkId());
                }
            }
            for (String linkId : removeIds) {
                links.remove(linkId);
            }
            if (links.isEmpty()) linksByMod.remove(normalizedModId, links);
        }
        markDirty(normalizedModId, normalizedNodeId);
        return true;
    }

    public int clear(String modId) {
        String normalizedModId = normalizeModId(modId);
        if (normalizedModId == null) return 0;
        Map<String, NodeRecord> removed = nodesByMod.remove(normalizedModId);
        linksByMod.remove(normalizedModId);
        dirtyNodesByMod.remove(normalizedModId);
        return removed == null ? 0 : removed.size();
    }

    public List<Map<String, Object>> list(LuaModContext ctx) {
        if (ctx == null) return List.of();
        Map<String, NodeRecord> nodes = nodesByMod.get(normalizeModId(ctx.modId()));
        if (nodes == null || nodes.isEmpty()) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>(nodes.keySet());
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        for (String id : ids) {
            NodeRecord record = nodes.get(id);
            if (record == null) continue;
            out.add(Collections.unmodifiableMap(toMap(record)));
        }
        return Collections.unmodifiableList(out);
    }

    public List<Map<String, Object>> listByKind(LuaModContext ctx, String kind) {
        if (ctx == null) return List.of();
        String normalizedKind = normalizeNodeId(kind);
        if (normalizedKind == null) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : list(ctx)) {
            Object current = row.get("kind");
            if (current != null && normalizedKind.equals(normalizeNodeId(String.valueOf(current)))) out.add(row);
        }
        return Collections.unmodifiableList(out);
    }

    public List<Map<String, Object>> listByTag(LuaModContext ctx, String tag) {
        if (ctx == null) return List.of();
        String normalizedTag = normalizeNodeId(tag);
        if (normalizedTag == null) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : list(ctx)) {
            Object tags = row.get("tags");
            if (!(tags instanceof List<?> list)) continue;
            for (Object tagValue : list) {
                if (tagValue != null && normalizedTag.equals(normalizeNodeId(String.valueOf(tagValue)))) {
                    out.add(row);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    public Map<String, Object> find(LuaModContext ctx, String nodeId) {
        NodeRecord record = findRecord(ctx == null ? null : ctx.modId(), nodeId);
        return record == null ? null : toMap(record);
    }

    public List<Map<String, Object>> neighborsOf(LuaModContext ctx, String nodeId) {
        if (ctx == null) return List.of();
        NodeRecord node = findRecord(ctx.modId(), nodeId);
        if (node == null) return List.of();
        return Collections.unmodifiableList(neighborRows(ctx.modId(), node, allNodesOrdered(ctx.modId()), true));
    }

    public LuaTable state(String modId, String nodeId) {
        NodeRecord record = findRecord(modId, nodeId);
        return record == null ? null : record.state();
    }

    public boolean setState(String modId, String nodeId, LuaTable state) {
        NodeRecord record = findRecord(modId, nodeId);
        if (record == null || state == null) return false;
        record.state(state);
        markDirty(modId, nodeId);
        return true;
    }

    public boolean move(String modId, String nodeId, double x, double y, double z) {
        NodeRecord record = findRecord(modId, nodeId);
        if (record == null) return false;
        record.x(x);
        record.y(y);
        record.z(z);
        markDirty(modId, nodeId);
        return true;
    }

    public Map<String, Object> connect(LuaModContext ctx, String fromNodeId, String toNodeId, LuaTable options) {
        if (ctx == null) return null;
        String modId = normalizeModId(ctx.modId());
        String from = normalizeNodeId(fromNodeId);
        String to = normalizeNodeId(toNodeId);
        if (modId == null || from == null || to == null || from.equals(to)) return null;
        NodeRecord left = findRecord(modId, from);
        NodeRecord right = findRecord(modId, to);
        if (left == null || right == null) return null;
        if (!sameWorld(left.worldUuid(), right.worldUuid())) return null;

        String linkId = nextLinkId(options);
        String kind = options == null ? "link" : normalizeNodeId(options.get("kind").optjstring("link"));
        boolean directed = options != null && options.get("directed").optboolean(false);
        LuaTable state = options != null && options.get("state").istable() ? (LuaTable) options.get("state") : new LuaTable();
        LinkRecord link = new LinkRecord(
                linkId,
                modId,
                from,
                to,
                kind == null ? "link" : kind,
                directed,
                readTags(options == null ? null : options.get("tags")),
                state
        );
        linksByMod.computeIfAbsent(modId, ignored -> new ConcurrentHashMap<>()).put(linkId, link);
        markDirty(modId, from);
        markDirty(modId, to);
        return toMap(link);
    }

    public boolean disconnect(String modId, String linkId) {
        String normalizedModId = normalizeModId(modId);
        String normalizedLinkId = normalizeNodeId(linkId);
        if (normalizedModId == null || normalizedLinkId == null) return false;
        Map<String, LinkRecord> links = linksByMod.get(normalizedModId);
        if (links == null) return false;
        LinkRecord removed = links.remove(normalizedLinkId);
        if (removed == null) return false;
        if (links.isEmpty()) linksByMod.remove(normalizedModId, links);
        markDirty(normalizedModId, removed.fromNodeId());
        markDirty(normalizedModId, removed.toNodeId());
        return true;
    }

    public List<Map<String, Object>> links(LuaModContext ctx) {
        if (ctx == null) return List.of();
        Map<String, LinkRecord> links = linksByMod.get(normalizeModId(ctx.modId()));
        if (links == null || links.isEmpty()) return List.of();
        ArrayList<LinkRecord> ordered = new ArrayList<>(links.values());
        ordered.sort((a, b) -> a.linkId().compareToIgnoreCase(b.linkId()));
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (LinkRecord link : ordered) {
            out.add(Collections.unmodifiableMap(toMap(link)));
        }
        return Collections.unmodifiableList(out);
    }

    public List<Map<String, Object>> linksOf(LuaModContext ctx, String nodeId) {
        if (ctx == null) return List.of();
        String normalizedNodeId = normalizeNodeId(nodeId);
        if (normalizedNodeId == null) return List.of();
        Map<String, LinkRecord> links = linksByMod.get(normalizeModId(ctx.modId()));
        if (links == null || links.isEmpty()) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        ArrayList<LinkRecord> ordered = new ArrayList<>(links.values());
        ordered.sort((a, b) -> a.linkId().compareToIgnoreCase(b.linkId()));
        for (LinkRecord link : ordered) {
            if (normalizedNodeId.equals(link.fromNodeId()) || normalizedNodeId.equals(link.toNodeId())) {
                out.add(Collections.unmodifiableMap(toMap(link)));
            }
        }
        return Collections.unmodifiableList(out);
    }

    public Map<String, Object> findLink(LuaModContext ctx, String linkId) {
        if (ctx == null) return null;
        LinkRecord link = findLinkRecord(ctx.modId(), linkId);
        return link == null ? null : toMap(link);
    }

    public List<Map<String, Object>> componentOf(LuaModContext ctx, String nodeId) {
        if (ctx == null) return List.of();
        String modId = normalizeModId(ctx.modId());
        String start = normalizeNodeId(nodeId);
        if (modId == null || start == null || findRecord(modId, start) == null) return List.of();
        Set<String> visited = bfs(modId, start);
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        ArrayList<String> ordered = new ArrayList<>(visited);
        ordered.sort(String.CASE_INSENSITIVE_ORDER);
        for (String current : ordered) {
            NodeRecord node = findRecord(modId, current);
            if (node != null) out.add(Collections.unmodifiableMap(toMap(node)));
        }
        return Collections.unmodifiableList(out);
    }

    public List<String> path(LuaModContext ctx, String fromNodeId, String toNodeId) {
        if (ctx == null) return List.of();
        String modId = normalizeModId(ctx.modId());
        String from = normalizeNodeId(fromNodeId);
        String to = normalizeNodeId(toNodeId);
        if (modId == null || from == null || to == null) return List.of();
        if (findRecord(modId, from) == null || findRecord(modId, to) == null) return List.of();
        ArrayDeque<String> queue = new ArrayDeque<>();
        Map<String, String> prev = new LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(from);
        visited.add(from);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (current.equals(to)) break;
            for (String next : adjacentNodeIds(modId, current, true)) {
                if (!visited.add(next)) continue;
                prev.put(next, current);
                queue.addLast(next);
            }
        }
        if (!visited.contains(to)) return List.of();
        ArrayDeque<String> reversed = new ArrayDeque<>();
        String cursor = to;
        while (cursor != null) {
            reversed.addFirst(cursor);
            cursor = prev.get(cursor);
        }
        return Collections.unmodifiableList(new ArrayList<>(reversed));
    }

    public boolean markDirty(String modId, String nodeId) {
        String normalizedModId = normalizeModId(modId);
        String normalizedNodeId = normalizeNodeId(nodeId);
        if (normalizedModId == null || normalizedNodeId == null) return false;
        dirtyNodesByMod.computeIfAbsent(normalizedModId, ignored -> ConcurrentHashMap.newKeySet()).add(normalizedNodeId);
        return true;
    }

    public List<String> dirtyNodes(String modId) {
        String normalizedModId = normalizeModId(modId);
        if (normalizedModId == null) return List.of();
        Set<String> dirty = dirtyNodesByMod.get(normalizedModId);
        if (dirty == null || dirty.isEmpty()) return List.of();
        ArrayList<String> out = new ArrayList<>(dirty);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return Collections.unmodifiableList(out);
    }

    public void tick(long tick, long timestampMs) {
        for (LuaMod mod : manager.listMods()) {
            if (mod.state() != LuaModState.ENABLED || mod.ctx() == null) continue;
            LuaModContext ctx = mod.ctx();
            String modId = normalizeModId(mod.manifest().id());
            Map<String, NodeRecord> nodes = nodesByMod.get(modId);
            if (nodes == null || nodes.isEmpty()) continue;
            ArrayList<NodeRecord> snapshot = new ArrayList<>(nodes.values());
            snapshot.sort((a, b) -> a.nodeId().compareToIgnoreCase(b.nodeId()));
            for (NodeRecord node : snapshot) {
                tickNode(ctx, mod, node, snapshot, tick, timestampMs);
            }
            Set<String> dirty = dirtyNodesByMod.get(modId);
            if (dirty != null) dirty.clear();
        }
    }

    private void tickNode(LuaModContext ctx, LuaMod mod, NodeRecord node, List<NodeRecord> scope, long tick, long timestampMs) {
        LuaFunction onUpdate = node.onUpdate();
        if (onUpdate == null) return;
        LuaTable payload = new LuaTable();
        payload.set("nodeId", LuaValue.valueOf(node.nodeId()));
        payload.set("kind", LuaValue.valueOf(node.kind()));
        payload.set("worldUuid", LuaValue.valueOf(node.worldUuid()));
        payload.set("position", vec3(node.x(), node.y(), node.z()));
        payload.set("radius", LuaValue.valueOf(node.radius()));
        payload.set("tags", tagsValue(node.tags()));
        payload.set("tick", LuaValue.valueOf(tick));
        payload.set("timestampMs", LuaValue.valueOf(timestampMs));
        payload.set("state", node.state());
        payload.set("dirty", LuaValue.valueOf(isDirty(node.modId(), node.nodeId())));
        payload.set("neighbors", LuaEngine.javaToLuaValue(neighborRows(node.modId(), node, scope, false)));
        payload.set("links", LuaEngine.javaToLuaValue(linksOfMaps(node.modId(), node.nodeId())));
        payload.set("component", LuaEngine.javaToLuaValue(componentIds(node.modId(), node.nodeId())));

        long start = System.nanoTime();
        boolean failed = false;
        try {
            Varargs result = onUpdate.invoke(payload);
            LuaValue first = result == null ? LuaValue.NIL : result.arg1();
            if (first != null && first.istable()) {
                node.state((LuaTable) first);
            }
        } catch (Throwable t) {
            failed = true;
            manager.recordRuntimeError(mod, "Mechanics node error (" + node.nodeId() + ")", t);
            if (manager.isDebugLogging()) {
                ctx.plugin().getLogger().at(Level.WARNING).log(
                        "Mechanics node failed for mod=" + mod.manifest().id()
                                + " nodeId=" + node.nodeId()
                                + " error=" + t
                );
            }
        } finally {
            manager.recordRuntimeInvocation(mod, "mechanics:update:" + node.nodeId(), start, failed);
        }
    }

    private boolean isDirty(String modId, String nodeId) {
        Set<String> dirty = dirtyNodesByMod.get(normalizeModId(modId));
        return dirty != null && dirty.contains(normalizeNodeId(nodeId));
    }

    private NodeRecord findRecord(String modId, String nodeId) {
        String normalizedModId = normalizeModId(modId);
        String normalizedNodeId = normalizeNodeId(nodeId);
        if (normalizedModId == null || normalizedNodeId == null) return null;
        Map<String, NodeRecord> nodes = nodesByMod.get(normalizedModId);
        if (nodes == null) return null;
        return nodes.get(normalizedNodeId);
    }

    private LinkRecord findLinkRecord(String modId, String linkId) {
        String normalizedModId = normalizeModId(modId);
        String normalizedLinkId = normalizeNodeId(linkId);
        if (normalizedModId == null || normalizedLinkId == null) return null;
        Map<String, LinkRecord> links = linksByMod.get(normalizedModId);
        if (links == null) return null;
        return links.get(normalizedLinkId);
    }

    private ArrayList<NodeRecord> allNodesOrdered(String modId) {
        Map<String, NodeRecord> nodes = nodesByMod.get(normalizeModId(modId));
        if (nodes == null || nodes.isEmpty()) return new ArrayList<>();
        ArrayList<NodeRecord> out = new ArrayList<>(nodes.values());
        out.sort((a, b) -> a.nodeId().compareToIgnoreCase(b.nodeId()));
        return out;
    }

    private List<Map<String, Object>> neighborRows(String modId, NodeRecord node, List<NodeRecord> scope, boolean immutable) {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (NodeRecord other : scope) {
            if (other == node) continue;
            if (!sameWorld(node.worldUuid(), other.worldUuid())) continue;
            double dx = other.x() - node.x();
            double dy = other.y() - node.y();
            double dz = other.z() - node.z();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean explicitLinked = areLinked(modId, node.nodeId(), other.nodeId());
            if (!explicitLinked && distance > Math.max(node.radius(), other.radius())) continue;
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("nodeId", other.nodeId());
            row.put("kind", other.kind());
            row.put("tags", new ArrayList<>(other.tags()));
            row.put("distance", distance);
            row.put("delta", Map.of("x", dx, "y", dy, "z", dz));
            row.put("axisAligned", isAxisAligned(dx, dy, dz));
            row.put("dominantAxis", dominantAxis(dx, dy, dz));
            row.put("linked", explicitLinked);
            row.put("state", other.state());
            out.add(immutable ? Collections.unmodifiableMap(row) : row);
        }
        return out;
    }

    private boolean areLinked(String modId, String a, String b) {
        for (LinkRecord link : linksForMod(modId)) {
            if (a.equals(link.fromNodeId()) && b.equals(link.toNodeId())) return true;
            if (!link.directed() && a.equals(link.toNodeId()) && b.equals(link.fromNodeId())) return true;
        }
        return false;
    }

    private List<LinkRecord> linksForMod(String modId) {
        Map<String, LinkRecord> links = linksByMod.get(normalizeModId(modId));
        if (links == null || links.isEmpty()) return List.of();
        ArrayList<LinkRecord> out = new ArrayList<>(links.values());
        out.sort((a, b) -> a.linkId().compareToIgnoreCase(b.linkId()));
        return out;
    }

    private List<Map<String, Object>> linksOfMaps(String modId, String nodeId) {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (LinkRecord link : linksForMod(modId)) {
            if (nodeId.equals(link.fromNodeId()) || nodeId.equals(link.toNodeId())) {
                out.add(Collections.unmodifiableMap(toMap(link)));
            }
        }
        return out;
    }

    private Set<String> bfs(String modId, String start) {
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacentNodeIds(modId, current, false)) {
                if (visited.add(next)) queue.addLast(next);
            }
        }
        return visited;
    }

    private List<String> componentIds(String modId, String nodeId) {
        ArrayList<String> out = new ArrayList<>(bfs(modId, nodeId));
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private Set<String> adjacentNodeIds(String modId, String nodeId, boolean respectDirection) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (LinkRecord link : linksForMod(modId)) {
            if (nodeId.equals(link.fromNodeId())) out.add(link.toNodeId());
            if (!respectDirection && nodeId.equals(link.toNodeId())) out.add(link.fromNodeId());
            if (!link.directed() && nodeId.equals(link.toNodeId())) out.add(link.fromNodeId());
        }
        return out;
    }

    private static LinkedHashMap<String, Object> toMap(NodeRecord node) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("nodeId", node.nodeId());
        out.put("kind", node.kind());
        out.put("worldUuid", node.worldUuid());
        out.put("x", node.x());
        out.put("y", node.y());
        out.put("z", node.z());
        out.put("tags", new ArrayList<>(node.tags()));
        out.put("radius", node.radius());
        out.put("state", node.state());
        return out;
    }

    private static LinkedHashMap<String, Object> toMap(LinkRecord link) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("linkId", link.linkId());
        out.put("kind", link.kind());
        out.put("fromNodeId", link.fromNodeId());
        out.put("toNodeId", link.toNodeId());
        out.put("directed", link.directed());
        out.put("tags", new ArrayList<>(link.tags()));
        out.put("state", link.state());
        return out;
    }

    private static LuaTable vec3(double x, double y, double z) {
        LuaTable out = new LuaTable();
        out.set("x", LuaValue.valueOf(x));
        out.set("y", LuaValue.valueOf(y));
        out.set("z", LuaValue.valueOf(z));
        return out;
    }

    private static boolean sameWorld(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        return left.equalsIgnoreCase(right);
    }

    private static LuaFunction asFunction(LuaValue value) {
        return value != null && value.isfunction() ? (LuaFunction) value : null;
    }

    private static LinkedHashSet<String> readTags(LuaValue value) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (value == null || value.isnil()) return out;
        if (value.isstring()) {
            String single = normalizeNodeId(value.tojstring());
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
                String tag = normalizeNodeId(current.tojstring());
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

    private static boolean isAxisAligned(double dx, double dy, double dz) {
        int nonZero = 0;
        if (Math.abs(dx) > 0.0001) nonZero++;
        if (Math.abs(dy) > 0.0001) nonZero++;
        if (Math.abs(dz) > 0.0001) nonZero++;
        return nonZero <= 1;
    }

    private static String dominantAxis(double dx, double dy, double dz) {
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ax >= ay && ax >= az) return "x";
        if (ay >= ax && ay >= az) return "y";
        return "z";
    }

    private static String nextNodeId(LuaTable options) {
        if (options != null) {
            String explicit = normalizeNodeId(options.get("id").optjstring(null));
            if (explicit != null) return explicit;
        }
        return normalizeNodeId("node-" + UUID.randomUUID());
    }

    private static String nextLinkId(LuaTable options) {
        if (options != null) {
            String explicit = normalizeNodeId(options.get("id").optjstring(null));
            if (explicit != null) return explicit;
        }
        return normalizeNodeId("link-" + UUID.randomUUID());
    }

    private static String normalizeModId(String modId) {
        if (modId == null) return null;
        String normalized = modId.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeNodeId(String nodeId) {
        if (nodeId == null) return null;
        String normalized = nodeId.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static final class NodeRecord {
        private final String nodeId;
        private final String modId;
        private final String worldUuid;
        private double x;
        private double y;
        private double z;
        private final String kind;
        private final double radius;
        private final Set<String> tags;
        private LuaTable state;
        private final LuaFunction onUpdate;

        private NodeRecord(String nodeId, String modId, String worldUuid, double x, double y, double z, String kind, double radius, Set<String> tags, LuaTable state, LuaFunction onUpdate) {
            this.nodeId = nodeId;
            this.modId = modId;
            this.worldUuid = worldUuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.kind = kind;
            this.radius = radius;
            this.tags = tags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tags);
            this.state = state == null ? new LuaTable() : state;
            this.onUpdate = onUpdate;
        }

        private String nodeId() { return nodeId; }
        private String modId() { return modId; }
        private String worldUuid() { return worldUuid; }
        private double x() { return x; }
        private void x(double next) { this.x = next; }
        private double y() { return y; }
        private void y(double next) { this.y = next; }
        private double z() { return z; }
        private void z(double next) { this.z = next; }
        private String kind() { return kind; }
        private double radius() { return radius; }
        private Set<String> tags() { return tags; }
        private LuaTable state() { return state; }
        private void state(LuaTable next) { this.state = next == null ? new LuaTable() : next; }
        private LuaFunction onUpdate() { return onUpdate; }
    }

    private static final class LinkRecord {
        private final String linkId;
        private final String modId;
        private final String fromNodeId;
        private final String toNodeId;
        private final String kind;
        private final boolean directed;
        private final Set<String> tags;
        private final LuaTable state;

        private LinkRecord(String linkId, String modId, String fromNodeId, String toNodeId, String kind, boolean directed, Set<String> tags, LuaTable state) {
            this.linkId = linkId;
            this.modId = modId;
            this.fromNodeId = fromNodeId;
            this.toNodeId = toNodeId;
            this.kind = kind;
            this.directed = directed;
            this.tags = tags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tags);
            this.state = state == null ? new LuaTable() : state;
        }

        private String linkId() { return linkId; }
        private String modId() { return modId; }
        private String fromNodeId() { return fromNodeId; }
        private String toNodeId() { return toNodeId; }
        private String kind() { return kind; }
        private boolean directed() { return directed; }
        private Set<String> tags() { return tags; }
        private LuaTable state() { return state; }
    }
}
