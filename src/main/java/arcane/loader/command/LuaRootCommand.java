package arcane.loader.command;

import arcane.loader.ArcaneLoaderPlugin;
import arcane.loader.lua.LuaMod;
import arcane.loader.lua.LuaModManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stable /lua command root.
 */
public final class LuaRootCommand extends AbstractCommandCollection {

    private static final int EVAL_RESULT_LIMIT = 160;
    private static final Pattern LUA_REQUIRE_PATTERN = Pattern.compile("require\\s*\\(?\\s*['\"]([^'\"]+)['\"]\\s*\\)?");

    private final ArcaneLoaderPlugin plugin;

    public LuaRootCommand(ArcaneLoaderPlugin plugin) {
        super("lua", "Arcane Loader commands");
        this.plugin = plugin;

        addSubCommand(new ModsSub(plugin));
        addSubCommand(new NewSub(plugin));
        addSubCommand(new ReloadSub(plugin));
        addSubCommand(new EnableSub(plugin));
        addSubCommand(new DisableSub(plugin));
        addSubCommand(new ErrorsSub(plugin));
        addSubCommand(new ApiSub());
        addSubCommand(new CallSub(plugin));
        addSubCommand(new EvalSub(plugin));
        addSubCommand(new DebugSub(plugin));
        addSubCommand(new ProfileSub(plugin));
        addSubCommand(new TraceSub(plugin));
        addSubCommand(new CapsSub(plugin));
        addSubCommand(new NetstatsSub(plugin));
        addSubCommand(new ConfigSub(plugin));
        addSubCommand(new PolicySub(plugin));
        addSubCommand(new VerifySub(plugin));
        addSubCommand(new DoctorSub(plugin));
        addSubCommand(new CheckSub(plugin));
        addSubCommand(new InspectSub(plugin));
    }

    private static String[] extractArgs(CommandContext context) {
        try {
            var m = context.getClass().getMethod("getArgs");
            Object r = m.invoke(context);
            if (r instanceof String[] a && a.length > 0) return a;
        } catch (Throwable ignored) { }

        try {
            var m = context.getClass().getMethod("args");
            Object r = m.invoke(context);
            if (r instanceof String[] a && a.length > 0) return a;
        } catch (Throwable ignored) { }

        try {
            var m = context.getClass().getMethod("getArguments");
            Object r = m.invoke(context);
            if (r instanceof java.util.List<?> list) {
                if (list.isEmpty()) throw new IllegalStateException("empty");
                String[] out = new String[list.size()];
                for (int i = 0; i < list.size(); i++) out[i] = String.valueOf(list.get(i));
                return out;
            }
        } catch (Throwable ignored) { }

        try {
            var m = context.getClass().getMethod("arguments");
            Object r = m.invoke(context);
            if (r instanceof java.util.List<?> list) {
                if (list.isEmpty()) throw new IllegalStateException("empty");
                String[] out = new String[list.size()];
                for (int i = 0; i < list.size(); i++) out[i] = String.valueOf(list.get(i));
                return out;
            }
        } catch (Throwable ignored) { }

        // Fallback parser for command frameworks that don't expose parsed args on subcommands.
        try {
            String input = null;
            try {
                var getInput = context.getClass().getMethod("getInputString");
                Object r = getInput.invoke(context);
                if (r != null) input = String.valueOf(r);
            } catch (Throwable ignored) { }
            if (input == null || input.isBlank()) return new String[0];

            String calledName = null;
            try {
                var getCalled = context.getClass().getMethod("getCalledCommand");
                Object cmd = getCalled.invoke(context);
                if (cmd != null) {
                    var getName = cmd.getClass().getMethod("getName");
                    Object n = getName.invoke(cmd);
                    if (n != null) calledName = String.valueOf(n).trim().toLowerCase(java.util.Locale.ROOT);
                }
            } catch (Throwable ignored) { }

            String[] raw = java.util.Arrays.stream(input.trim().split("\\s+"))
                    .filter(s -> s != null && !s.isBlank())
                    .toArray(String[]::new);
            if (raw.length == 0) return new String[0];

            int start = 0;
            if (raw.length >= 2 && raw[0].equalsIgnoreCase("lua")) {
                start = 1;
                if (calledName != null && raw.length >= 3 && raw[1].equalsIgnoreCase(calledName)) {
                    start = 2;
                }
            } else if (calledName != null && raw[0].equalsIgnoreCase(calledName)) {
                start = 1;
            }
            if (start >= raw.length) return new String[0];
            return java.util.Arrays.copyOfRange(raw, start, raw.length);
        } catch (Throwable ignored) { }

        return new String[0];
    }

    private static String firstArgOrNull(CommandContext context) {
        String[] args = extractArgs(context);
        if (args.length == 0) return null;
        String a = args[0];
        if (a == null) return null;
        a = a.trim();
        return a.isEmpty() ? null : a;
    }

