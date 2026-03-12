package arcane.loader.lua;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

final class LuaLifecycleOrderResolver {

    private LuaLifecycleOrderResolver() {}

    static Resolution resolve(Map<String, ModManifest> manifestsById) {
        if (manifestsById == null || manifestsById.isEmpty()) {
            return new Resolution(List.of(), Set.of());
        }
        if (manifestsById.size() == 1) {
            return new Resolution(List.copyOf(manifestsById.keySet()), Set.of());
        }

        Map<String, Set<String>> graph = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String id : manifestsById.keySet()) {
            graph.put(id, new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
            indegree.put(id, 0);
        }

        for (ModManifest manifest : manifestsById.values()) {
            String src = manifest.id();
            for (String before : manifest.loadBefore()) {
                String dst = findCanonicalId(manifestsById.keySet(), before);
                if (dst == null || dst.equalsIgnoreCase(src)) continue;
                if (graph.get(src).add(dst)) {
                    indegree.put(dst, indegree.get(dst) + 1);
                }
            }
            for (String after : manifest.loadAfter()) {
                String prev = findCanonicalId(manifestsById.keySet(), after);
                if (prev == null || prev.equalsIgnoreCase(src)) continue;
                if (graph.get(prev).add(src)) {
                    indegree.put(src, indegree.get(src) + 1);
                }
            }
        }

        PriorityQueue<String> queue = new PriorityQueue<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Integer> ent : indegree.entrySet()) {
            if (ent.getValue() == 0) queue.add(ent.getKey());
        }

        ArrayList<String> orderedIds = new ArrayList<>(manifestsById.size());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            orderedIds.add(id);
            for (String next : graph.get(id)) {
                int d = indegree.get(next) - 1;
                indegree.put(next, d);
                if (d == 0) queue.add(next);
            }
        }

        TreeSet<String> unresolved = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (orderedIds.size() != manifestsById.size()) {
            for (String id : manifestsById.keySet()) {
                if (!orderedIds.contains(id)) unresolved.add(id);
            }
            orderedIds.addAll(unresolved);
        }

        return new Resolution(Collections.unmodifiableList(orderedIds), Collections.unmodifiableSet(unresolved));
    }

    private static String findCanonicalId(Set<String> ids, String requestedId) {
        if (requestedId == null || requestedId.isBlank()) return null;
        for (String id : ids) {
            if (id.equalsIgnoreCase(requestedId)) return id;
        }
        return null;
    }

    record Resolution(List<String> orderedIds, Set<String> unresolvedIds) {}

    static Map<String, ModManifest> manifestsById(Iterable<LuaMod> mods) {
        LinkedHashMap<String, ModManifest> manifests = new LinkedHashMap<>();
        for (LuaMod mod : mods) {
            manifests.put(mod.manifest().id(), mod.manifest());
        }
        return manifests;
    }
}
