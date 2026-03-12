package arcane.loader.lua;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

final class LuaModuleHooksTest {

    @Test
    void missingHookDoesNothing() {
        LuaTable module = new LuaTable();

        assertDoesNotThrow(() -> LuaModuleHooks.callOptional(module, "onEnable", null));
    }

    @Test
    void existingHookReceivesContextUserdata() {
        LuaTable module = new LuaTable();
        AtomicReference<Object> received = new AtomicReference<>();
        Object ctx = new Object();

        module.set("onEnable", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                received.set(arg.touserdata());
                return LuaValue.NIL;
            }
        });

        LuaModuleHooks.callOptional(module, "onEnable", ctx);

        assertSame(ctx, received.get());
    }
}
