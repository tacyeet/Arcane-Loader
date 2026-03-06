package arcane.loader.lua;

import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-mod in-memory event bus for Lua handlers.
 */
public final class LuaEventBus {

    private final ConcurrentHashMap<String, List<LuaValue>> listeners = new ConcurrentHashMap<>();

    public void on(String event, LuaValue fn) {
        listeners.compute(event, (k, v) -> {
            if (v == null) v = Collections.synchronizedList(new ArrayList<>());
            synchronized (v) {
                v.add(fn);
            }
            return v;
        });
    }

    public int off(String event, LuaValue fn) {
        if (event == null || event.isBlank()) return 0;
        if (fn == null) return clear(event);

        List<LuaValue> list = listeners.get(event);
        if (list == null || list.isEmpty()) return 0;
        int removed = 0;
        synchronized (list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).raweq(fn)) {
                    list.remove(i);
                    removed++;
                }
            }
            if (list.isEmpty()) {
                listeners.remove(event, list);
            }
        }
        return removed;
    }

    public int clear(String event) {
        if (event == null || event.isBlank()) return 0;
        List<LuaValue> removed = listeners.remove(event);
        if (removed == null) return 0;
        synchronized (removed) {
            int count = removed.size();
            removed.clear();
            return count;
        }
    }

    public Set<String> eventNames() {
        return new TreeSet<>(listeners.keySet());
    }

    public int listenerCount(String event) {
        if (event == null || event.isBlank()) return 0;
        List<LuaValue> list = listeners.get(event);
        if (list == null) return 0;
        synchronized (list) {
            return list.size();
        }
    }

    public int totalListenerCount() {
        int total = 0;
        for (List<LuaValue> list : listeners.values()) {
            if (list == null) continue;
            synchronized (list) {
                total += list.size();
            }
        }
        return total;
    }

    public void clearAll() {
        listeners.clear();
    }

    public void emit(String event, LuaValue... args) {
        List<LuaValue> list = listeners.get(event);
        if (list == null || list.isEmpty()) return;

        LuaValue[] snapshot;
        synchronized (list) {
            snapshot = list.toArray(new LuaValue[0]);
        }
        Throwable firstError = null;
        for (LuaValue fn : snapshot) {
            try {
                fn.invoke(LuaValue.varargsOf(args));
            } catch (Throwable t) {
                // Keep dispatching remaining handlers, then surface failure to caller.
                if (firstError == null) firstError = t;
            }
        }
        if (firstError != null) {
            throw new RuntimeException(firstError);
        }
    }
}
