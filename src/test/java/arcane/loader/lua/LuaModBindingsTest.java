package arcane.loader.lua;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaModBindingsTest {

    @Test
    void commandsAndEventsAreRegisteredAndDispatched() {
        LuaModBindings bindings = new LuaModBindings();
        List<String> events = new ArrayList<>();

        bindings.command("ping", "desc", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue sender, LuaValue args) {
                return LuaValue.valueOf(args.checktable().length());
            }
        });
        bindings.on("tick", new ZeroArgFunction() {
            @Override public LuaValue call() {
                events.add("tick");
                return LuaValue.NIL;
            }
        }, LuaEventBus.PRIORITY_NORMAL, false, false);

        LuaTable args = new LuaTable();
        args.set(1, "a");
        args.set(2, "b");

        assertEquals(2, bindings.invokeCommand("ping", null, args).toint());
        assertTrue(bindings.commandNames().contains("ping"));
        assertEquals("desc", bindings.commandHelp("ping"));

        bindings.emit("tick");
        assertEquals(List.of("tick"), events);
        assertEquals(1, bindings.eventListenerCount("tick"));

        bindings.unregisterCommand("ping");
        bindings.clearEvent("tick");
        assertFalse(bindings.commandNames().contains("ping"));
        assertEquals(0, bindings.eventListenerCount("tick"));
    }
}
