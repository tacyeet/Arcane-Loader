package arcane.loader.lua;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class LuaAssetManagerTest {

    @Test
    void sourceFingerprintChangesWhenAssetSourceChanges() throws Exception {
        Path root = Files.createTempDirectory("arcane-assets-test");
        Path assetsDir = Files.createDirectories(root.resolve("assets"));
        Path file = assetsDir.resolve("model.txt");
        Files.writeString(file, "one");

        String before = LuaAssetManager.assetMark(assetsDir, root.resolve("assets.zip"), true, false);

        Files.writeString(file, "two");
        String afterContentChange = LuaAssetManager.assetMark(assetsDir, root.resolve("assets.zip"), true, false);

        Path nested = Files.createDirectories(assetsDir.resolve("nested"));
        Files.writeString(nested.resolve("other.txt"), "three");
        String afterStructureChange = LuaAssetManager.assetMark(assetsDir, root.resolve("assets.zip"), true, false);

        assertNotEquals(before, afterContentChange);
        assertNotEquals(afterContentChange, afterStructureChange);
    }

    @Test
    void sourceFingerprintIsStableForUnchangedSource() throws Exception {
        Path root = Files.createTempDirectory("arcane-assets-stable");
        Path assetsDir = Files.createDirectories(root.resolve("assets"));
        Files.writeString(assetsDir.resolve("texture.txt"), "same");

        String left = LuaAssetManager.assetMark(assetsDir, root.resolve("assets.zip"), true, false);
        String right = LuaAssetManager.assetMark(assetsDir, root.resolve("assets.zip"), true, false);

        assertEquals(left, right);
    }
}
