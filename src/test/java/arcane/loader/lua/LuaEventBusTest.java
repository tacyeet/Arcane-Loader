package arcane.loader.lua;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LuaEventBusTest {

    @Test
    void emitUsesPriorityThenRegistrationOrder() {
        LuaEventBus bus = new LuaEventBus();
        List<String> calls = new ArrayList<>();

        bus.on("tick", recorder(calls, "normal-a"), LuaEventBus.PRIORITY_NORMAL, false, false);
        bus.on("tick", recorder(calls, "high"), LuaEventBus.PRIORITY_HIGH, false, false);
        bus.on("tick", recorder(calls, "normal-b"), LuaEventBus.PRIORITY_NORMAL, false, false);

        bus.emit("tick");

        assertEquals(List.of("high", "normal-a", "normal-b"), calls);
    }

    @Test
    void onceListenerIsRemovedAfterFirstEmit() {
        LuaEventBus bus = new LuaEventBus();
        List<String> calls = new ArrayList<>();

        bus.on("join", recorder(calls, "once"), LuaEventBus.PRIORITY_NORMAL, false, true);

        bus.emit("join");
        bus.emit("join");

        assertEquals(List.of("once"), calls);
        assertEquals(0, bus.listenerCount("join"));
    }

    @Test
    void cancelledPayloadSkipsIgnoreCancelledListeners() {
        LuaEventBus bus = new LuaEventBus();
        List<String> calls = new ArrayList<>();
        LuaTable payload = new LuaTable();
        payload.set("cancelled", LuaValue.TRUE);

        bus.on("chat", recorder(calls, "skip-on-cancel"), LuaEventBus.PRIORITY_NORMAL, true, false);
        bus.on("chat", recorder(calls, "still-runs"), LuaEventBus.PRIORITY_NORMAL, false, false);

        bus.emit("chat", payload);

        assertEquals(List.of("still-runs"), calls);
    }

    private static LuaValue recorder(List<String> calls, String label) {
        return new ZeroArgFunction() {
            @Override public LuaValue call() {
                calls.add(label);
                return LuaValue.NIL;
            }
        };
    }
}
