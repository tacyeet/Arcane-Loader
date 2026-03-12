package arcane.loader.lua;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LuaRegistryRuntime {
    private final Map<String, Map<String, RegistryRecord>> registriesByMod = new ConcurrentHashMap<>();

    public Map<String, Object> define(LuaModContext ctx, String registryId, Map<String, Object> options) {
        if (ctx == null) return null;
        String modId = normalize(ctx.modId());
        String id = normalize(registryId);
        if (modId == null) return null;
        if (id == null) id = normalize("registry-" + UUID.randomUUID());
        String kind = options == null ? "registry" : normalize(String.valueOf(options.getOrDefault("kind", "registry")));
        Map<String, Object> defaults = options == null ? Collections.emptyMap() : copyMap(options.get("defaults"));
        Map<String, RegistryRecord> byId = registriesByMod.computeIfAbsent(modId, ignored -> new ConcurrentHashMap<>());
        RegistryRecord existing = byId.get(id);
        boolean redefined = existing != null;
        Map<String, Object> entries = existing == null ? new LinkedHashMap<>() : existing.entries();
        RegistryRecord record = new RegistryRecord(id, modId, kind == null ? "registry" : kind, defaults, entries);
        byId.put(id, record);
        LinkedHashMap<String, Object> out = toMap(record);
        if (redefined) out.put("redefined", true);
        return out;
    }

    public Map<String, Object> find(LuaModContext ctx, String registryId) {
        RegistryRecord record = findRecord(ctx == null ? null : ctx.modId(), registryId);
        return record == null ? null : toMap(record);
    }

    public List<Map<String, Object>> list(LuaModContext ctx) {
        if (ctx == null) return List.of();
        Map<String, RegistryRecord> registries = registriesByMod.get(normalize(ctx.modId()));
        if (registries == null || registries.isEmpty()) return List.of();
        ArrayList<RegistryRecord> ordered = new ArrayList<>(registries.values());
        ordered.sort((a, b) -> a.registryId().compareToIgnoreCase(b.registryId()));
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (RegistryRecord record : ordered) out.add(Collections.unmodifiableMap(toMap(record)));
        return Collections.unmodifiableList(out);
    }

    public List<Map<String, Object>> listByKind(LuaModContext ctx, String kind) {
        if (ctx == null) return List.of();
        String normalizedKind = normalize(kind);
        if (normalizedKind == null) return List.of();
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : list(ctx)) {
            if (normalizedKind.equals(normalize(String.valueOf(row.get("kind"))))) out.add(row);
        }
        return Collections.unmodifiableList(out);
    }

    public boolean has(String modId, String registryId, String key) {
        RegistryRecord record = findRecord(modId, registryId);
        String normalizedKey = normalize(key);
        if (record == null || normalizedKey == null) return false;
        return record.entries().containsKey(normalizedKey);
    }

    public int size(String modId, String registryId) {
        RegistryRecord record = findRecord(modId, registryId);
        return record == null ? 0 : record.entries().size();
    }

    public List<String> kinds(LuaModContext ctx) {
        if (ctx == null) return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (Map<String, Object> row : list(ctx)) {
            Object kind = row.get("kind");
            if (kind == null) continue;
            String normalized = String.valueOf(kind);
            if (!out.contains(normalized)) out.add(normalized);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return Collections.unmodifiableList(out);
    }

    public boolean remove(String modId, String registryId) {
        Map<String, RegistryRecord> registries = registriesByMod.get(normalize(modId));
        if (registries == null) return false;
        RegistryRecord removed = registries.remove(normalize(registryId));
        if (registries.isEmpty()) registriesByMod.remove(normalize(modId), registries);
        return removed != null;
    }

    public int clear(String modId) {
        Map<String, RegistryRecord> removed = registriesByMod.remove(normalize(modId));
        return removed == null ? 0 : removed.size();
    }

    public boolean put(String modId, String registryId, String key, Object value) {
        RegistryRecord record = findRecord(modId, registryId);
        String normalizedKey = normalize(key);
        if (record == null || normalizedKey == null) return false;
        Map<String, Object> merged = mergeMaps(record.defaults(), value);
        record.entries().put(normalizedKey, merged == null ? value : merged);
        return true;
    }

    public Object get(String modId, String registryId, String key) {
        RegistryRecord record = findRecord(modId, registryId);
        String normalizedKey = normalize(key);
        if (record == null || normalizedKey == null) return null;
        return record.entries().get(normalizedKey);
    }

    public boolean removeEntry(String modId, String registryId, String key) {
        RegistryRecord record = findRecord(modId, registryId);
        String normalizedKey = normalize(key);
        if (record == null || normalizedKey == null) return false;
        return record.entries().remove(normalizedKey) != null;
    }

    public List<String> keys(String modId, String registryId) {
        RegistryRecord record = findRecord(modId, registryId);
        if (record == null || record.entries().isEmpty()) return List.of();
        ArrayList<String> keys = new ArrayList<>(record.entries().keySet());
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        return Collections.unmodifiableList(keys);
    }

    public List<Map<String, Object>> entries(String modId, String registryId) {
        RegistryRecord record = findRecord(modId, registryId);
        if (record == null || record.entries().isEmpty()) return List.of();
        ArrayList<String> keys = new ArrayList<>(record.entries().keySet());
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (String key : keys) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("value", record.entries().get(key));
            out.add(Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableList(out);
    }

    private RegistryRecord findRecord(String modId, String registryId) {
        Map<String, RegistryRecord> registries = registriesByMod.get(normalize(modId));
        if (registries == null) return null;
        return registries.get(normalize(registryId));
    }

    private static LinkedHashMap<String, Object> toMap(RegistryRecord record) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("registryId", record.registryId());
        out.put("kind", record.kind());
        out.put("defaults", new LinkedHashMap<>(record.defaults()));
        out.put("size", record.entries().size());
        return out;
    }

    private static Map<String, Object> copyMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Collections.emptyMap();
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (var ent : map.entrySet()) {
            if (ent.getKey() == null) continue;
            out.put(String.valueOf(ent.getKey()), ent.getValue());
        }
        return out;
    }

    private static Map<String, Object> mergeMaps(Map<String, Object> defaults, Object value) {
        if (!(value instanceof Map<?, ?> map)) return defaults.isEmpty() ? null : new LinkedHashMap<>(defaults);
        LinkedHashMap<String, Object> out = new LinkedHashMap<>(defaults);
        for (var ent : map.entrySet()) {
            if (ent.getKey() == null) continue;
            String key = String.valueOf(ent.getKey());
            Object current = ent.getValue();
            if (current instanceof Map<?, ?> nested && out.get(key) instanceof Map<?, ?> existing) {
                LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
                for (var oldEnt : existing.entrySet()) {
                    if (oldEnt.getKey() != null) merged.put(String.valueOf(oldEnt.getKey()), oldEnt.getValue());
                }
                for (var newEnt : nested.entrySet()) {
                    if (newEnt.getKey() != null) merged.put(String.valueOf(newEnt.getKey()), newEnt.getValue());
                }
                out.put(key, merged);
            } else {
                out.put(key, current);
            }
        }
        return out;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private record RegistryRecord(
            String registryId,
            String modId,
            String kind,
            Map<String, Object> defaults,
            Map<String, Object> entries
    ) {}
}
