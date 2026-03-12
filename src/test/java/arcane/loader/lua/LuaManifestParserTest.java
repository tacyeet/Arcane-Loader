package arcane.loader.lua;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LuaManifestParserTest {

    @Test
    void parseJsonAppliesDefaultsAndOrderingHints() {
        ModManifest manifest = LuaManifestParser.parseJson(Path.of("example"), """
                {
                  "id": "example",
                  "loadBefore": ["ui", "chat"],
                  "loadAfter": ["core"],
                  "entry": "boot.lua"
                }
                """);

        assertEquals("example", manifest.id());
        assertEquals("example", manifest.name());
        assertEquals("0.0.0", manifest.version());
        assertEquals("boot.lua", manifest.entry());
        assertEquals(Set.of("chat", "ui"), manifest.loadBefore());
        assertEquals(Set.of("core"), manifest.loadAfter());
    }

    @Test
    void parseJsonRejectsMissingId() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LuaManifestParser.parseJson(Path.of("bad"), "{\"name\":\"missing-id\"}"));

        assertEquals("manifest missing id", error.getMessage());
    }
}
