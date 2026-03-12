package arcane.loader.lua;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class LuaModCatalogPlanner {
    private LuaModCatalogPlanner() {}

    static Plan plan(Map<String, LuaMod> currentMods, Map<String, ModManifest> discoveredManifests) {
        TreeSet<String> removals = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        LinkedHashMap<String, ModManifest> swaps = new LinkedHashMap<>();

        for (Map.Entry<String, LuaMod> slot : currentMods.entrySet()) {
            if (!discoveredManifests.containsKey(slot.getKey())) {
                removals.add(slot.getKey());
            }
        }

        for (Map.Entry<String, ModManifest> slot : discoveredManifests.entrySet()) {
            LuaMod existing = currentMods.get(slot.getKey());
            if (existing == null || existing.state() != LuaModState.ENABLED) {
                swaps.put(slot.getKey(), slot.getValue());
            }
        }

        return new Plan(
                Collections.unmodifiableSet(removals),
                Collections.unmodifiableMap(swaps)
        );
    }

    record Plan(Set<String> removals, Map<String, ModManifest> replacements) {}
}