    private static String truncate(String s, int limit) {
        if (s == null) return "";
        if (s.length() <= limit) return s;
        return s.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private static String renderLuaValue(LuaValue value) {
        if (value == null || value.isnil()) return "nil";
        String text;
        try {
            text = value.tojstring();
        } catch (Throwable ignored) {
            text = value.typename();
        }
        text = truncate(text, EVAL_RESULT_LIMIT);
        if (value.isstring() || value.isnumber() || value.isboolean()) {
            return text;
        }
        return value.typename() + ": " + text;
    }

    private static final class EvalResult {
        private final boolean ok;
        private final String message;

        private EvalResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private record DiagnosticIssue(String severity, String code, String message, String source) {}

    private record ManifestInfo(
            String id,
            String name,
            String version,
            String entry,
            List<String> loadBefore,
            List<String> loadAfter,
            JsonObject raw
    ) {}

    private record ModCheckResult(
            String folderName,
            Path dir,
            Path manifestPath,
            ManifestInfo manifest,
            int luaFileCount,
            List<DiagnosticIssue> issues
    ) {}

    private static void hardenEvalGlobals(Globals globals) {
        globals.set("os", LuaValue.NIL);
        globals.set("io", LuaValue.NIL);
        globals.set("package", LuaValue.NIL);
        globals.set("dofile", LuaValue.NIL);
        globals.set("loadfile", LuaValue.NIL);
    }

    private static String severityLabel(String severity) {
        if (severity == null) return "INFO";
        return severity.trim().toUpperCase(Locale.ROOT);
    }

    private static void addIssue(List<DiagnosticIssue> issues, String severity, String code, String message, String source) {
        issues.add(new DiagnosticIssue(severityLabel(severity), code, message, source == null ? "" : source));
    }

    private static String renderIssue(DiagnosticIssue issue) {
        String src = issue.source() == null || issue.source().isBlank() ? "" : " [" + issue.source() + "]";
        return " - " + issue.severity() + " " + issue.code() + src + ": " + issue.message();
    }

    private static String readJsonString(JsonObject obj, String key, String fallback) {
        if (obj == null || key == null || key.isBlank()) return fallback;
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return fallback;
        String text = value.getAsString();
        return text == null || text.isBlank() ? fallback : text;
    }

    private static List<String> readJsonStringArray(JsonObject obj, String key) {
        if (obj == null || key == null || key.isBlank()) return List.of();
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonArray()) return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element == null || element.isJsonNull()) continue;
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) continue;
            String text = element.getAsString();
            if (text == null || text.isBlank()) continue;
            out.add(text.trim());
        }
        return Collections.unmodifiableList(out);
    }

    private static ManifestInfo parseManifest(Path manifestPath, List<DiagnosticIssue> issues) {
        try {
            String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String id = readJsonString(root, "id", null);
            String name = readJsonString(root, "name", id == null ? "" : id);
            String version = readJsonString(root, "version", "0.0.0");
            String entry = readJsonString(root, "entry", "init.lua");
            return new ManifestInfo(id, name, version, entry, readJsonStringArray(root, "loadBefore"), readJsonStringArray(root, "loadAfter"), root);
        } catch (Throwable t) {
            addIssue(issues, "ERROR", "manifest.parse", String.valueOf(t.getMessage() == null ? t : t.getMessage()), manifestPath.getFileName().toString());
            return null;
        }
    }

    private static List<String> findRequires(String source) {
        if (source == null || source.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher matcher = LUA_REQUIRE_PATTERN.matcher(source);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return Collections.unmodifiableList(new ArrayList<>(out));
    }

    private static boolean moduleExists(Path modRoot, String moduleName) {
        if (modRoot == null || moduleName == null || moduleName.isBlank()) return false;
        String normalized = moduleName.trim().replace('.', '/');
        List<Path> candidates = List.of(
                modRoot.resolve(normalized + ".lua"),
                modRoot.resolve(normalized).resolve("init.lua"),
                modRoot.resolve("modules").resolve(normalized + ".lua"),
                modRoot.resolve("modules").resolve(normalized).resolve("init.lua")
        );
        for (Path candidate : candidates) {
            Path resolved = candidate.normalize();
            if (!resolved.startsWith(modRoot.normalize())) continue;
            if (Files.isRegularFile(resolved)) return true;
        }
        return false;
    }

    private static void scanCapabilityHints(ArcaneLoaderPlugin plugin, ManifestInfo manifest, List<DiagnosticIssue> issues, String rel, String source) {
        if (plugin == null || manifest == null || manifest.id() == null || source == null || source.isBlank()) return;
        if (!plugin.isRestrictSensitiveApis()) return;
        String modId = manifest.id();
        if ((source.contains("players.teleport(") || source.contains("players.refer(") || source.contains("players.kick("))
                && !plugin.canUseCapability(modId, "player-movement")) {
            addIssue(issues, "WARN", "capability.player_movement", "code appears to use player-movement operations but capability is currently denied", rel);
        }
        if ((source.contains("entities.spawn(") || source.contains("entities.remove(") || source.contains("entities.setVelocity(") || source.contains("actors.spawn("))
                && !plugin.canUseCapability(modId, "entity-control")) {
            addIssue(issues, "WARN", "capability.entity_control", "code appears to use entity-control operations but capability is currently denied", rel);
        }
        if ((source.contains("world.setBlock(") || source.contains("world.setBlockState(") || source.contains("world.queueSetBlock(") || source.contains("blocks.register("))
                && !plugin.canUseCapability(modId, "world-control")) {
            addIssue(issues, "WARN", "capability.world_control", "code appears to use world-control operations but capability is currently denied", rel);
        }
        if ((source.contains("network.send(") || source.contains("network.emit(") || source.contains("webhook.") || source.contains("players.refer("))
                && !plugin.canUseCapability(modId, "network-control")) {
            addIssue(issues, "WARN", "capability.network_control", "code appears to use network-control operations but capability is currently denied", rel);
        }
        if ((source.contains("ui.") || source.contains("players.sendActionBar(") || source.contains("players.sendTitle("))
                && !plugin.canUseCapability(modId, "ui-control")) {
            addIssue(issues, "WARN", "capability.ui_control", "code appears to use ui-control operations but capability is currently denied", rel);
        }
    }

    private static Path writeCheckReport(ArcaneLoaderPlugin plugin, List<ModCheckResult> results) throws Exception {
        Path logsDir = plugin.getLogDir();
        Files.createDirectories(logsDir);
        String ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());
        Path out = logsDir.resolve("arcane-lua-check-" + ts + ".json");
        JsonObject root = new JsonObject();
        root.addProperty("generatedAtUtc", java.time.Instant.now().toString());
        root.addProperty("modCount", results.size());
        int errors = 0;
        int warnings = 0;
        com.google.gson.JsonArray mods = new com.google.gson.JsonArray();
        for (ModCheckResult result : results) {
            JsonObject mod = new JsonObject();
            String modId = result.manifest() == null ? result.folderName() : String.valueOf(result.manifest().id());
            mod.addProperty("modId", modId);
            mod.addProperty("folderName", result.folderName());
            mod.addProperty("dir", result.dir().toString());
            mod.addProperty("manifestPath", result.manifestPath().toString());
            mod.addProperty("luaFileCount", result.luaFileCount());
            if (result.manifest() != null) {
                mod.addProperty("entry", result.manifest().entry());
                mod.addProperty("version", result.manifest().version());
            }
            com.google.gson.JsonArray issues = new com.google.gson.JsonArray();
            for (DiagnosticIssue issue : result.issues()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("severity", issue.severity());
                obj.addProperty("code", issue.code());
                obj.addProperty("message", issue.message());
                obj.addProperty("source", issue.source());
                issues.add(obj);
                if ("ERROR".equals(issue.severity())) errors++;
                else if ("WARN".equals(issue.severity())) warnings++;
            }
            mod.add("issues", issues);
            mods.add(mod);
        }
        root.addProperty("errors", errors);
        root.addProperty("warnings", warnings);
        root.add("mods", mods);
        Files.writeString(out, root.toString(), StandardCharsets.UTF_8);
        return out;
    }

    private static String normalizeModIdCandidate(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replace(' ', '-')
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("-{2,}", "-");
        normalized = normalized.replaceAll("^[._-]+", "").replaceAll("[._-]+$", "");
        return normalized.isBlank() ? null : normalized;
    }

    private static String titleFromModId(String modId) {
        if (modId == null || modId.isBlank()) return "New Mod";
        String[] parts = modId.replace('.', '-').replace('_', '-').split("-");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) out.append(part.substring(1));
        }
        return out.isEmpty() ? "New Mod" : out.toString();
    }

    private static String manifestTemplate(String modId, String name) {
        return "{\n"
                + "  \"id\": \"" + modId + "\",\n"
                + "  \"name\": \"" + name.replace("\"", "\\\"") + "\",\n"
                + "  \"version\": \"1.1.0\",\n"
                + "  \"entry\": \"init.lua\"\n"
                + "}\n";
    }

    private static String initTemplate(String modId, String template) {
        String normalizedTemplate = template == null ? "blank" : template.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedTemplate) {
            case "command" -> String.format(Locale.ROOT, """
                    local M = {}

                    function M.onEnable(ctx)
                      commands.register("%1$s", "Starter command for %1$s", function(sender, args)
                        players.send(sender, "Hello from %1$s")
                      end)
                      log.info("Enabled. Try /lua call %1$s %1$s")
                    end

                    function M.onDisable(ctx)
                      commands.unregister("%1$s")
                    end

                    return M
                    """, modId);
            case "event" -> String.format(Locale.ROOT, """
                    local M = {}

                    function M.onEnable(ctx)
                      events.on("player_join", function(payload)
                        local player = payload.player
                        if player ~= nil then
                          players.send(player, "Welcome from %1$s")
                        end
                      end)
                      log.info("Enabled event starter for %1$s")
                    end

                    return M
                    """, modId);
            default -> String.format(Locale.ROOT, """
                    local M = {}

                    function M.onEnable(ctx)
                      log.info("Enabled %1$s")
                    end

                    function M.onDisable(ctx)
                      log.info("Disabled %1$s")
                    end

                    return M
                    """, modId);
        };
    }

    private static void sendReloadHint(CommandContext context, LuaMod mod) {
        if (mod == null) return;
        if (mod.lastError() == null || mod.lastError().isBlank()) {
            context.sender().sendMessage(Message.raw("Hint: use /lua inspect " + mod.manifest().id() + " for runtime details."));
        } else {
            context.sender().sendMessage(Message.raw("Hint: use /lua errors " + mod.manifest().id() + " detail or /lua check " + mod.manifest().id()));
        }
    }

    private static String loadSummary(LuaModManager mm) {
        int total = 0;
        int enabled = 0;
        int errored = 0;
        for (LuaMod mod : mm.listMods()) {
            total++;
            if (mod.state() == arcane.loader.lua.LuaModState.ENABLED) enabled++;
            if (mod.state() == arcane.loader.lua.LuaModState.ERROR) errored++;
        }
        return "mods=" + total + " enabled=" + enabled + " error=" + errored;
    }

    private static void sendBulkLoadHint(CommandContext context, LuaModManager mm) {
        if (mm.modsWithErrors().isEmpty()) {
            context.sender().sendMessage(Message.raw("Hint: use /lua mods or /lua inspect <modId> to confirm what loaded."));
        } else {
            context.sender().sendMessage(Message.raw("Hint: use /lua errors all or /lua check all to fix the mods still failing."));
        }
    }

    private static List<ModCheckResult> runModChecks(ArcaneLoaderPlugin plugin, String targetModId) {
        Path root = plugin.getLuaModsDir();
        ArrayList<ModCheckResult> results = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            ArrayList<DiagnosticIssue> issues = new ArrayList<>();
            addIssue(issues, "ERROR", "mods.root", "lua_mods directory is missing", root.toString());
            results.add(new ModCheckResult("(root)", root, root.resolve("manifest.json"), null, 0, issues));
            return results;
        }

        TreeMap<String, List<String>> foldersByModId = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ArrayList<ModCheckResult> discovered = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path dir : ds) {
                if (!Files.isDirectory(dir)) continue;
                Path manifestPath = dir.resolve("manifest.json");
                if (!Files.isRegularFile(manifestPath)) continue;
                ArrayList<DiagnosticIssue> issues = new ArrayList<>();
                ManifestInfo manifest = parseManifest(manifestPath, issues);
                String effectiveId = manifest == null ? dir.getFileName().toString() : manifest.id();
                if (targetModId != null && effectiveId != null && !effectiveId.equalsIgnoreCase(targetModId)) continue;
                if (targetModId != null && manifest == null && !dir.getFileName().toString().equalsIgnoreCase(targetModId)) continue;

                int luaFileCount = 0;
                try (var walk = Files.walk(dir)) {
                    ArrayList<Path> luaFiles = new ArrayList<>(walk.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lua"))
                            .toList());
                    luaFiles.sort(Comparator.comparing(path -> dir.relativize(path).toString(), String.CASE_INSENSITIVE_ORDER));
                    luaFileCount = luaFiles.size();
                    Globals globals = JsePlatform.standardGlobals();
                    hardenEvalGlobals(globals);
                    for (Path luaFile : luaFiles) {
                        String rel = dir.relativize(luaFile).toString().replace('\\', '/');
                        try {
                            String source = Files.readString(luaFile, StandardCharsets.UTF_8);
                            globals.load(source, "@" + rel);
                            for (String moduleName : findRequires(source)) {
                                if (!moduleExists(dir, moduleName)) {
                                    addIssue(issues, "WARN", "lua.require_missing", "require target could not be resolved in mod-local paths", rel + " -> " + moduleName);
                                }
                            }
                            scanCapabilityHints(plugin, manifest, issues, rel, source);
                        } catch (Throwable t) {
                            addIssue(issues, "ERROR", "lua.syntax", String.valueOf(t.getMessage() == null ? t : t.getMessage()), rel);
                        }
                    }
                } catch (Throwable t) {
                    addIssue(issues, "ERROR", "lua.scan", String.valueOf(t.getMessage() == null ? t : t.getMessage()), dir.toString());
                }

                if (manifest != null) {
                    foldersByModId.computeIfAbsent(manifest.id() == null ? "" : manifest.id(), ignored -> new ArrayList<>()).add(dir.getFileName().toString());
                    if (manifest.id() == null || manifest.id().isBlank()) {
                        addIssue(issues, "ERROR", "manifest.id", "manifest missing id", "manifest.json");
                    } else if (!manifest.id().matches("[a-z0-9._-]+")) {
                        addIssue(issues, "WARN", "manifest.id_format", "id should usually match [a-z0-9._-]+", manifest.id());
                    }
                    if (manifest.entry() == null || manifest.entry().isBlank()) {
                        addIssue(issues, "ERROR", "manifest.entry", "entry is missing or blank", "manifest.json");
                    } else {
                        Path entryPath = dir.resolve(manifest.entry()).normalize();
                        if (!entryPath.startsWith(dir.normalize())) {
                            addIssue(issues, "ERROR", "manifest.entry_path", "entry escapes mod root", manifest.entry());
                        } else if (!Files.isRegularFile(entryPath)) {
                            addIssue(issues, "ERROR", "manifest.entry_missing", "entry file does not exist", manifest.entry());
                        }
                    }
                    if (manifest.version() == null || manifest.version().isBlank()) {
                        addIssue(issues, "WARN", "manifest.version", "version is blank", "manifest.json");
                    }
                    if (manifest.id() != null && !dir.getFileName().toString().equalsIgnoreCase(manifest.id())) {
                        addIssue(issues, "INFO", "folder.id_mismatch", "folder name and manifest id differ", dir.getFileName() + " vs " + manifest.id());
                    }
                    for (String dep : manifest.loadAfter()) {
                        if (dep.equalsIgnoreCase(manifest.id())) {
                            addIssue(issues, "WARN", "manifest.loadAfter_self", "loadAfter contains self reference", dep);
                        }
                    }
                    for (String dep : manifest.loadBefore()) {
                        if (dep.equalsIgnoreCase(manifest.id())) {
                            addIssue(issues, "WARN", "manifest.loadBefore_self", "loadBefore contains self reference", dep);
                        }
                    }
                }
                discovered.add(new ModCheckResult(dir.getFileName().toString(), dir, manifestPath, manifest, luaFileCount, issues));
            }
        } catch (Throwable t) {
            ArrayList<DiagnosticIssue> issues = new ArrayList<>();
            addIssue(issues, "ERROR", "mods.scan", String.valueOf(t.getMessage() == null ? t : t.getMessage()), root.toString());
            results.add(new ModCheckResult("(scan)", root, root.resolve("manifest.json"), null, 0, issues));
            return results;
        }

        TreeSet<String> knownIds = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ModCheckResult result : discovered) {
            if (result.manifest() != null && result.manifest().id() != null) knownIds.add(result.manifest().id());
        }

        for (ModCheckResult result : discovered) {
            ArrayList<DiagnosticIssue> issues = new ArrayList<>(result.issues());
            ManifestInfo manifest = result.manifest();
            if (manifest != null && manifest.id() != null) {
                List<String> folders = foldersByModId.get(manifest.id());
                if (folders != null && folders.size() > 1) {
                    addIssue(issues, "ERROR", "manifest.duplicate_id", "duplicate mod id discovered in folders: " + String.join(", ", folders), manifest.id());
                }
                for (String dep : manifest.loadAfter()) {
                    if (!knownIds.contains(dep)) {
                        addIssue(issues, "WARN", "manifest.loadAfter_missing", "loadAfter target not found in lua_mods", dep);
                    }
                }
                for (String dep : manifest.loadBefore()) {
                    if (!knownIds.contains(dep)) {
                        addIssue(issues, "WARN", "manifest.loadBefore_missing", "loadBefore target not found in lua_mods", dep);
                    }
                }
            }
            results.add(new ModCheckResult(result.folderName(), result.dir(), result.manifestPath(), manifest, result.luaFileCount(), issues));
        }

        results.sort(Comparator.comparing(result -> {
            ManifestInfo manifest = result.manifest();
            return manifest == null || manifest.id() == null ? result.folderName() : manifest.id();
        }, String.CASE_INSENSITIVE_ORDER));
        return results;
    }

    private static EvalResult evalInDevSandbox(String code) {
        try {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("os", LuaValue.NIL);
            globals.set("io", LuaValue.NIL);
            globals.set("package", LuaValue.NIL);
            globals.set("dofile", LuaValue.NIL);
            globals.set("loadfile", LuaValue.NIL);

            LuaValue chunk;
            try {
                chunk = globals.load("return " + code, "lua-eval");
            } catch (Throwable ignored) {
                chunk = globals.load(code, "lua-eval");
            }

            Varargs out = chunk.invoke();
            return new EvalResult(true, renderLuaValue(out.arg1()));
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null || msg.isBlank()) msg = t.toString();
            return new EvalResult(false, truncate(msg, EVAL_RESULT_LIMIT));
        }
    }

    private static final class ModsSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private ModsSub(ArcaneLoaderPlugin plugin) {
            super("mods", "List Lua mods and loader paths");
            this.plugin = plugin;
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            context.sender().sendMessage(Message.raw("Arcane Loader paths:"));
            context.sender().sendMessage(Message.raw("- root: " + plugin.getServerRoot()));
            context.sender().sendMessage(Message.raw("- lua_mods: " + plugin.getLuaModsDir()));
            context.sender().sendMessage(Message.raw("- lua_cache: " + plugin.getLuaCacheDir()));
            context.sender().sendMessage(Message.raw("- logs: " + plugin.getLogDir()));
            context.sender().sendMessage(Message.raw("- lua_assets: " + plugin.getLuaAssetsDir()));
            context.sender().sendMessage(Message.raw("- config: " + plugin.getConfigPath()));
            context.sender().sendMessage(Message.raw("- devMode: " + plugin.isDevMode()));
            context.sender().sendMessage(Message.raw("- autoReload: " + plugin.isAutoReload()));
            context.sender().sendMessage(Message.raw("- autoEnable: " + plugin.isAutoEnable()));
            context.sender().sendMessage(Message.raw("- autoStageAssets: " + plugin.isAutoStageAssets()));
            context.sender().sendMessage(Message.raw("- slowCallWarnMs: " + plugin.getSlowCallWarnMs()));
            context.sender().sendMessage(Message.raw("- blockEditBudgetPerTick: " + plugin.getBlockEditBudgetPerTick()));
            context.sender().sendMessage(Message.raw("- maxQueuedBlockEditsPerMod: " + plugin.getMaxQueuedBlockEditsPerMod()));
            context.sender().sendMessage(Message.raw("- maxTxBlockEditsPerMod: " + plugin.getMaxTxBlockEditsPerMod()));
            context.sender().sendMessage(Message.raw("- maxBatchSetOpsPerCall: " + plugin.getMaxBatchSetOpsPerCall()));
            context.sender().sendMessage(Message.raw("- restrictSensitiveApis: " + plugin.isRestrictSensitiveApis()));

            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("(Mod manager not ready.)"));
                return CompletableFuture.completedFuture(null);
            }

            mm.scanMods();
            context.sender().sendMessage(Message.raw("Lua mods:"));
            boolean any = false;
            for (LuaMod mod : mm.listMods()) {
                any = true;
                context.sender().sendMessage(Message.raw(" - " + mod.manifest().id() + " [" + mod.state() + "] v" + mod.manifest().version()));
            }
            if (!any) {
                context.sender().sendMessage(Message.raw(" (none found; use /lua new my_first_mod or create server/lua_mods/<modId>/manifest.json)"));
            } else {
                context.sender().sendMessage(Message.raw("Hint: use /lua reload <modId> after edits, /lua inspect <modId> for details, and /lua check <modId> before reloading."));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class NewSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private NewSub(ArcaneLoaderPlugin plugin) {
            super("new", "Create a starter Lua mod: /lua new <modId> [blank|command|event]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            String[] args = extractArgs(context);
            if (args.length < 1) {
                context.sender().sendMessage(Message.raw("Usage: /lua new <modId> [blank|command|event]"));
                context.sender().sendMessage(Message.raw("Templates: blank, command, event"));
                return CompletableFuture.completedFuture(null);
            }

            String modId = normalizeModIdCandidate(args[0]);
            if (modId == null) {
                context.sender().sendMessage(Message.raw("Invalid mod id. Use letters, numbers, dots, underscores, or dashes."));
                return CompletableFuture.completedFuture(null);
            }
            String template = args.length >= 2 ? args[1] : "blank";
            if (!template.equalsIgnoreCase("blank") && !template.equalsIgnoreCase("command") && !template.equalsIgnoreCase("event")) {
                context.sender().sendMessage(Message.raw("Unknown template: " + template + ". Use blank, command, or event."));
                return CompletableFuture.completedFuture(null);
            }

            Path modDir = plugin.getLuaModsDir().resolve(modId).normalize();
            if (!modDir.startsWith(plugin.getLuaModsDir().normalize())) {
                context.sender().sendMessage(Message.raw("Refusing to create mod outside lua_mods."));
                return CompletableFuture.completedFuture(null);
            }
            if (Files.exists(modDir)) {
                context.sender().sendMessage(Message.raw("Mod folder already exists: " + modDir));
                return CompletableFuture.completedFuture(null);
            }
            try {
                Files.createDirectories(modDir);
                Files.writeString(modDir.resolve("manifest.json"), manifestTemplate(modId, titleFromModId(modId)), StandardCharsets.UTF_8);
                Files.writeString(modDir.resolve("init.lua"), initTemplate(modId, template), StandardCharsets.UTF_8);
                context.sender().sendMessage(Message.raw("Created starter mod: " + modDir));
                context.sender().sendMessage(Message.raw(" - manifest.json"));
                context.sender().sendMessage(Message.raw(" - init.lua"));
                context.sender().sendMessage(Message.raw("Next steps: /lua check " + modId + ", then /lua reload " + modId));
                context.sender().sendMessage(Message.raw("Starter template: " + template.toLowerCase(Locale.ROOT)));
            } catch (Exception e) {
                context.sender().sendMessage(Message.raw("Failed creating starter mod: " + e));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ReloadSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private ReloadSub(ArcaneLoaderPlugin plugin) {
            super("reload", "Reload Lua mods: /lua reload [modId|all]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();
            String target = firstArgOrNull(context);
            if (target == null || target.equalsIgnoreCase("all")) {
                mm.reloadAll();
                context.sender().sendMessage(Message.raw("Reloaded all Lua mods. " + loadSummary(mm)));
                sendBulkLoadHint(context, mm);
                return CompletableFuture.completedFuture(null);
            }
            LuaMod mod = mm.findById(target);
            if (mod == null) {
                context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + target));
                return CompletableFuture.completedFuture(null);
            }
            mm.reloadMod(mod);
            context.sender().sendMessage(Message.raw("Reloaded Lua mod: " + mod.manifest().id() + " [" + mod.state() + "]"));
            sendReloadHint(context, mod);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class EnableSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private EnableSub(ArcaneLoaderPlugin plugin) {
            super("enable", "Enable Lua mods: /lua enable [modId|all]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();
            String target = firstArgOrNull(context);
            if (target == null || target.equalsIgnoreCase("all")) {
                mm.enableAll();
                context.sender().sendMessage(Message.raw("Enabled all Lua mods. " + loadSummary(mm)));
                sendBulkLoadHint(context, mm);
                return CompletableFuture.completedFuture(null);
            }
            LuaMod mod = mm.findById(target);
            if (mod == null) {
                context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + target));
                return CompletableFuture.completedFuture(null);
            }
            mm.enableMod(mod);
            context.sender().sendMessage(Message.raw("Enabled Lua mod: " + mod.manifest().id() + " [" + mod.state() + "]"));
            sendReloadHint(context, mod);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class DisableSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private DisableSub(ArcaneLoaderPlugin plugin) {
            super("disable", "Disable Lua mods: /lua disable [modId|all]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();
            String target = firstArgOrNull(context);
            if (target == null || target.equalsIgnoreCase("all")) {
                mm.disableAll();
                context.sender().sendMessage(Message.raw("Disabled all Lua mods."));
                return CompletableFuture.completedFuture(null);
            }
            LuaMod mod = mm.findById(target);
            if (mod == null) {
                context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + target));
                return CompletableFuture.completedFuture(null);
            }
            mm.disableMod(mod);
            context.sender().sendMessage(Message.raw("Disabled Lua mod: " + mod.manifest().id()));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ErrorsSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private ErrorsSub(ArcaneLoaderPlugin plugin) {
            super("errors", "Show errors: /lua errors [modId|all]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();
            String target = firstArgOrNull(context);
            if (target != null && !target.equalsIgnoreCase("all")) {
                String mode = null;
                String[] args = extractArgs(context);
                if (args.length >= 2) mode = args[1];
                LuaMod mod = mm.findById(target);
                if (mod == null) {
                    context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + target));
                    return CompletableFuture.completedFuture(null);
                }
                if (mod.lastError() == null) {
                    context.sender().sendMessage(Message.raw("No recorded errors for " + mod.manifest().id() + "."));
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw("Lua mod error for " + mod.manifest().id() + " [" + mod.state() + "]:"));
                context.sender().sendMessage(Message.raw(" - " + mod.lastError()));
                if (!mod.errorHistory().isEmpty()) {
                    context.sender().sendMessage(Message.raw("Recent errors:"));
                    int limit = Math.min(5, mod.errorHistory().size());
                    for (int i = 0; i < limit; i++) {
                        context.sender().sendMessage(Message.raw(" - " + truncate(mod.errorHistory().get(i), 140)));
                    }
                }
                if (mode != null && (mode.equalsIgnoreCase("detail") || mode.equalsIgnoreCase("stack"))) {
                    String detail = mod.lastErrorDetail();
                    if (detail == null || detail.isBlank()) {
                        context.sender().sendMessage(Message.raw("No detailed stack trace recorded."));
                    } else {
                        context.sender().sendMessage(Message.raw("Stack trace:"));
                        String[] lines = detail.split("\\R");
                        int limit = Math.min(12, lines.length);
                        for (int i = 0; i < limit; i++) {
                            context.sender().sendMessage(Message.raw(" - " + truncate(lines[i], 180)));
                        }
                        if (lines.length > limit) {
                            context.sender().sendMessage(Message.raw(" - ... " + (lines.length - limit) + " more line(s) in logs"));
                        }
                    }
                } else {
                    context.sender().sendMessage(Message.raw("Use /lua errors " + mod.manifest().id() + " detail for stack trace excerpt."));
                }
                return CompletableFuture.completedFuture(null);
            }

            var errs = mm.modsWithErrors();
            if (errs.isEmpty()) {
                context.sender().sendMessage(Message.raw("No recorded Lua mod errors."));
                return CompletableFuture.completedFuture(null);
            }
            context.sender().sendMessage(Message.raw("Lua mod errors:"));
            for (LuaMod mod : errs) {
                context.sender().sendMessage(Message.raw(" - " + mod.manifest().id() + " [" + mod.state() + "]: " + truncate(mod.lastError(), 120)));
            }
            context.sender().sendMessage(Message.raw("(Full stack traces are in server logs for now.)"));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ApiSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private ApiSub() {
            super("api", "Show Lua API summary");
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            context.sender().sendMessage(Message.raw("Lua API (current):"));
            context.sender().sendMessage(Message.raw(" - log.info(msg), log.warn(msg), log.error(msg)"));
            context.sender().sendMessage(Message.raw(" - commands.register(name, fn|help, fn), commands.unregister(name), commands.list(), commands.help(name)"));
            context.sender().sendMessage(Message.raw(" - events.on(event[, options], fn), events.once(event[, options], fn), events.emit(event, payload...), events.off(event[, fn]), events.clear(event), events.list(), events.count([event]), events.listeners(event)"));
            context.sender().sendMessage(Message.raw("   event options: priority=LOWEST|LOW|NORMAL|HIGH|HIGHEST|MONITOR (or number), ignoreCancelled=true, once=true"));
            context.sender().sendMessage(Message.raw("   server events: server_start, server_stop, pre_tick(payload), tick(payload), post_tick(payload), player_connect(payload), player_disconnect(payload), player_chat(payload), player_ready(payload), player_world_join(payload), player_world_leave(payload)"));
            context.sender().sendMessage(Message.raw("   gameplay events: block_break(payload), block_place(payload), block_use(payload), block_damage(payload), item_drop(payload), item_pickup(payload), player_interact(payload), player_craft(payload), entity_remove(payload), entity_inventory_change(payload), world_add(payload), world_remove(payload), world_start(payload), worlds_loaded(payload), chunk_save(payload), chunk_unload(payload)"));
            context.sender().sendMessage(Message.raw("   payload controls (when supported): payload.cancel(), payload.setCancelled(bool), payload.isCancelled()"));
            context.sender().sendMessage(Message.raw("   chat controls (when supported): payload.setContent(text), payload.getContent()"));
            context.sender().sendMessage(Message.raw(" - vec3.new(x,y,z), vec3.is(v), vec3.unpack(v); blockpos.new(x,y,z), blockpos.is(v), blockpos.unpack(v)"));
            context.sender().sendMessage(Message.raw(" - query.withinDistance(a, b, radius), query.nearestPlayer(origin[, radius[, worldUuid]]), query.nearestEntity(origin[, radius[, worldUuid]]), query.nearestActor(origin[, radius[, kind]]), query.nearestVolume(origin[, radius[, kind]]), query.nearestNode(origin[, radius[, kind]])"));
            context.sender().sendMessage(Message.raw(" - interop.server(), interop.universe(), interop.defaultWorld(), interop.get(target,key), interop.set(target,key,val), interop.call(target,method,...), interop.methods(target[,prefix]), interop.typeOf(target)"));
            context.sender().sendMessage(Message.raw(" - components.health/setHealth, components.maxHealth/setMaxHealth, components.alive, components.damage/heal, components.stamina/setStamina, components.hunger/setHunger, components.mana/setMana, components.tags/hasTag/addTag/removeTag, components.get/set/call/methods"));
            context.sender().sendMessage(Message.raw(" - fs.read(path), fs.write(path, data), fs.append(path, data), fs.mkdir(path), fs.readJson(path[, defaults[, writeBack]]), fs.writeJson(path, value[, pretty]), fs.delete(path[, recursive]), fs.move(from, to[, replace]), fs.copy(from, to[, replace]), fs.exists(path), fs.list([dir])"));
            context.sender().sendMessage(Message.raw(" - store.path(name), store.exists(name), store.list(), store.load(name[, defaults[, writeBack]]), store.loadVersioned(name, schemaVersion[, defaults[, migrators[, writeBack]]]), store.save(name, value[, pretty]), store.saveVersioned(name, schemaVersion, value[, pretty]), store.delete(name)"));
            context.sender().sendMessage(Message.raw(" - standins.create(options), standins.spawn(typeOrPrototype, options), standins.find(id), standins.list(), standins.count(), standins.listByKind(kind), standins.listByTag(tag), standins.state(id), standins.setState(id, state), standins.resolve(id), standins.actor(id), standins.transform(id), standins.volume(id), standins.node(id), standins.has(id, component), standins.move(id, vec3), standins.rotate(id, vec3), standins.attach(childId, parentId), standins.remove(id), standins.clear()"));
            context.sender().sendMessage(Message.raw(" - players.send(...), players.name(...), players.uuid(...), players.worldUuid(...), players.position(...), players.teleport(player, [worldUuid], x,y,z|vec3), players.kick(...), players.refer(...), players.isValid(...), players.hasPermission(...), players.broadcast(...), players.broadcastTo(...), players.list(), players.count(), players.findByName(...), players.findByUuid(...)"));
            context.sender().sendMessage(Message.raw(" - entities.list([worldUuid]), entities.count([worldUuid]), entities.find(id), entities.id(entity), entities.type(entity), entities.worldUuid(entity), entities.isValid(entity), entities.remove(entity), entities.position(entity), entities.teleport(entity, [worldUuid], x,y,z|vec3), entities.rotation(entity), entities.setRotation(entity,yaw,pitch[,roll]|table), entities.velocity(entity), entities.setVelocity(entity,x,y,z|vec3), entities.near([worldUuid],x,y,z,radius), entities.spawn(typeOrPrototype,[worldUuid],x,y,z|vec3[,yaw,pitch,roll]), entities.inventory/equipment, entities.setEquipment(entity,slot,item), entities.giveItem/takeItem, entities.effects/addEffect/removeEffect, entities.attribute/setAttribute, entities.pathTo/stopPath, entities.canControl()"));
            context.sender().sendMessage(Message.raw(" - world.list(), world.find(uuid), world.findByName(name), world.default(), world.ofPlayer(player), world.players([uuid]), world.playerCount([uuid]), world.entities([uuid]), world.entityCount([uuid]), world.time([uuid]), world.setTime(uuid, time), world.isPaused([uuid]), world.setPaused(uuid, bool), world.blockAt([worldUuid],x,y,z), world.blockNameAt([worldUuid],x,y,z), world.blockIdAt([worldUuid],x,y,z), world.blockType(nameOrId), world.blockStateAt([worldUuid],x,y,z), world.setBlock([worldUuid],x,y,z,block), world.setBlockState([worldUuid],x,y,z,state), world.queueSetBlock([worldUuid],x,y,z,block), world.batchGet([worldUuid],positions[,includeAir]), world.batchSet([worldUuid],edits[,direct|queue|tx]), world.applyQueuedBlocks([limit]), world.clearQueuedBlocks(), world.queuedBlocks(), world.txBegin(), world.txSetBlock(...), world.txCommit([limit]), world.txRollback(), world.txStatus(), world.neighbors([worldUuid],x,y,z), world.scanBox([worldUuid],minX,minY,minZ,maxX,maxY,maxZ[,limit]), world.raycastBlock([worldUuid],ox,oy,oz,dx,dy,dz[,maxDist,step]), world.broadcast(uuid, msg), world.canControl()"));
            context.sender().sendMessage(Message.raw(" - network.send(player, channel, payload), network.sendAll(channel, payload, [worldUuid]), network.refer(player, host, port), network.referAll(host, port, [worldUuid]), network.on(channel, fn), network.off(channel[, fn]), network.list(), network.emit(channel, payload), network.allowed(channel), network.policy(channel), network.canControl()"));
            context.sender().sendMessage(Message.raw(" - webhook.request(method, url, [body], [contentType], [timeoutMs], [headers]), webhook.postJson(url, payload, [timeoutMs], [headers]), webhook.canControl()"));
            context.sender().sendMessage(Message.raw(" - ui.actionbar(player, msg), ui.actionbarAll(msg, [worldUuid]), ui.title(player, title, subtitle, [fadeIn], [stay], [fadeOut]), ui.titleAll(title, subtitle, [fadeIn], [stay], [fadeOut], [worldUuid]), ui.form(player, id, payload), ui.formAll(id, payload, [worldUuid]), ui.panel(player, id, payload), ui.panelAll(id, payload, [worldUuid]), ui.canControl()"));
            context.sender().sendMessage(Message.raw(" - arcane.apiVersion, arcane.loaderVersion, arcane.modId, arcane.capabilities, arcane.hasCapability(name)"));
            context.sender().sendMessage(Message.raw(" - capability checks: players.canMove(), entities.canControl(), world.canControl(), network.canControl(), ui.canControl()"));
            context.sender().sendMessage(Message.raw(" - ctx:log(message)"));
            context.sender().sendMessage(Message.raw(" - ctx:command(name, help, fn(sender, args))"));
            context.sender().sendMessage(Message.raw(" - ctx:on(event[, options], fn(...)), ctx:off(event[, fn])"));
            context.sender().sendMessage(Message.raw(" - ctx:setTimeout(ms[, label], fn), ctx:setInterval(ms[, label], fn), ctx:cancelTimer(handle), ctx:timerActive(handle), ctx:timers(), ctx:timerCount()"));
            context.sender().sendMessage(Message.raw(" - ctx:dataDir(), ctx:readText(path), ctx:writeText(path, text), ctx:appendText(path, text)"));
            context.sender().sendMessage(Message.raw(" - registry.define(id[, options]), registry.find(id), registry.list(), registry.listByKind(kind), registry.kinds(), registry.put(id, key, value), registry.get(id, key), registry.has(id, key), registry.size(id), registry.entries(id), registry.keys(id), registry.removeEntry(id, key), registry.remove(id), registry.clear()"));
            context.sender().sendMessage(Message.raw(" - volumes.move(volumeId, vec3), mechanics.move(nodeId, vec3)"));
            context.sender().sendMessage(Message.raw("Command bridge: /lua call <modId> <command> [args...]"));
            context.sender().sendMessage(Message.raw("Dev command: /lua eval <code> (sandboxed, devMode only)."));
            context.sender().sendMessage(Message.raw("Debug commands: /lua debug [on|off|toggle], /lua profile [reset [modId]|dump], /lua trace <modId> <event:name|network:name|command:name|list|clear|off key>"));
            context.sender().sendMessage(Message.raw("Security command: /lua caps [modId]"));
            context.sender().sendMessage(Message.raw("Network command: /lua netstats [channel|reset]"));
            context.sender().sendMessage(Message.raw("Config command: /lua config [reload]"));
            context.sender().sendMessage(Message.raw("Policy command: /lua policy <modId> <channel> | /lua policy allow|deny|clear|cap|list ..."));
            context.sender().sendMessage(Message.raw("Verification command: /lua verify [benchPrefix]"));
            context.sender().sendMessage(Message.raw("Health command: /lua doctor"));
            context.sender().sendMessage(Message.raw("Authoring commands: /lua new <modId> [blank|command|event], /lua check [modId|all], /lua inspect <modId>, /lua errors <modId> detail"));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class CallSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private CallSub(ArcaneLoaderPlugin plugin) {
            super("call", "Call a Lua-registered command: /lua call <modId> <command> [args...]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            String[] args = extractArgs(context);
            if (args.length < 2) {
                context.sender().sendMessage(Message.raw("Usage: /lua call <modId> <command> [args...]"));
                return CompletableFuture.completedFuture(null);
            }

            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }

            String modId = args[0];
            String command = args[1];
            List<String> rest = new ArrayList<>();
            for (int i = 2; i < args.length; i++) {
                rest.add(args[i]);
            }

            boolean ok = mm.invokeModCommand(modId, command, context.sender(), rest);
            if (!ok) {
                context.sender().sendMessage(Message.raw("Call failed: unknown mod/command, mod disabled, or command errored."));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class EvalSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private EvalSub(ArcaneLoaderPlugin plugin) {
            super("eval", "Evaluate Lua code (dev only): /lua eval <code>");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            if (!plugin.isDevMode()) {
                context.sender().sendMessage(Message.raw("Eval is disabled when devMode=false."));
                return CompletableFuture.completedFuture(null);
            }

            String[] args = extractArgs(context);
            if (args.length < 1) {
                context.sender().sendMessage(Message.raw("Usage: /lua eval <code>"));
                return CompletableFuture.completedFuture(null);
            }

            String code = String.join(" ", args).trim();
            if (code.isEmpty()) {
                context.sender().sendMessage(Message.raw("Usage: /lua eval <code>"));
                return CompletableFuture.completedFuture(null);
            }

            EvalResult result = evalInDevSandbox(code);
            if (result.ok) {
                context.sender().sendMessage(Message.raw("Eval result: " + result.message));
            } else {
                context.sender().sendMessage(Message.raw("Eval error: " + result.message));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class DebugSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private DebugSub(ArcaneLoaderPlugin plugin) {
            super("debug", "Toggle Lua debug logging");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            String mode = firstArgOrNull(context);
            boolean next;
            if (mode == null || mode.equalsIgnoreCase("toggle")) {
                next = !mm.isDebugLogging();
            } else if (mode.equalsIgnoreCase("on") || mode.equalsIgnoreCase("true")) {
                next = true;
            } else if (mode.equalsIgnoreCase("off") || mode.equalsIgnoreCase("false")) {
                next = false;
            } else {
                context.sender().sendMessage(Message.raw("Usage: /lua debug [on|off|toggle]"));
                return CompletableFuture.completedFuture(null);
            }
            mm.setDebugLogging(next);
            context.sender().sendMessage(Message.raw("Lua debug logging: " + (next ? "ON" : "OFF")));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ProfileSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private ProfileSub(ArcaneLoaderPlugin plugin) {
            super("profile", "Show Lua mod profile snapshot: /lua profile [reset [modId|all]|dump]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();

            String[] args = extractArgs(context);
            if (args.length >= 1 && args[0] != null && args[0].equalsIgnoreCase("reset")) {
                if (args.length >= 2 && args[1] != null && !args[1].isBlank()) {
                    if (args[1].equalsIgnoreCase("all")) {
                        mm.resetProfileMetrics();
                        context.sender().sendMessage(Message.raw("Reset Lua profile metrics for all mods."));
                    } else {
                        boolean ok = mm.resetProfileMetrics(args[1]);
                        if (!ok) {
                            context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + args[1]));
                            return CompletableFuture.completedFuture(null);
                        }
                        context.sender().sendMessage(Message.raw("Reset Lua profile metrics for mod: " + args[1]));
                    }
                } else {
                    mm.resetProfileMetrics();
                    context.sender().sendMessage(Message.raw("Reset Lua profile metrics for all mods."));
                }
                return CompletableFuture.completedFuture(null);
            }
            if (args.length >= 1 && args[0] != null && args[0].equalsIgnoreCase("dump")) {
                try {
                    java.nio.file.Path out = mm.dumpProfileSnapshot(plugin.getLogDir());
                    context.sender().sendMessage(Message.raw("Wrote Lua profile snapshot: " + out));
                } catch (Exception e) {
                    context.sender().sendMessage(Message.raw("Failed writing profile snapshot: " + e));
                }
                return CompletableFuture.completedFuture(null);
            }

            context.sender().sendMessage(Message.raw("Lua profile snapshot:"));
            context.sender().sendMessage(Message.raw(" - debugLogging=" + mm.isDebugLogging()));
            context.sender().sendMessage(Message.raw(" - slowCallWarnMs=" + plugin.getSlowCallWarnMs()));
            boolean any = false;
            int slowMods = 0;
            for (LuaMod mod : mm.listMods()) {
                any = true;
                int commandCount = (mod.ctx() == null) ? 0 : mod.ctx().commandNames().size();
                int errorCount = mod.errorHistory().size();
                long invocations = mod.invocationCount();
                long failures = mod.invocationFailures();
                double avgMs = invocations == 0 ? 0.0 : (mod.totalInvocationNanos() / 1_000_000.0) / invocations;
                double maxMs = mod.maxInvocationNanos() / 1_000_000.0;
                if (mod.slowInvocationCount() > 0) slowMods++;
                context.sender().sendMessage(Message.raw(
                        " - " + mod.manifest().id()
                                + " state=" + mod.state()
                                + " commands=" + commandCount
                                + " errors=" + errorCount
                                + " invocations=" + invocations
                                + " failures=" + failures
                                + " slowCalls=" + mod.slowInvocationCount()
                                + " avgMs=" + String.format(java.util.Locale.ROOT, "%.2f", avgMs)
                                + " maxMs=" + String.format(java.util.Locale.ROOT, "%.2f", maxMs)
                                + " lastLoadMs=" + mod.lastLoadEpochMs()
                ));
                var breakdown = new java.util.ArrayList<>(mod.invocationBreakdown().entrySet());
                breakdown.sort((a, b) -> Long.compare(b.getValue().totalNanos(), a.getValue().totalNanos()));
                int limit = Math.min(3, breakdown.size());
                for (int i = 0; i < limit; i++) {
                    var ent = breakdown.get(i);
                    var stats = ent.getValue();
                    double bucketAvgMs = stats.count() == 0 ? 0.0 : (stats.totalNanos() / 1_000_000.0) / stats.count();
                    double bucketMaxMs = stats.maxNanos() / 1_000_000.0;
                    context.sender().sendMessage(Message.raw(
                            "   > " + ent.getKey()
                                    + " count=" + stats.count()
                                    + " fail=" + stats.failures()
                                    + " slow=" + stats.slowCount()
                                    + " avgMs=" + String.format(java.util.Locale.ROOT, "%.2f", bucketAvgMs)
                                    + " maxMs=" + String.format(java.util.Locale.ROOT, "%.2f", bucketMaxMs)
                    ));
                }
            }
            if (!any) {
                context.sender().sendMessage(Message.raw(" - no mods discovered"));
            } else {
                context.sender().sendMessage(Message.raw(" - slowMods=" + slowMods + "/" + mm.listMods().size()));
                context.sender().sendMessage(Message.raw("Use: /lua profile reset [modId] | /lua profile dump"));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class TraceSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private TraceSub(ArcaneLoaderPlugin plugin) {
            super("trace", "Per-mod trace filters: /lua trace <modId> <event:name|network:name|command:name|list|clear|off key>");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            String[] args = extractArgs(context);
            if (args.length < 2) {
                context.sender().sendMessage(Message.raw("Usage: /lua trace <modId> <event:name|network:name|command:name|list|clear|off key>"));
                return CompletableFuture.completedFuture(null);
            }

            String modId = args[0];
            String action = args[1] == null ? "" : args[1].trim().toLowerCase(java.util.Locale.ROOT);
            if (action.isEmpty()) {
                context.sender().sendMessage(Message.raw("Usage: /lua trace <modId> <event:name|network:name|command:name|list|clear|off key>"));
                return CompletableFuture.completedFuture(null);
            }

            if (action.equals("list")) {
                Set<String> keys = mm.traceKeys(modId);
                if (keys.isEmpty()) {
                    context.sender().sendMessage(Message.raw("No trace filters for mod: " + modId));
                } else {
                    context.sender().sendMessage(Message.raw("Trace filters for " + modId + ": " + String.join(", ", keys)));
                }
                return CompletableFuture.completedFuture(null);
            }

            if (action.equals("clear")) {
                if (!mm.clearTraceKeys(modId)) {
                    context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + modId));
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw("Cleared trace filters for mod: " + modId));
                return CompletableFuture.completedFuture(null);
            }

            if (action.equals("off")) {
                if (args.length < 3) {
                    context.sender().sendMessage(Message.raw("Usage: /lua trace <modId> off <event:name|network:name|command:name>"));
                    return CompletableFuture.completedFuture(null);
                }
                String key = normalizeTraceKeyArg(args[2]);
                if (key == null) {
                    context.sender().sendMessage(Message.raw("Invalid trace key. Use event:name, network:name, or command:name."));
                    return CompletableFuture.completedFuture(null);
                }
                if (!mm.removeTraceKey(modId, key)) {
                    LuaMod mod = mm.findById(modId);
                    if (mod == null) {
                        context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + modId));
                    } else {
                        context.sender().sendMessage(Message.raw("Trace filter was not set: " + key));
                    }
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw("Removed trace filter for " + modId + ": " + key));
                return CompletableFuture.completedFuture(null);
            }

            String key = normalizeTraceKeyArg(action);
            if (key == null) {
                context.sender().sendMessage(Message.raw("Invalid trace key. Use event:name, network:name, or command:name."));
                return CompletableFuture.completedFuture(null);
            }
            if (!mm.addTraceKey(modId, key)) {
                LuaMod mod = mm.findById(modId);
                if (mod == null) {
                    context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + modId));
                } else {
                    context.sender().sendMessage(Message.raw("Trace filter already set or invalid: " + key));
                }
                return CompletableFuture.completedFuture(null);
            }
            context.sender().sendMessage(Message.raw("Added trace filter for " + modId + ": " + key));
            return CompletableFuture.completedFuture(null);
        }

        private static String normalizeTraceKeyArg(String raw) {
            if (raw == null) return null;
            String key = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (key.isEmpty()) return null;
            int colon = key.indexOf(':');
            if (colon <= 0 || colon == key.length() - 1) return null;
            String type = key.substring(0, colon);
            String name = key.substring(colon + 1).trim();
            if (name.isEmpty()) return null;
            if (!type.equals("event") && !type.equals("network") && !type.equals("command")) return null;
            return type + ":" + name;
        }
    }

    private static final class CapsSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private CapsSub(ArcaneLoaderPlugin plugin) {
            super("caps", "Show effective sensitive capabilities: /lua caps [modId|all]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();

            String target = firstArgOrNull(context);
            if (target != null && !target.equalsIgnoreCase("all")) {
                LuaMod mod = mm.findById(target);
                if (mod == null) {
                    context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + target));
                    return CompletableFuture.completedFuture(null);
                }
                printCaps(context, mod.manifest().id(), plugin);
                return CompletableFuture.completedFuture(null);
            }

            context.sender().sendMessage(Message.raw("Sensitive capabilities (restrictSensitiveApis=" + plugin.isRestrictSensitiveApis() + "):"));
            boolean any = false;
            for (LuaMod mod : mm.listMods()) {
                any = true;
                printCaps(context, mod.manifest().id(), plugin);
            }
            if (!any) {
                context.sender().sendMessage(Message.raw(" - no mods discovered"));
            }
            return CompletableFuture.completedFuture(null);
        }

        private static void printCaps(CommandContext context, String modId, ArcaneLoaderPlugin plugin) {
            java.util.Set<String> channelPrefixes = plugin.networkPrefixesForMod(modId);
            context.sender().sendMessage(Message.raw(
                    " - " + modId
                            + " player-movement=" + plugin.canUseCapability(modId, "player-movement")
                            + " entity-control=" + plugin.canUseCapability(modId, "entity-control")
                            + " world-control=" + plugin.canUseCapability(modId, "world-control")
                            + " network-control=" + plugin.canUseCapability(modId, "network-control")
                            + " ui-control=" + plugin.canUseCapability(modId, "ui-control")
                            + " network-prefixes=" + (channelPrefixes.isEmpty() ? "*" : String.join(",", channelPrefixes))
            ));
        }
    }

    private static final class NetstatsSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private NetstatsSub(ArcaneLoaderPlugin plugin) {
            super("netstats", "Show Lua network channel stats: /lua netstats [channel|reset]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();

            String arg = firstArgOrNull(context);
            if (arg != null && arg.equalsIgnoreCase("reset")) {
                mm.resetProfileMetrics();
                context.sender().sendMessage(Message.raw("Reset Lua network stats (via profile metrics reset)."));
                return CompletableFuture.completedFuture(null);
            }

            java.util.Map<String, Agg> channels = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (LuaMod mod : mm.listMods()) {
                for (var ent : mod.invocationBreakdown().entrySet()) {
                    String key = ent.getKey();
                    if (key == null || !key.startsWith("network:")) continue;
                    String channel = key.substring("network:".length());
                    if (channel.isBlank()) channel = "(unknown)";
                    LuaMod.InvocationStats stats = ent.getValue();
                    Agg agg = channels.computeIfAbsent(channel, k -> new Agg());
                    agg.count += stats.count();
                    agg.failures += stats.failures();
                    agg.slow += stats.slowCount();
                    agg.totalNanos += stats.totalNanos();
                    if (stats.maxNanos() > agg.maxNanos) agg.maxNanos = stats.maxNanos();
                    agg.mods.add(mod.manifest().id());
                }
            }

            if (channels.isEmpty()) {
                context.sender().sendMessage(Message.raw("No network channel activity recorded yet."));
                return CompletableFuture.completedFuture(null);
            }

            if (arg != null) {
                Agg agg = channels.get(arg);
                if (agg == null) {
                    context.sender().sendMessage(Message.raw("No stats for network channel: " + arg));
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw(renderChannel(arg, agg)));
                return CompletableFuture.completedFuture(null);
            }

            context.sender().sendMessage(Message.raw("Lua network channel stats:"));
            int shown = 0;
            for (var ent : channels.entrySet()) {
                context.sender().sendMessage(Message.raw(renderChannel(ent.getKey(), ent.getValue())));
                shown++;
                if (shown >= 10) break;
            }
            if (channels.size() > shown) {
                context.sender().sendMessage(Message.raw("... " + (channels.size() - shown) + " more channel(s). Use /lua netstats <channel>."));
            }
            context.sender().sendMessage(Message.raw("Use /lua netstats reset to clear counters."));
            return CompletableFuture.completedFuture(null);
        }

        private static String renderChannel(String channel, Agg agg) {
            double avgMs = agg.count == 0 ? 0.0 : (agg.totalNanos / 1_000_000.0) / agg.count;
            double maxMs = agg.maxNanos / 1_000_000.0;
            return " - " + channel
                    + " count=" + agg.count
                    + " fail=" + agg.failures
                    + " slow=" + agg.slow
                    + " avgMs=" + String.format(java.util.Locale.ROOT, "%.2f", avgMs)
                    + " maxMs=" + String.format(java.util.Locale.ROOT, "%.2f", maxMs)
                    + " mods=" + agg.mods.size();
        }

        private static final class Agg {
            private long count;
            private long failures;
            private long slow;
            private long totalNanos;
            private long maxNanos;
            private final java.util.Set<String> mods = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        }
    }

    private static final class ConfigSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private ConfigSub(ArcaneLoaderPlugin plugin) {
            super("config", "Show/reload Arcane Loader config: /lua config [reload]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            String arg = firstArgOrNull(context);
            if (arg != null && arg.equalsIgnoreCase("reload")) {
                boolean ok = plugin.reloadArcaneConfig();
                context.sender().sendMessage(Message.raw(ok
                        ? "Reloaded arcane-loader.json."
                        : "Failed reloading arcane-loader.json. Check server logs."));
                return CompletableFuture.completedFuture(null);
            }

            context.sender().sendMessage(Message.raw("Arcane Loader config:"));
            context.sender().sendMessage(Message.raw(" - file: " + plugin.getConfigPath()));
            context.sender().sendMessage(Message.raw(" - devMode=" + plugin.isDevMode()));
            context.sender().sendMessage(Message.raw(" - autoReload=" + plugin.isAutoReload()));
            context.sender().sendMessage(Message.raw(" - autoEnable=" + plugin.isAutoEnable()));
            context.sender().sendMessage(Message.raw(" - autoStageAssets=" + plugin.isAutoStageAssets()));
            context.sender().sendMessage(Message.raw(" - slowCallWarnMs=" + plugin.getSlowCallWarnMs()));
            context.sender().sendMessage(Message.raw(" - blockEditBudgetPerTick=" + plugin.getBlockEditBudgetPerTick()));
            context.sender().sendMessage(Message.raw(" - maxQueuedBlockEditsPerMod=" + plugin.getMaxQueuedBlockEditsPerMod()));
            context.sender().sendMessage(Message.raw(" - maxTxBlockEditsPerMod=" + plugin.getMaxTxBlockEditsPerMod()));
            context.sender().sendMessage(Message.raw(" - maxBatchSetOpsPerCall=" + plugin.getMaxBatchSetOpsPerCall()));
            context.sender().sendMessage(Message.raw(" - allowlistEnabled=" + plugin.isAllowlistEnabled() + " allowlistCount=" + plugin.getAllowlist().size()));
            context.sender().sendMessage(Message.raw(" - restrictSensitiveApis=" + plugin.isRestrictSensitiveApis()));
            context.sender().sendMessage(Message.raw(" - caps: playerMovementMods=" + plugin.getPlayerMovementMods().size()
                    + " entityControlMods=" + plugin.getEntityControlMods().size()
                    + " worldControlMods=" + plugin.getWorldControlMods().size()
                    + " networkControlMods=" + plugin.getNetworkControlMods().size()
                    + " uiControlMods=" + plugin.getUiControlMods().size()));
            context.sender().sendMessage(Message.raw(" - networkChannelPolicies=" + plugin.getNetworkChannelPolicies().size()));
            context.sender().sendMessage(Message.raw("Use /lua config reload after editing arcane-loader.json."));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class PolicySub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private PolicySub(ArcaneLoaderPlugin plugin) {
            super("policy", "Inspect/mutate policy: /lua policy <modId> <channel> | allow/deny/clear/cap/list");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();

            String[] args = extractArgs(context);
            if (args.length < 1) {
                context.sender().sendMessage(Message.raw("Usage: /lua policy <modId> <channel>"));
                context.sender().sendMessage(Message.raw("Mutate: /lua policy allow|deny <modId|*> <prefix>"));
                context.sender().sendMessage(Message.raw("Mutate: /lua policy clear <modId|*>"));
                context.sender().sendMessage(Message.raw("Mutate: /lua policy cap <modId> <player-movement|entity-control|world-control|network-control|ui-control> <on|off>"));
                context.sender().sendMessage(Message.raw("Inspect: /lua policy list [modId]"));
                return CompletableFuture.completedFuture(null);
            }

            String op = args[0].trim().toLowerCase(java.util.Locale.ROOT);
            if (op.equals("allow") || op.equals("deny")) {
                if (args.length < 3) {
                    context.sender().sendMessage(Message.raw("Usage: /lua policy " + op + " <modId|*> <prefix>"));
                    return CompletableFuture.completedFuture(null);
                }
                String modId = args[1];
                String prefix = args[2];
                boolean ok = op.equals("allow")
                        ? plugin.addNetworkChannelPrefix(modId, prefix)
                        : plugin.removeNetworkChannelPrefix(modId, prefix);
                if (!ok) {
                    context.sender().sendMessage(Message.raw("Policy update failed. Validate modId/prefix and check logs."));
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw((op.equals("allow") ? "Added" : "Removed") + " network prefix policy: mod=" + modId + " prefix=" + prefix));
                return CompletableFuture.completedFuture(null);
            }

            if (op.equals("clear")) {
                if (args.length < 2) {
                    context.sender().sendMessage(Message.raw("Usage: /lua policy clear <modId|*>"));
                    return CompletableFuture.completedFuture(null);
                }
                boolean ok = plugin.clearNetworkChannelPolicy(args[1]);
                if (!ok) {
                    context.sender().sendMessage(Message.raw("Policy clear failed. Validate modId and check logs."));
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw("Cleared network policy for mod=" + args[1]));
                return CompletableFuture.completedFuture(null);
            }

            if (op.equals("cap")) {
                if (args.length < 4) {
                    context.sender().sendMessage(Message.raw("Usage: /lua policy cap <modId> <player-movement|entity-control|world-control|network-control|ui-control> <on|off>"));
                    return CompletableFuture.completedFuture(null);
                }
                String modId = args[1];
                String capability = args[2];
                String mode = args[3].trim().toLowerCase(java.util.Locale.ROOT);
                boolean enabled;
                if (mode.equals("on") || mode.equals("true") || mode.equals("1")) {
                    enabled = true;
                } else if (mode.equals("off") || mode.equals("false") || mode.equals("0")) {
                    enabled = false;
                } else {
                    context.sender().sendMessage(Message.raw("Capability mode must be on/off."));
                    return CompletableFuture.completedFuture(null);
                }
                boolean ok = plugin.setCapabilityGrant(modId, capability, enabled);
                if (!ok) {
                    context.sender().sendMessage(Message.raw("Capability update failed. Validate modId/capability and check logs."));
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw("Updated capability: mod=" + modId + " capability=" + capability + " enabled=" + enabled));
                return CompletableFuture.completedFuture(null);
            }

            if (op.equals("list")) {
                String target = args.length >= 2 ? args[1] : null;
                if (target != null && !target.isBlank()) {
                    printPolicySnapshot(context, target);
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw("Policy snapshot:"));
                for (LuaMod mod : mm.listMods()) {
                    printPolicySnapshot(context, mod.manifest().id());
                }
                printPolicySnapshot(context, "*");
                return CompletableFuture.completedFuture(null);
            }

            if (args.length < 2) {
                context.sender().sendMessage(Message.raw("Usage: /lua policy <modId> <channel>"));
                return CompletableFuture.completedFuture(null);
            }

            String modId = args[0];
            String channel = args[1];

            boolean hasCap = plugin.canUseCapability(modId, "network-control");
            java.util.Set<String> prefixes = plugin.networkPrefixesForMod(modId);
            ArcaneLoaderPlugin.NetworkChannelDecision decision = plugin.evaluateNetworkChannel(modId, channel);

            context.sender().sendMessage(Message.raw("Policy check for mod=" + modId + " channel=" + channel));
            context.sender().sendMessage(Message.raw(" - network-control=" + hasCap));
            context.sender().sendMessage(Message.raw(" - prefixes=" + (prefixes.isEmpty() ? "*" : String.join(",", prefixes))));
            context.sender().sendMessage(Message.raw(" - matchedPrefix=" + (decision.matchedPrefix() == null ? "(none)" : decision.matchedPrefix())));
            context.sender().sendMessage(Message.raw(" - reason=" + decision.reason()));
            context.sender().sendMessage(Message.raw(" - allowed=" + decision.allowed()));
            return CompletableFuture.completedFuture(null);
        }

        private void printPolicySnapshot(CommandContext context, String modId) {
            java.util.Set<String> prefixes = plugin.networkPrefixesForMod(modId);
            context.sender().sendMessage(Message.raw(
                    " - " + modId
                            + " player-movement=" + plugin.canUseCapability(modId, "player-movement")
                            + " entity-control=" + plugin.canUseCapability(modId, "entity-control")
                            + " world-control=" + plugin.canUseCapability(modId, "world-control")
                            + " network-control=" + plugin.canUseCapability(modId, "network-control")
                            + " ui-control=" + plugin.canUseCapability(modId, "ui-control")
                            + " prefixes=" + (prefixes.isEmpty() ? "*" : String.join(",", prefixes))
            ));
        }
    }

    private static final class DoctorSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private DoctorSub(ArcaneLoaderPlugin plugin) {
            super("doctor", "Run Arcane Loader health checks");
            this.plugin = plugin;
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }

            int warnings = 0;
            int errors = 0;
            context.sender().sendMessage(Message.raw("Arcane Loader doctor report:"));

            if (java.nio.file.Files.isDirectory(plugin.getLuaModsDir())) {
                context.sender().sendMessage(Message.raw(" - OK lua_mods dir: " + plugin.getLuaModsDir()));
            } else {
                errors++;
                context.sender().sendMessage(Message.raw(" - ERROR missing lua_mods dir: " + plugin.getLuaModsDir()));
            }
            if (java.nio.file.Files.isDirectory(plugin.getLuaCacheDir())) {
                context.sender().sendMessage(Message.raw(" - OK lua_cache dir: " + plugin.getLuaCacheDir()));
            } else {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN missing lua_cache dir: " + plugin.getLuaCacheDir()));
            }
            if (java.nio.file.Files.isDirectory(plugin.getLuaDataDir())) {
                context.sender().sendMessage(Message.raw(" - OK lua_data dir: " + plugin.getLuaDataDir()));
            } else {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN missing lua_data dir: " + plugin.getLuaDataDir()));
            }
            if (java.nio.file.Files.exists(plugin.getConfigPath())) {
                context.sender().sendMessage(Message.raw(" - OK config file: " + plugin.getConfigPath()));
            } else {
                errors++;
                context.sender().sendMessage(Message.raw(" - ERROR missing config file: " + plugin.getConfigPath()));
            }
            if (plugin.isAutoReload() == plugin.isWatcherActive()) {
                context.sender().sendMessage(Message.raw(" - OK autoReload watcher state aligned (autoReload=" + plugin.isAutoReload() + ")."));
            } else {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN autoReload watcher mismatch (autoReload=" + plugin.isAutoReload() + ", watcherActive=" + plugin.isWatcherActive() + ")."));
            }

            if (plugin.isAllowlistEnabled() && plugin.getAllowlist().isEmpty()) {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN allowlistEnabled=true but allowlist is empty (no mods can load)."));
            }
            if (plugin.isRestrictSensitiveApis()) {
                if (plugin.getPlayerMovementMods().isEmpty()) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN playerMovementMods is empty while restrictions are enabled."));
                }
                if (plugin.getEntityControlMods().isEmpty()) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN entityControlMods is empty while restrictions are enabled."));
                }
                if (plugin.getWorldControlMods().isEmpty()) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN worldControlMods is empty while restrictions are enabled."));
                }
                if (plugin.getNetworkControlMods().isEmpty()) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN networkControlMods is empty while restrictions are enabled."));
                }
                if (plugin.getUiControlMods().isEmpty()) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN uiControlMods is empty while restrictions are enabled."));
                }
            }
            if (plugin.getSlowCallWarnMs() <= 0.0) {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN slowCallWarnMs <= 0 (slow-call tracking effectively disabled)."));
            }

            mm.scanMods();
            int totalMods = 0;
            int enabledMods = 0;
            int errorMods = 0;
            java.util.Set<String> discoveredIds = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (LuaMod mod : mm.listMods()) {
                totalMods++;
                if (mod.state() == arcane.loader.lua.LuaModState.ENABLED) enabledMods++;
                if (mod.state() == arcane.loader.lua.LuaModState.ERROR) errorMods++;
                discoveredIds.add(mod.manifest().id());
            }
            if (totalMods == 0) {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN no mods discovered in lua_mods."));
            } else {
                context.sender().sendMessage(Message.raw(" - OK mods discovered=" + totalMods + " enabled=" + enabledMods + " error=" + errorMods));
            }
            if (errorMods > 0) {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN one or more mods are in ERROR state. Use /lua errors."));
            }

            warnings += warnStaleIds(context, "playerMovementMods", plugin.getPlayerMovementMods(), discoveredIds);
            warnings += warnStaleIds(context, "entityControlMods", plugin.getEntityControlMods(), discoveredIds);
            warnings += warnStaleIds(context, "worldControlMods", plugin.getWorldControlMods(), discoveredIds);
            warnings += warnStaleIds(context, "networkControlMods", plugin.getNetworkControlMods(), discoveredIds);
            warnings += warnStaleIds(context, "uiControlMods", plugin.getUiControlMods(), discoveredIds);

            if (!plugin.getNetworkChannelPolicies().isEmpty()) {
                java.util.Set<String> wildcard = plugin.getNetworkChannelPolicies().get("*");
                if (wildcard == null || wildcard.isEmpty()) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN networkChannelPolicies has no '*' fallback; unspecified mods may be blocked."));
                } else {
                    context.sender().sendMessage(Message.raw(" - OK networkChannelPolicies wildcard prefixes=" + String.join(",", wildcard)));
                }
                for (String policyModId : plugin.getNetworkChannelPolicies().keySet()) {
                    if (policyModId.equals("*")) continue;
                    if (!discoveredIds.contains(policyModId)) {
                        warnings++;
                        context.sender().sendMessage(Message.raw(" - WARN networkChannelPolicies contains unknown mod id: " + policyModId));
                    }
                }
            } else {
                context.sender().sendMessage(Message.raw(" - INFO networkChannelPolicies not configured (channels unrestricted after capability check)."));
            }

            context.sender().sendMessage(Message.raw("Doctor summary: errors=" + errors + " warnings=" + warnings));
            if (errors == 0 && warnings == 0) {
                context.sender().sendMessage(Message.raw("Doctor status: HEALTHY"));
            } else if (errors == 0) {
                context.sender().sendMessage(Message.raw("Doctor status: OK_WITH_WARNINGS"));
            } else {
                context.sender().sendMessage(Message.raw("Doctor status: NEEDS_ATTENTION"));
            }
            return CompletableFuture.completedFuture(null);
        }

        private static int warnStaleIds(CommandContext context, String label, java.util.Set<String> configuredIds, java.util.Set<String> discoveredIds) {
            if (configuredIds == null || configuredIds.isEmpty()) return 0;
            int warnings = 0;
            for (String id : configuredIds) {
                if (id == null || id.isBlank()) continue;
                if (!discoveredIds.contains(id)) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN " + label + " contains unknown mod id: " + id));
                }
            }
            return warnings;
        }
    }

    private static final class VerifySub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private VerifySub(ArcaneLoaderPlugin plugin) {
            super("verify", "Runtime integration assertions: /lua verify [benchPrefix]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();

            String prefix = firstArgOrNull(context);
            if (prefix == null) prefix = "bench_";
            final String prefixNorm = prefix.toLowerCase(java.util.Locale.ROOT);

            int failures = 0;
            int checked = 0;
            java.util.List<LuaMod> matched = new java.util.ArrayList<>();
            for (LuaMod mod : mm.listMods()) {
                if (mod.manifest().id().toLowerCase(java.util.Locale.ROOT).startsWith(prefixNorm)) {
                    matched.add(mod);
                }
            }
            matched.sort(java.util.Comparator.comparing(m -> m.manifest().id(), String.CASE_INSENSITIVE_ORDER));
            if (matched.isEmpty()) {
                context.sender().sendMessage(Message.raw("No mods found for prefix: " + prefix));
                return CompletableFuture.completedFuture(null);
            }

            context.sender().sendMessage(Message.raw("Runtime verify for " + matched.size() + " mod(s), prefix=" + prefix));
            for (LuaMod mod : matched) {
                checked++;
                String id = mod.manifest().id();
                boolean enabled = mod.state() == arcane.loader.lua.LuaModState.ENABLED;
                if (!enabled) {
                    failures++;
                    context.sender().sendMessage(Message.raw(" - FAIL " + id + " state=" + mod.state()));
                    continue;
                }
                if (mod.ctx() == null || mod.globals() == null || mod.module() == null) {
                    failures++;
                    context.sender().sendMessage(Message.raw(" - FAIL " + id + " missing active runtime references"));
                    continue;
                }
                long tickEvents = mod.invocationBreakdown().getOrDefault("event:tick", new LuaMod.InvocationStats(0, 0, 0, 0, 0)).count();
                if (tickEvents == 0) {
                    failures++;
                    context.sender().sendMessage(Message.raw(" - FAIL " + id + " no event:tick invocations recorded"));
                    continue;
                }
                context.sender().sendMessage(Message.raw(" - OK " + id + " tickEvents=" + tickEvents + " failures=" + mod.invocationFailures()));
            }

            int chainChecked = 0;
            for (int i = 1; i < matched.size(); i++) {
                LuaMod curr = matched.get(i);
                LuaMod prev = matched.get(i - 1);
                if (!curr.manifest().id().matches(".*\\d+$")) continue;
                chainChecked++;
                boolean hasExpectedAfter = curr.manifest().loadAfter().contains(prev.manifest().id());
                if (!hasExpectedAfter) {
                    failures++;
                    context.sender().sendMessage(Message.raw(" - FAIL chain " + curr.manifest().id() + " missing loadAfter " + prev.manifest().id()));
                }
            }

            context.sender().sendMessage(Message.raw("Verify summary: checked=" + checked + " chainChecked=" + chainChecked + " failures=" + failures));
            context.sender().sendMessage(Message.raw(failures == 0 ? "Verify status: PASS" : "Verify status: FAIL"));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class CheckSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private CheckSub(ArcaneLoaderPlugin plugin) {
            super("check", "Preflight Lua mod diagnostics: /lua check [modId|all]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            String[] args = extractArgs(context);
            String target = args.length >= 1 ? args[0] : null;
            if (target != null && target.equalsIgnoreCase("all")) target = null;
            boolean dump = false;
            for (String arg : args) {
                if (arg != null && arg.equalsIgnoreCase("dump")) {
                    dump = true;
                    break;
                }
            }
            List<ModCheckResult> results = runModChecks(plugin, target);
            if (results.isEmpty()) {
                context.sender().sendMessage(Message.raw("No Lua mods found to check."));
                context.sender().sendMessage(Message.raw("Hint: use /lua new my_first_mod to create a starter mod."));
                return CompletableFuture.completedFuture(null);
            }

            int errorCount = 0;
            int warnCount = 0;
            int checked = 0;
            for (ModCheckResult result : results) {
                checked++;
                String modLabel = result.manifest() != null && result.manifest().id() != null
                        ? result.manifest().id()
                        : result.folderName();
                context.sender().sendMessage(Message.raw(
                        "Check " + modLabel
                                + ": luaFiles=" + result.luaFileCount()
                                + " entry=" + (result.manifest() == null ? "(unknown)" : result.manifest().entry())
                                + " path=" + result.dir()
                ));
                if (result.issues().isEmpty()) {
                    context.sender().sendMessage(Message.raw(" - OK no issues found"));
                    continue;
                }
                for (DiagnosticIssue issue : result.issues()) {
                    if ("ERROR".equals(issue.severity())) errorCount++;
                    else if ("WARN".equals(issue.severity())) warnCount++;
                    context.sender().sendMessage(Message.raw(renderIssue(issue)));
                }
            }
            context.sender().sendMessage(Message.raw(
                    "Check summary: checked=" + checked + " errors=" + errorCount + " warnings=" + warnCount
            ));
            context.sender().sendMessage(Message.raw(
                    errorCount == 0 ? (warnCount == 0 ? "Check status: CLEAN" : "Check status: WARNINGS") : "Check status: FAIL"
            ));
            if (dump) {
                try {
                    Path out = writeCheckReport(plugin, results);
                    context.sender().sendMessage(Message.raw("Wrote check report: " + out));
                } catch (Exception e) {
                    context.sender().sendMessage(Message.raw("Failed writing check report: " + e));
                }
            } else {
                context.sender().sendMessage(Message.raw("Use /lua check " + (target == null ? "all" : target) + " dump for JSON report."));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class InspectSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private InspectSub(ArcaneLoaderPlugin plugin) {
            super("inspect", "Inspect one Lua mod: /lua inspect <modId>");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();
            String modId = firstArgOrNull(context);
            if (modId == null) {
                context.sender().sendMessage(Message.raw("Usage: /lua inspect <modId>"));
                return CompletableFuture.completedFuture(null);
            }
            LuaMod mod = mm.findById(modId);
            if (mod == null) {
                context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + modId));
                return CompletableFuture.completedFuture(null);
            }

            context.sender().sendMessage(Message.raw("Inspect " + mod.manifest().id() + ":"));
            context.sender().sendMessage(Message.raw(" - state=" + mod.state() + " version=" + mod.manifest().version() + " entry=" + mod.manifest().entry()));
            context.sender().sendMessage(Message.raw(" - dir=" + mod.manifest().dir()));
            context.sender().sendMessage(Message.raw(" - loadBefore=" + (mod.manifest().loadBefore().isEmpty() ? "(none)" : String.join(",", mod.manifest().loadBefore()))));
            context.sender().sendMessage(Message.raw(" - loadAfter=" + (mod.manifest().loadAfter().isEmpty() ? "(none)" : String.join(",", mod.manifest().loadAfter()))));
            context.sender().sendMessage(Message.raw(" - lastLoadMs=" + mod.lastLoadEpochMs()));
            context.sender().sendMessage(Message.raw(" - errors=" + mod.errorHistory().size() + (mod.lastError() == null ? "" : " lastError=" + truncate(mod.lastError(), 140))));
            context.sender().sendMessage(Message.raw(" - traceKeys=" + (mod.traceKeys().isEmpty() ? "(none)" : String.join(",", mod.traceKeys()))));

            if (mod.ctx() != null) {
                context.sender().sendMessage(Message.raw(" - commands=" + (mod.ctx().commandNames().isEmpty() ? "(none)" : String.join(",", new TreeSet<>(mod.ctx().commandNames())))));
                context.sender().sendMessage(Message.raw(" - eventNames=" + (mod.ctx().eventNames().isEmpty() ? "(none)" : String.join(",", new TreeSet<>(mod.ctx().eventNames())))));
                context.sender().sendMessage(Message.raw(" - eventListeners=" + mod.ctx().totalEventListeners()));
                context.sender().sendMessage(Message.raw(" - activeTimers=" + mod.ctx().activeTaskCount()));
                context.sender().sendMessage(Message.raw(" - registries=" + mod.ctx().manager().registry().list(mod.ctx()).size()));
                context.sender().sendMessage(Message.raw(" - standins=" + mod.ctx().manager().standins().count(mod.ctx())));
                context.sender().sendMessage(Message.raw(" - dataDir=" + mod.ctx().dataDir()));
                context.sender().sendMessage(Message.raw(" - assetCount=" + mod.ctx().listAssets("", 1000).size()));
            } else {
                context.sender().sendMessage(Message.raw(" - runtime=(inactive)"));
            }

            long invocations = mod.invocationCount();
            double avgMs = invocations == 0 ? 0.0 : (mod.totalInvocationNanos() / 1_000_000.0) / invocations;
            context.sender().sendMessage(Message.raw(
                    " - invocations=" + invocations
                            + " failures=" + mod.invocationFailures()
                            + " slowCalls=" + mod.slowInvocationCount()
                            + " avgMs=" + String.format(Locale.ROOT, "%.2f", avgMs)
                            + " maxMs=" + String.format(Locale.ROOT, "%.2f", mod.maxInvocationNanos() / 1_000_000.0)
            ));
            var breakdown = new ArrayList<>(mod.invocationBreakdown().entrySet());
            breakdown.sort((a, b) -> Long.compare(b.getValue().totalNanos(), a.getValue().totalNanos()));
            int limit = Math.min(5, breakdown.size());
            if (limit > 0) {
                context.sender().sendMessage(Message.raw("Top invocation buckets:"));
                for (int i = 0; i < limit; i++) {
                    var ent = breakdown.get(i);
                    var stats = ent.getValue();
                    double bucketAvgMs = stats.count() == 0 ? 0.0 : (stats.totalNanos() / 1_000_000.0) / stats.count();
                    context.sender().sendMessage(Message.raw(
                            " - " + ent.getKey()
                                    + " count=" + stats.count()
                                    + " fail=" + stats.failures()
                                    + " slow=" + stats.slowCount()
                                    + " avgMs=" + String.format(Locale.ROOT, "%.2f", bucketAvgMs)
                    ));
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
