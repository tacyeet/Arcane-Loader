package arcane.loader.lua;

import java.nio.file.Path;
import java.util.Set;

/**
 * Parsed Lua mod manifest data.
 */
public record ModManifest(
        String id,
        String name,
        String version,
        String entry,
        Set<String> loadBefore,
        Set<String> loadAfter,
        Path dir
) {}
