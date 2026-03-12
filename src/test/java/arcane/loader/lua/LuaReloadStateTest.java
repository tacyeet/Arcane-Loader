package arcane.loader.lua;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class LuaReloadStateTest {

    @Test
    void restorePutsBackCapturedRuntimeInstanceAndState() {
        LuaMod mod = new LuaMod(new ModManifest("test", "Test", "1.0.0", "init.lua", Set.of(), Set.of(), Path.of("test")));
        Globals originalGlobals = JsePlatform.standardGlobals();
        LuaTable originalModule = new LuaTable();
        originalModule.set("name", "original");
        mod.globals(originalGlobals);
        mod.module(originalModule);
        mod.ctx(null);
        mod.state(LuaModState.ENABLED);

        LuaReloadState captured = LuaReloadState.capture(mod);

        mod.globals(JsePlatform.standardGlobals());
        mod.module(new LuaTable());
        mod.state(LuaModState.ERROR);

        captured.restore(mod);

        assertSame(originalGlobals, mod.globals());
        assertSame(originalModule, mod.module());
        assertEquals(LuaModState.ENABLED, mod.state());
    }
}
