package arcane.loader.lua;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaLifecycleOperationsTest {

    @Test
    void enableLoadsCommitsAndLogs() {
        LuaMod mod = mod("enable");
        Globals globals = JsePlatform.standardGlobals();
        LuaTable module = new LuaTable();
        AtomicBoolean enabled = new AtomicBoolean();
        module.set("onEnable", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                enabled.set(true);
                return LuaValue.NIL;
            }
        });
        ArrayList<String> info = new ArrayList<>();
        ArrayList<String> warn = new ArrayList<>();

        boolean success = LuaLifecycleOperations.enable(
                mod,
                manifest -> new LuaLoadedInstance(globals, module, null),
                LuaRuntimeErrors::record,
                info::add,
                warn::add
        );

        assertTrue(success);
        assertTrue(enabled.get());
        assertSame(globals, mod.globals());
        assertSame(module, mod.module());
        assertEquals(LuaModState.ENABLED, mod.state());
        assertEquals(List.of("Enabled Lua mod: enable"), info);
        assertTrue(warn.isEmpty());
    }

    @Test
    void reloadFailureRollsBackPreviousInstanceAndRecordsError() {
        LuaMod mod = mod("reload");
        Globals previousGlobals = JsePlatform.standardGlobals();
        LuaTable previousModule = new LuaTable();
        mod.globals(previousGlobals);
        mod.module(previousModule);
        mod.state(LuaModState.ENABLED);

        ArrayList<String> warns = new ArrayList<>();
        boolean success = LuaLifecycleOperations.reload(
                mod,
                manifest -> {
                    LuaTable nextModule = new LuaTable();
                    nextModule.set("onEnable", new OneArgFunction() {
                        @Override public LuaValue call(LuaValue arg) {
                            throw new LuaError("boom");
                        }
                    });
                    return new LuaLoadedInstance(JsePlatform.standardGlobals(), nextModule, null);
                },
                LuaRuntimeErrors::record,
                message -> {
                },
                warns::add
        );

        assertFalse(success);
        assertSame(previousGlobals, mod.globals());
        assertSame(previousModule, mod.module());
        assertEquals(LuaModState.ENABLED, mod.state());
        assertTrue(mod.lastError().contains("Failed reloading (kept last-known-good)"));
        assertEquals(1, warns.size());
    }

    private static LuaMod mod(String id) {
        return new LuaMod(new ModManifest(id, id, "1.0.0", "init.lua", Set.of(), Set.of(), Path.of(id)));
    }
}
