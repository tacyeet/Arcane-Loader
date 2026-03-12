package arcane.loader.lua;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class LuaLiveModStateTest {

    @Test
    void applyLoadedInstanceReplacesRuntimeStateAndClearsErrors() {
        LuaMod mod = new LuaMod(new ModManifest("test", "Test", "1.0.0", "init.lua", Set.of(), Set.of(), Path.of("test")));
        mod.lastError("old error");
        mod.lastErrorDetail("old detail");
        mod.state(LuaModState.ERROR);

        Globals globals = JsePlatform.standardGlobals();
        LuaTable module = new LuaTable();
        LuaLoadedInstance instance = new LuaLoadedInstance(globals, module, null);

        LuaLiveModState.applyLoadedInstance(mod, instance, 1234L);

        assertSame(globals, mod.globals());
        assertSame(module, mod.module());
        assertNull(mod.lastError());
        assertNull(mod.lastErrorDetail());
        assertEquals(1234L, mod.lastLoadEpochMs());
        assertEquals(LuaModState.ENABLED, mod.state());
    }
}
