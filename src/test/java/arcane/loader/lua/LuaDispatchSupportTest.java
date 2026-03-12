package arcane.loader.lua;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class LuaDispatchSupportTest {

    @Test
    void buildsCommandArgsAndNetworkEnvelope() {
        LuaTable args = LuaDispatchSupport.commandArgs(List.of("a", "b"));
        LuaTable envelope = LuaDispatchSupport.networkEnvelope("modA", "arcane.test", LuaValue.valueOf("payload"), 123L);

        assertEquals("a", args.get(1).tojstring());
        assertEquals("b", args.get(2).tojstring());
        assertEquals("arcane.test", envelope.get("channel").tojstring());
        assertEquals("modA", envelope.get("fromModId").tojstring());
        assertEquals("payload", envelope.get("payload").tojstring());
        assertEquals(123L, envelope.get("timestampMs").tolong());
        assertEquals("arcane.test", LuaDispatchSupport.normalizedNetworkChannel(" Arcane.Test "));
        assertNull(LuaDispatchSupport.normalizedNetworkChannel("   "));
    }

    @Test
    void dispatchCountsSuccessesAndInvokesFailureAndFinallyHandlers() {
        LuaMod alpha = mod("alpha");
        LuaMod beta = mod("beta");
        List<String> failures = new ArrayList<>();
        List<String> finished = new ArrayList<>();

        int delivered = LuaDispatchSupport.dispatch(
                List.of(alpha, beta),
                mod -> true,
                mod -> {
                    if (mod.manifest().id().equals("beta")) throw new IllegalStateException("boom");
                },
                (mod, error) -> failures.add(mod.manifest().id() + ":" + error.getClass().getSimpleName()),
                (mod, failed) -> finished.add(mod.manifest().id() + ":" + failed)
        );

        assertEquals(1, delivered);
        assertEquals(List.of("beta:IllegalStateException"), failures);
        assertEquals(List.of("alpha:false", "beta:true"), finished);
    }

    private static LuaMod mod(String id) {
        return new LuaMod(new ModManifest(id, id, "1.0.0", "init.lua", Set.of(), Set.of(), Path.of(id)));
    }
}
