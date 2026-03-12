package arcane.loader.lua;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaModCatalogPlannerTest {

    @Test
    void planRemovesMissingAndReplacesNonEnabledMods() {
        LuaMod enabled = new LuaMod(manifest("enabled"));
        enabled.state(LuaModState.ENABLED);
        LuaMod disabled = new LuaMod(manifest("disabled"));
        disabled.state(LuaModState.DISABLED);
        LuaMod stale = new LuaMod(manifest("stale"));
        stale.state(LuaModState.ERROR);

        Map<String, LuaMod> current = new LinkedHashMap<>();
        current.put("enabled", enabled);
        current.put("disabled", disabled);
        current.put("stale", stale);

        Map<String, ModManifest> discovered = new LinkedHashMap<>();
        discovered.put("enabled", manifest("enabled"));
        discovered.put("disabled", manifest("disabled"));
        discovered.put("fresh", manifest("fresh"));

        LuaModCatalogPlanner.Plan plan = LuaModCatalogPlanner.plan(current, discovered);

        assertEquals(Set.of("stale"), plan.removals());
        assertEquals(Set.of("disabled", "fresh"), plan.replacements().keySet());
        assertFalse(plan.replacements().containsKey("enabled"));
    }

    @Test
    void planHandlesEmptyInputs() {
        LuaModCatalogPlanner.Plan plan = LuaModCatalogPlanner.plan(Map.of(), Map.of());

        assertTrue(plan.removals().isEmpty());
        assertTrue(plan.replacements().isEmpty());
    }

    private static ModManifest manifest(String id) {
        return new ModManifest(id, id, "1.0.0", "init.lua", Set.of(), Set.of(), Path.of(id));
    }
}
