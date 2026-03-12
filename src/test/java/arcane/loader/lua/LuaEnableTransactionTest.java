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
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaEnableTransactionTest {

    @Test
    void commitAppliesLoadedState() {
        LuaMod mod = new LuaMod(new ModManifest("test", "Test", "1.0.0", "init.lua", Set.of(), Set.of(), Path.of("test")));
        Globals globals = JsePlatform.standardGlobals();
        LuaTable module = new LuaTable();
        LuaEnableTransaction tx = new LuaEnableTransaction(mod, new LuaLoadedInstance(globals, module, null));

        tx.markLoaded();
        assertEquals(LuaModState.LOADED, mod.state());

        tx.commit(77L);
        assertSame(globals, mod.globals());
        assertSame(module, mod.module());
        assertEquals(77L, mod.lastLoadEpochMs());
        assertEquals(LuaModState.ENABLED, mod.state());
        assertNull(mod.lastError());
    }

    @Test
    void failRecordsRuntimeErrorAndMovesToErrorState() {
        LuaMod mod = new LuaMod(new ModManifest("test", "Test", "1.0.0", "init.lua", Set.of(), Set.of(), Path.of("test")));
        LuaEnableTransaction tx = new LuaEnableTransaction(mod, new LuaLoadedInstance(JsePlatform.standardGlobals(), new LuaTable(), null));

        tx.fail("Failed enabling", new IllegalStateException("boom"));

        assertEquals(LuaModState.ERROR, mod.state());
        assertTrue(mod.lastError().contains("Failed enabling: java.lang.IllegalStateException: boom"));
        assertTrue(mod.lastErrorDetail().contains("IllegalStateException"));
    }
}
