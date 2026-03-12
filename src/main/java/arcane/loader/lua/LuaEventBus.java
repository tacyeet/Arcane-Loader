package arcane.loader.lua;

import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-mod in-memory event bus for Lua handlers.
 */
public final class LuaEventBus {
    public static final int PRIORITY_LOWEST = -200;
    public static final int PRIORITY_LOW = -100;
    public static final int PRIORITY_NORMAL = 0;
    public static final int PRIORITY_HIGH = 100;
    public static final int PRIORITY_HIGHEST = 200;
    public static final int PRIORITY_MONITOR = 300;

    private final ConcurrentHashMap<String, List<Listener>> listeners = new ConcurrentHashMap<>();
    private final AtomicLong nextOrder = new AtomicLong(0L);

    public void on(String event, LuaValue fn) {
        on(event, fn, PRIORITY_NORMAL, false, false);
    }

    public void on(String event, LuaValue fn, int priority, boolean ignoreCancelled, boolean once) {
        if (event == null || event.isBlank() || fn == null) return;
        listeners.compute(event, (k, v) -> {
            if (v == null) v = Collections.synchronizedList(new ArrayList<>());
            synchronized (v) {
                v.add(new Listener(fn, normalizePriority(priority), ignoreCancelled, once, nextOrder.getAndIncrement()));
            }
            return v;
        });
    }

    public int off(String event, LuaValue fn) {
        if (event == null || event.isBlank()) return 0;
        if (fn == null) return clear(event);

        List<Listener> list = listeners.get(event);
        if (list == null || list.isEmpty()) return 0;
        int removed = 0;
        synchronized (list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).fn().raweq(fn)) {
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
        List<Listener> removed = listeners.remove(event);
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
        List<Listener> list = listeners.get(event);
        if (list == null) return 0;
        synchronized (list) {
            return list.size();
        }
    }

    public int totalListenerCount() {
        int total = 0;
        for (List<Listener> list : listeners.values()) {
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

    public List<Map<String, Object>> listenerInfo(String event) {
        if (event == null || event.isBlank()) return List.of();
        List<Listener> list = listeners.get(event);
        if (list == null || list.isEmpty()) return List.of();
        ArrayList<Listener> snapshot;
        synchronized (list) {
            snapshot = new ArrayList<>(list);
        }
        snapshot.sort(DISPATCH_ORDER);
        ArrayList<Map<String, Object>> out = new ArrayList<>(snapshot.size());
        for (Listener listener : snapshot) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("priority", priorityName(listener.priority()));
            item.put("priorityValue", listener.priority());
            item.put("ignoreCancelled", listener.ignoreCancelled());
            item.put("once", listener.once());
            item.put("order", listener.order());
            out.add(item);
        }
        return Collections.unmodifiableList(out);
    }

    public void emit(String event, LuaValue... args) {
        List<Listener> list = listeners.get(event);
        if (list == null || list.isEmpty()) return;

        Listener[] snapshot;
        synchronized (list) {
            snapshot = list.toArray(new Listener[0]);
        }
        java.util.Arrays.sort(snapshot, DISPATCH_ORDER);
        Throwable firstError = null;
        boolean cancelled = isCancelled(args);
        for (Listener listener : snapshot) {
            if (cancelled && listener.ignoreCancelled()) continue;
            try {
                listener.fn().invoke(LuaValue.varargsOf(args));
            } catch (Throwable t) {
                // Keep dispatching remaining handlers, then surface failure to caller.
                if (firstError == null) firstError = t;
            } finally {
                if (listener.once()) {
                    off(event, listener.fn());
                }
            }
        }
        if (firstError != null) {
            throw new RuntimeException(firstError);
        }
    }

    private static boolean isCancelled(LuaValue[] args) {
        if (args == null || args.length == 0) return false;
        LuaValue payload = args[0];
        if (payload == null || !payload.istable()) return false;
        LuaValue isCancelled = payload.get("isCancelled");
        if (isCancelled.isfunction()) {
            try {
                return isCancelled.call(payload).optboolean(false);
            } catch (Throwable ignored) { }
        }
        LuaValue cancelled = payload.get("cancelled");
        if (cancelled.isboolean()) return cancelled.toboolean();
        LuaValue canceled = payload.get("canceled");
        return canceled.isboolean() && canceled.toboolean();
    }

    private static int normalizePriority(int priority) {
        return Math.max(PRIORITY_LOWEST, Math.min(PRIORITY_MONITOR, priority));
    }

    public static int priorityFromName(String raw) {
        if (raw == null || raw.isBlank()) return PRIORITY_NORMAL;
        return switch (raw.trim().toUpperCase()) {
            case "LOWEST" -> PRIORITY_LOWEST;
            case "LOW" -> PRIORITY_LOW;
            case "HIGH" -> PRIORITY_HIGH;
            case "HIGHEST" -> PRIORITY_HIGHEST;
            case "MONITOR" -> PRIORITY_MONITOR;
            default -> PRIORITY_NORMAL;
        };
    }

    public static String priorityName(int priority) {
        return switch (normalizePriority(priority)) {
            case PRIORITY_LOWEST -> "LOWEST";
            case PRIORITY_LOW -> "LOW";
            case PRIORITY_HIGH -> "HIGH";
            case PRIORITY_HIGHEST -> "HIGHEST";
            case PRIORITY_MONITOR -> "MONITOR";
            default -> "NORMAL";
        };
    }

    private static final Comparator<Listener> DISPATCH_ORDER = Comparator
            .comparingInt(Listener::priority).reversed()
            .thenComparingLong(Listener::order);

    private record Listener(LuaValue fn, int priority, boolean ignoreCancelled, boolean once, long order) {}
}
