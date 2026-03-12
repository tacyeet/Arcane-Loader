package arcane.loader.lua;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LuaLifecyclePlannerTest {

    @Test
    void enableAllTargetsOnlyNonEnabledModsInOrder() {
        LuaMod alpha = mod("alpha", LuaModState.DISCOVERED);
        LuaMod beta = mod("beta", LuaModState.ENABLED);
        LuaMod gamma = mod("gamma", LuaModState.ERROR);

        List<LuaLifecyclePlanner.Action> actions = LuaLifecyclePlanner.planEnableAll(List.of(alpha, beta, gamma));

        assertEquals(List.of(
                new LuaLifecyclePlanner.Action(LuaLifecyclePlanner.Type.ENABLE, alpha),
                new LuaLifecyclePlanner.Action(LuaLifecyclePlanner.Type.ENABLE, gamma)
        ), actions);
    }

    @Test
    void disableAllTargetsEnabledModsInReverseOrder() {
        LuaMod alpha = mod("alpha", LuaModState.ENABLED);
        LuaMod beta = mod("beta", LuaModState.DISABLED);
        LuaMod gamma = mod("gamma", LuaModState.ENABLED);

        List<LuaLifecyclePlanner.Action> actions = LuaLifecyclePlanner.planDisableAll(List.of(alpha, beta, gamma));

        assertEquals(List.of(
                new LuaLifecyclePlanner.Action(LuaLifecyclePlanner.Type.DISABLE, gamma),
                new LuaLifecyclePlanner.Action(LuaLifecyclePlanner.Type.DISABLE, alpha)
        ), actions);
    }

    @Test
    void reloadAllReloadsEnabledAndEnablesEverythingElse() {
        LuaMod alpha = mod("alpha", LuaModState.ENABLED);
        LuaMod beta = mod("beta", LuaModState.ERROR);
        LuaMod gamma = mod("gamma", LuaModState.DISCOVERED);

        List<LuaLifecyclePlanner.Action> actions = LuaLifecyclePlanner.planReloadAll(List.of(alpha, beta, gamma));

        assertEquals(List.of(
                new LuaLifecyclePlanner.Action(LuaLifecyclePlanner.Type.RELOAD, alpha),
                new LuaLifecyclePlanner.Action(LuaLifecyclePlanner.Type.ENABLE, beta),
                new LuaLifecyclePlanner.Action(LuaLifecyclePlanner.Type.ENABLE, gamma)
        ), actions);
    }

    private static LuaMod mod(String id, LuaModState state) {
        LuaMod mod = new LuaMod(new ModManifest(id, id, "1.0.0", "init.lua", Set.of(), Set.of(), Path.of(id)));
        mod.state(state);
        return mod;
    }
}
