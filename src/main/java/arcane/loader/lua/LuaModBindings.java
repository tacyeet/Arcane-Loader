package arcane.loader.lua;

import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LuaModBindings {
    private final Map<String, LuaFunction> commands = new LinkedHashMap<>();
    private final Map<String, String> commandHelp = new LinkedHashMap<>();
    private final LuaEventBus events = new LuaEventBus();

    void command(String name, String help, LuaFunction fn) {
        if (name == null || name.isBlank() || fn == null) return;
        commands.put(name, fn);
        commandHelp.put(name, help == null ? "" : help);
    }

    void unregisterCommand(String name) {
        if (name == null || name.isBlank()) return;
        commands.remove(name);
        commandHelp.remove(name);
    }

    Set<String> commandNames() {
        return Collections.unmodifiableSet(commands.keySet());
    }

    String commandHelp(String name) {
        return commandHelp.getOrDefault(name, "");
    }

    LuaValue invokeCommand(String name, LuaSender sender, LuaTable args) {
        LuaFunction fn = commands.get(name);
        if (fn == null) return LuaValue.NIL;
        return fn.call(LuaValue.userdataOf(sender), args);
    }

    void on(String event, LuaFunction fn, int priority, boolean ignoreCancelled, boolean once) {
        if (event == null || event.isBlank() || fn == null) return;
        events.on(event, fn, priority, ignoreCancelled, once);
    }

    int off(String event, LuaFunction fn) {
        if (event == null || event.isBlank()) return 0;
        return events.off(event, fn);
    }

    int clearEvent(String event) {
        if (event == null || event.isBlank()) return 0;
        return events.clear(event);
    }

    Set<String> eventNames() {
        return events.eventNames();
    }

    int eventListenerCount(String event) {
        return events.listenerCount(event);
    }

    int totalEventListeners() {
        return events.totalListenerCount();
    }

    List<Map<String, Object>> eventListeners(String event) {
        return events.listenerInfo(event);
    }

    void emit(String event, LuaValue... args) {
        events.emit(event, args);
    }

    void clearAll() {
        commands.clear();
        commandHelp.clear();
        events.clearAll();
    }
}
