package arcane.loader.lua;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

final class LuaManifestParser {
    private LuaManifestParser() {}

    static ModManifest parse(Path dir, Path manifestPath) throws IOException {
        String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
        return parseJson(dir, json);
    }

    static ModManifest parseJson(Path dir, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String id = readString(root, "id", null);
        if (id == null || id.isBlank()) throw new IllegalArgumentException("manifest missing id");
        String name = readString(root, "name", id);
        String version = readString(root, "version", "0.0.0");
        String entry = readString(root, "entry", "init.lua");
        Set<String> loadBefore = parseArrayField(root, "loadBefore");
        Set<String> loadAfter = parseArrayField(root, "loadAfter");
        return new ModManifest(id, name, version, entry, loadBefore, loadAfter, dir);
    }

    private static String readString(JsonObject root, String key, String fallback) {
        if (root == null || key == null || key.isBlank()) return fallback;
        JsonElement value = root.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return fallback;
        String text = value.getAsString();
        return text == null || text.isBlank() ? fallback : text;
    }

    private static Set<String> parseArrayField(JsonObject root, String key) {
        if (root == null || key == null || key.isBlank()) return Collections.emptySet();
        JsonElement value = root.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonArray()) return Collections.emptySet();
        JsonArray array = value.getAsJsonArray();
        TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonElement entry : array) {
            if (entry == null || entry.isJsonNull()) continue;
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) continue;
            String text = entry.getAsString();
            if (text == null) continue;
            String trimmed = text.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return Collections.unmodifiableSet(out);
    }
}
