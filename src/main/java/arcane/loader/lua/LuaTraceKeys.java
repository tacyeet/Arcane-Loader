package arcane.loader.lua;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class LuaTraceKeys {
    private final Set<String> keys = new LinkedHashSet<>();

    boolean add(String key) {
        String normalized = normalize(key);
        return normalized != null && keys.add(normalized);
    }

    boolean remove(String key) {
        String normalized = normalize(key);
        return normalized != null && keys.remove(normalized);
    }

    void clear() {
        keys.clear();
    }

    boolean contains(String key) {
        String normalized = normalize(key);
        return normalized != null && keys.contains(normalized);
    }

    Set<String> snapshot() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(keys));
    }

    static String normalize(String key) {
        if (key == null) return null;
        String normalized = key.trim().toLowerCase();
        return normalized.isEmpty() ? null : normalized;
    }
}
