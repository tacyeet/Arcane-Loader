package arcane.loader.lua;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class LuaReloadTransactionTest {

    @Test
    void markLoadedCommitAndRollbackManageModState() {
        LuaMod mod = new LuaMod(new ModManifest("test", "Test", "1.0.0", "init.lua", Set.of(), Set.of(), Path.of("test")));
        Globals oldGlobals = JsePlatform.standardGlobals();
        LuaTable oldModule = new LuaTable();
        mod.globals(oldGlobals);
        mod.module(oldModule);
        mod.state(LuaModState.ENABLED);

        LuaReloadState previous = LuaReloadState.capture(mod);
        Globals nextGlobals = JsePlatform.standardGlobals();
        LuaTable nextModule = new LuaTable();
        LuaLoadedInstance next = new LuaLoadedInstance(nextGlobals, nextModule, null);
        LuaReloadTransaction tx = new LuaReloadTransaction(mod, previous, next);

        tx.markLoaded();
        assertEquals(LuaModState.LOADED, mod.state());

        tx.commit(99L);
        assertSame(nextGlobals, mod.globals());
        assertSame(nextModule, mod.module());
        assertEquals(LuaModState.ENABLED, mod.state());
        assertEquals(99L, mod.lastLoadEpochMs());

        tx.rollback();
        assertSame(oldGlobals, mod.globals());
        assertSame(oldModule, mod.module());
        assertEquals(LuaModState.ENABLED, mod.state());
    }
}
