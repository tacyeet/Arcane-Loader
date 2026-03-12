package arcane.loader.lua;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaModuleLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadModuleReadsLuaTableFromEntryFile() throws Exception {
        Path entry = tempDir.resolve("init.lua");
        Files.writeString(entry, """
                return {
                  value = 42
                }
                """);

        LuaTable table = LuaModuleLoader.loadModule(JsePlatform.standardGlobals(), "example", "init.lua", entry);

        assertEquals(42, table.get("value").toint());
    }

    @Test
    void loadModuleRejectsMissingEntryFile() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> LuaModuleLoader.loadModule(JsePlatform.standardGlobals(), "example", "init.lua", tempDir.resolve("missing.lua")));

        assertTrue(error.getMessage().contains("Missing entry file"));
    }

    @Test
    void loadModuleRejectsNonTableReturn() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> LuaModuleLoader.loadModuleFromCode(JsePlatform.standardGlobals(), "example", "init.lua", "return 123"));

        assertEquals("init.lua must return a table (got number)", error.getMessage());
    }
}
