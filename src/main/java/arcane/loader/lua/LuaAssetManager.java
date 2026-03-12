package arcane.loader.lua;

import arcane.loader.ArcaneLoaderPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stages mod-local assets into a stable server directory (`lua_assets/<modId>`).
 *
 * Supported inputs inside each mod directory:
 * - assets/ (directory, copied recursively)
 * - assets.zip (optional, extracted after folder copy, can override files)
 */
public final class LuaAssetManager {

    private final ArcaneLoaderPlugin plugin;
    private final Map<String, AssetBundle> bundles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String> assetMarks = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public LuaAssetManager(ArcaneLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized AssetBundle stage(ModManifest manifest) {
        if (manifest == null) return null;
        String modId = manifest.id();
        Path sourceDir = manifest.dir().resolve("assets");
        Path sourceZip = manifest.dir().resolve("assets.zip");
        boolean hasDir = Files.isDirectory(sourceDir);
        boolean hasZip = Files.isRegularFile(sourceZip);

        Path stageDir = plugin.getLuaAssetsDir().resolve(modId).normalize();
        try {
            String assetMark = assetMark(sourceDir, sourceZip, hasDir, hasZip);
            AssetBundle existing = bundles.get(modId);
            if (existing != null && assetMark.equals(assetMarks.get(modId)) && Files.isDirectory(stageDir)) {
                return existing;
            }

            deleteTree(stageDir);
            if (!hasDir && !hasZip) {
                bundles.remove(modId);
                assetMarks.remove(modId);
                return null;
            }

            Files.createDirectories(stageDir);
            if (hasDir) {
                copyTree(sourceDir, stageDir);
            }
            if (hasZip) {
                unzipTo(sourceZip, stageDir);
            }

            AssetStats stats = collectStats(stageDir);
            writeIndex(stageDir, modId, hasDir, hasZip, stats);
            AssetBundle bundle = new AssetBundle(
                    modId,
                    stageDir,
                    stats.fileCount,
                    stats.modelCount,
                    stats.textureCount,
                    Instant.now().toString(),
                    hasDir,
                    hasZip
            );
            bundles.put(modId, bundle);
            assetMarks.put(modId, assetMark);
            return bundle;
        } catch (Exception e) {
            deleteTree(stageDir);
            plugin.getLogger().at(Level.WARNING).log("Failed staging assets for mod " + modId + ": " + e);
            bundles.remove(modId);
            assetMarks.remove(modId);
            return null;
        }
    }

    public synchronized void removeMod(String modId) {
        if (modId == null || modId.isBlank()) return;
        bundles.remove(modId);
        assetMarks.remove(modId);
        Path stageDir = plugin.getLuaAssetsDir().resolve(modId).normalize();
        deleteTree(stageDir);
    }

    public synchronized AssetBundle bundle(String modId) {
        if (modId == null) return null;
        return bundles.get(modId);
    }

    public synchronized List<AssetBundle> bundles() {
        return Collections.unmodifiableList(new ArrayList<>(bundles.values()));
    }

    public synchronized boolean assetExists(String modId, String relPath) {
        Path p = assetPath(modId, relPath);
        return p != null && Files.exists(p);
    }

    public synchronized Path assetPath(String modId, String relPath) {
        if (modId == null || modId.isBlank()) return null;
        if (relPath == null || relPath.isBlank()) return null;
        Path root = plugin.getLuaAssetsDir().resolve(modId).normalize();
        String safeRel = relPath.replace('\\', '/');
        if (safeRel.startsWith("/") || safeRel.contains("..")) return null;
        Path p = root.resolve(safeRel).normalize();
        return p.startsWith(root) ? p : null;
    }

    public synchronized List<String> listAssets(String modId, String prefix, int limit) {
        if (modId == null || modId.isBlank()) return List.of();
        Path root = plugin.getLuaAssetsDir().resolve(modId).normalize();
        if (!Files.isDirectory(root)) return List.of();
        String pref = prefix == null ? "" : prefix.trim().replace('\\', '/');
        int cap = Math.max(1, Math.min(20000, limit <= 0 ? 1000 : limit));
        ArrayList<String> out = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!pref.isEmpty() && !rel.startsWith(pref)) continue;
                out.add(rel);
                if (out.size() >= cap) break;
            }
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).log("Failed listing staged assets for mod " + modId + ": " + e);
        }
        return out;
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (var walk = Files.walk(src)) {
            for (Path p : walk.sorted().toList()) {
                Path rel = src.relativize(p);
                Path target = dst.resolve(rel).normalize();
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void unzipTo(Path zip, Path dst) throws IOException {
        try (InputStream in = Files.newInputStream(zip);
             ZipInputStream zis = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.isBlank()) continue;
                Path out = dst.resolve(name).normalize();
                if (!out.startsWith(dst)) continue; // zip-slip protection
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) { }
    }

    static String assetMark(Path sourceDir, Path sourceZip, boolean hasDir, boolean hasZip) throws IOException {
        StringBuilder rage = new StringBuilder();
        rage.append("dir=").append(hasDir).append(';');
        if (hasDir) {
            try (var walk = Files.walk(sourceDir)) {
                for (Path path : walk.sorted().toList()) {
                    var attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
                    rage.append(sourceDir.relativize(path).toString().replace('\\', '/'))
                            .append('|')
                            .append(attrs.isDirectory() ? 'd' : 'f')
                            .append('|');
                    if (attrs.isDirectory()) {
                        rage.append(attrs.lastModifiedTime().toMillis());
                    } else {
                        rage.append(fileHash(path));
                    }
                    rage.append(';');
                }
            }
        }
        rage.append("zip=").append(hasZip).append(';');
        if (hasZip) {
            rage.append(sourceZip.getFileName())
                    .append('|')
                    .append(fileHash(sourceZip))
                    .append(';');
        }
        return rage.toString();
    }

    private static String fileHash(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder rage = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            rage.append(Character.forDigit((value >> 4) & 0xF, 16));
            rage.append(Character.forDigit(value & 0xF, 16));
        }
        return rage.toString();
    }

    private static AssetStats collectStats(Path root) throws IOException {
        AssetStats stats = new AssetStats();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                stats.fileCount++;
                String rel = root.relativize(p).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (rel.contains("/models/") || rel.endsWith(".model.json")) stats.modelCount++;
                if (rel.contains("/textures/")
                        || rel.endsWith(".png")
                        || rel.endsWith(".jpg")
                        || rel.endsWith(".jpeg")
                        || rel.endsWith(".tga")
                        || rel.endsWith(".ktx")) stats.textureCount++;
            }
        }
        return stats;
    }

    private static void writeIndex(Path root, String modId, boolean hasDir, boolean hasZip, AssetStats stats) throws IOException {
        Path index = root.resolve("asset-index.json");
        String json = "{\n"
                + "  \"modId\": \"" + escape(modId) + "\",\n"
                + "  \"generatedAt\": \"" + escape(Instant.now().toString()) + "\",\n"
                + "  \"source\": {\"assetsDir\": " + hasDir + ", \"assetsZip\": " + hasZip + "},\n"
                + "  \"counts\": {\"files\": " + stats.fileCount + ", \"models\": " + stats.modelCount + ", \"textures\": " + stats.textureCount + "}\n"
                + "}\n";
        Files.writeString(index, json, StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class AssetStats {
        private int fileCount;
        private int modelCount;
        private int textureCount;
    }

    public record AssetBundle(
            String modId,
            Path root,
            int fileCount,
            int modelCount,
            int textureCount,
            String stagedAt,
            boolean hasAssetsDir,
            boolean hasAssetsZip
    ) {}
}
