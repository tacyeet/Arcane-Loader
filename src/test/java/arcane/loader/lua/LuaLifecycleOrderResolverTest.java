package arcane.loader.lua;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaLifecycleOrderResolverTest {

    @Test
    void ordersByLoadAfterAndLoadBeforeHints() {
        Map<String, ModManifest> manifests = new LinkedHashMap<>();
        manifests.put("core", manifest("core", Set.of(), Set.of()));
        manifests.put("chat", manifest("chat", Set.of(), Set.of("core")));
        manifests.put("ui", manifest("ui", Set.of("chat"), Set.of()));

        LuaLifecycleOrderResolver.Resolution resolution = LuaLifecycleOrderResolver.resolve(manifests);

        assertEquals(List.of("core", "ui", "chat"), resolution.orderedIds());
        assertTrue(resolution.unresolvedIds().isEmpty());
    }

    @Test
    void cyclesFallBackToStableIdOrderForUnresolvedSet() {
        Map<String, ModManifest> manifests = new LinkedHashMap<>();
        manifests.put("beta", manifest("beta", Set.of(), Set.of("gamma")));
        manifests.put("gamma", manifest("gamma", Set.of(), Set.of("beta")));
        manifests.put("alpha", manifest("alpha", Set.of(), Set.of()));

        LuaLifecycleOrderResolver.Resolution resolution = LuaLifecycleOrderResolver.resolve(manifests);

        assertEquals(List.of("alpha", "beta", "gamma"), resolution.orderedIds());
        assertEquals(Set.of("beta", "gamma"), resolution.unresolvedIds());
    }

    private static ModManifest manifest(String id, Set<String> loadBefore, Set<String> loadAfter) {
        return new ModManifest(id, id, "1.0.0", "init.lua", loadBefore, loadAfter, Path.of(id));
    }
}
