package arcane.loader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class ArcaneLoaderConfig {
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    static final ArcaneLoaderConfig DEFAULTS = new ArcaneLoaderConfig(
            true,
            false,
            false,
            true,
            10.0,
            256,
            20000,
            10000,
            5000,
            64,
            false,
            false,
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptySet(),
            immutablePolicyCopy(Map.of("*", Set.of("arcane.")))
    );

    private final boolean devMode;
    private final boolean autoReload;
    private final boolean autoEnable;
    private final boolean autoStageAssets;
    private final double slowCallWarnMs;
    private final int blockEditBudgetPerTick;
    private final int maxQueuedBlockEditsPerMod;
    private final int maxTxBlockEditsPerMod;
    private final int maxBatchSetOpsPerCall;
    private final int maxBlockBehaviorNeighborUpdatesPerCause;
    private final boolean restrictSensitiveApis;
    private final boolean allowlistEnabled;
    private final Set<String> allowlist;
    private final Set<String> playerMovementMods;
    private final Set<String> entityControlMods;
    private final Set<String> worldControlMods;
    private final Set<String> networkControlMods;
    private final Set<String> uiControlMods;
    private final Map<String, Set<String>> networkChannelPolicies;

    ArcaneLoaderConfig(
            boolean devMode,
            boolean autoReload,
            boolean autoEnable,
            boolean autoStageAssets,
            double slowCallWarnMs,
            int blockEditBudgetPerTick,
            int maxQueuedBlockEditsPerMod,
            int maxTxBlockEditsPerMod,
            int maxBatchSetOpsPerCall,
            int maxBlockBehaviorNeighborUpdatesPerCause,
            boolean restrictSensitiveApis,
            boolean allowlistEnabled,
            Set<String> allowlist,
            Set<String> playerMovementMods,
            Set<String> entityControlMods,
            Set<String> worldControlMods,
            Set<String> networkControlMods,
            Set<String> uiControlMods,
            Map<String, Set<String>> networkChannelPolicies
    ) {
        this.devMode = devMode;
        this.autoReload = autoReload;
        this.autoEnable = autoEnable;
        this.autoStageAssets = autoStageAssets;
        this.slowCallWarnMs = slowCallWarnMs;
        this.blockEditBudgetPerTick = blockEditBudgetPerTick;
        this.maxQueuedBlockEditsPerMod = maxQueuedBlockEditsPerMod;
        this.maxTxBlockEditsPerMod = maxTxBlockEditsPerMod;
        this.maxBatchSetOpsPerCall = maxBatchSetOpsPerCall;
        this.maxBlockBehaviorNeighborUpdatesPerCause = maxBlockBehaviorNeighborUpdatesPerCause;
        this.restrictSensitiveApis = restrictSensitiveApis;
        this.allowlistEnabled = allowlistEnabled;
        this.allowlist = immutableSortedCopy(allowlist);
        this.playerMovementMods = immutableSortedCopy(playerMovementMods);
        this.entityControlMods = immutableSortedCopy(entityControlMods);
        this.worldControlMods = immutableSortedCopy(worldControlMods);
        this.networkControlMods = immutableSortedCopy(networkControlMods);
        this.uiControlMods = immutableSortedCopy(uiControlMods);
        this.networkChannelPolicies = immutablePolicyCopy(networkChannelPolicies);
    }

    static ArcaneLoaderConfig parse(String json, ArcaneLoaderConfig fallback) {
        ArcaneLoaderConfig base = fallback == null ? DEFAULTS : fallback;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new ArcaneLoaderConfig(
                readBoolean(root, "devMode", base.devMode),
                readBoolean(root, "autoReload", base.autoReload),
                readBoolean(root, "autoEnable", base.autoEnable),
                readBoolean(root, "autoStageAssets", base.autoStageAssets),
                readDouble(root, "slowCallWarnMs", base.slowCallWarnMs),
                readInt(root, "blockEditBudgetPerTick", base.blockEditBudgetPerTick),
                readInt(root, "maxQueuedBlockEditsPerMod", base.maxQueuedBlockEditsPerMod),
                readInt(root, "maxTxBlockEditsPerMod", base.maxTxBlockEditsPerMod),
                readInt(root, "maxBatchSetOpsPerCall", base.maxBatchSetOpsPerCall),
                readInt(root, "maxBlockBehaviorNeighborUpdatesPerCause", base.maxBlockBehaviorNeighborUpdatesPerCause),
                readBoolean(root, "restrictSensitiveApis", base.restrictSensitiveApis),
                readBoolean(root, "allowlistEnabled", base.allowlistEnabled),
                readStringSet(root, "allowlist", base.allowlist),
                readStringSet(root, "playerMovementMods", base.playerMovementMods),
                readStringSet(root, "entityControlMods", base.entityControlMods),
                readStringSet(root, "worldControlMods", base.worldControlMods),
                readStringSet(root, "networkControlMods", base.networkControlMods),
                readStringSet(root, "uiControlMods", base.uiControlMods),
                readPolicyMap(root, "networkChannelPolicies", base.networkChannelPolicies)
        );
    }

    String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("devMode", devMode);
        root.addProperty("autoReload", autoReload);
        root.addProperty("autoEnable", autoEnable);
        root.addProperty("autoStageAssets", autoStageAssets);
        root.addProperty("slowCallWarnMs", slowCallWarnMs);
        root.addProperty("blockEditBudgetPerTick", blockEditBudgetPerTick);
        root.addProperty("maxQueuedBlockEditsPerMod", maxQueuedBlockEditsPerMod);
        root.addProperty("maxTxBlockEditsPerMod", maxTxBlockEditsPerMod);
        root.addProperty("maxBatchSetOpsPerCall", maxBatchSetOpsPerCall);
        root.addProperty("maxBlockBehaviorNeighborUpdatesPerCause", maxBlockBehaviorNeighborUpdatesPerCause);
        root.addProperty("restrictSensitiveApis", restrictSensitiveApis);
        root.addProperty("allowlistEnabled", allowlistEnabled);
        root.add("allowlist", PRETTY_GSON.toJsonTree(allowlist));
        root.add("playerMovementMods", PRETTY_GSON.toJsonTree(playerMovementMods));
        root.add("entityControlMods", PRETTY_GSON.toJsonTree(entityControlMods));
        root.add("worldControlMods", PRETTY_GSON.toJsonTree(worldControlMods));
        root.add("networkControlMods", PRETTY_GSON.toJsonTree(networkControlMods));
        root.add("uiControlMods", PRETTY_GSON.toJsonTree(uiControlMods));
        root.add("networkChannelPolicies", PRETTY_GSON.toJsonTree(networkChannelPolicies));
        return PRETTY_GSON.toJson(root) + System.lineSeparator();
    }

    boolean devMode() { return devMode; }
    boolean autoReload() { return autoReload; }
    boolean autoEnable() { return autoEnable; }
    boolean autoStageAssets() { return autoStageAssets; }
    double slowCallWarnMs() { return slowCallWarnMs; }
    int blockEditBudgetPerTick() { return blockEditBudgetPerTick; }
    int maxQueuedBlockEditsPerMod() { return maxQueuedBlockEditsPerMod; }
    int maxTxBlockEditsPerMod() { return maxTxBlockEditsPerMod; }
    int maxBatchSetOpsPerCall() { return maxBatchSetOpsPerCall; }
    int maxBlockBehaviorNeighborUpdatesPerCause() { return maxBlockBehaviorNeighborUpdatesPerCause; }
    boolean restrictSensitiveApis() { return restrictSensitiveApis; }
    boolean allowlistEnabled() { return allowlistEnabled; }
    Set<String> allowlist() { return allowlist; }
    Set<String> playerMovementMods() { return playerMovementMods; }
    Set<String> entityControlMods() { return entityControlMods; }
    Set<String> worldControlMods() { return worldControlMods; }
    Set<String> networkControlMods() { return networkControlMods; }
    Set<String> uiControlMods() { return uiControlMods; }
    Map<String, Set<String>> networkChannelPolicies() { return networkChannelPolicies; }

    private static boolean readBoolean(JsonObject root, String key, boolean defaultVal) {
        if (root == null || key == null || key.isBlank()) return defaultVal;
        JsonElement value = root.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean()
                : defaultVal;
    }

    private static int readInt(JsonObject root, String key, int defaultVal) {
        if (root == null || key == null || key.isBlank()) return defaultVal;
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return defaultVal;
        try {
            return value.getAsInt();
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static double readDouble(JsonObject root, String key, double defaultVal) {
        if (root == null || key == null || key.isBlank()) return defaultVal;
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return defaultVal;
        try {
            return value.getAsDouble();
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static Set<String> readStringSet(JsonObject root, String key, Set<String> fallback) {
        if (root == null || key == null || key.isBlank()) return fallback;
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonArray()) return fallback;
        TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonElement entry : value.getAsJsonArray()) {
            if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) continue;
            String text = entry.getAsString();
            if (text == null || text.isBlank()) continue;
            out.add(text.trim());
        }
        return Collections.unmodifiableSet(out);
    }

    private static Map<String, Set<String>> readPolicyMap(JsonObject root, String key, Map<String, Set<String>> fallback) {
        if (root == null || key == null || key.isBlank()) return fallback;
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonObject()) return fallback;
        LinkedHashMap<String, Set<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> ent : value.getAsJsonObject().entrySet()) {
            String normalizedKey = normalizePolicyModId(ent.getKey(), true);
            if (normalizedKey == null) continue;
            TreeSet<String> prefixes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            JsonElement rawPrefixes = ent.getValue();
            if (rawPrefixes != null && rawPrefixes.isJsonArray()) {
                for (JsonElement prefixValue : rawPrefixes.getAsJsonArray()) {
                    if (prefixValue == null || !prefixValue.isJsonPrimitive() || !prefixValue.getAsJsonPrimitive().isString()) continue;
                    String prefix = normalizePolicyPrefix(prefixValue.getAsString());
                    if (prefix != null) prefixes.add(prefix);
                }
            }
            out.put(normalizedKey, Collections.unmodifiableSet(prefixes));
        }
        return Collections.unmodifiableMap(out);
    }

    private static String normalizePolicyModId(String modId, boolean allowWildcard) {
        if (modId == null) return null;
        String normalized = modId.trim().toLowerCase();
        if (normalized.isEmpty()) return null;
        if (allowWildcard && normalized.equals("*")) return "*";
        if (!normalized.matches("[a-z0-9._-]+")) return null;
        return normalized;
    }

    private static String normalizePolicyPrefix(String prefix) {
        if (prefix == null) return null;
        String normalized = prefix.trim().toLowerCase();
        if (normalized.isEmpty()) return null;
        if (normalized.chars().anyMatch(Character::isWhitespace)) return null;
        return normalized;
    }

    private static Set<String> immutableSortedCopy(Set<String> source) {
        TreeSet<String> copy = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (source != null) {
            for (String value : source) {
                if (value == null || value.isBlank()) continue;
                copy.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Map<String, Set<String>> immutablePolicyCopy(Map<String, Set<String>> source) {
        TreeMap<String, Set<String>> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (source != null) {
            for (Map.Entry<String, Set<String>> ent : source.entrySet()) {
                String key = normalizePolicyModId(ent.getKey(), true);
                if (key == null) continue;
                Set<String> raw = ent.getValue() == null ? Collections.emptySet() : ent.getValue();
                sorted.put(key, immutableSortedCopy(raw));
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
