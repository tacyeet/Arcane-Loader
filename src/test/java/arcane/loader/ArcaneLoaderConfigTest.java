package arcane.loader;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArcaneLoaderConfigTest {

    @Test
    void parseNormalizesPoliciesAndPreservesFallbacks() {
        String json = """
                {
                  "autoReload": true,
                  "allowlist": ["beta", "Alpha", "alpha"],
                  "networkChannelPolicies": {
                    "ChatMod": ["Arcane.", "chat.", "bad prefix"]
                  }
                }
                """;

        ArcaneLoaderConfig config = ArcaneLoaderConfig.parse(json, ArcaneLoaderConfig.DEFAULTS);

        assertTrue(config.devMode());
        assertTrue(config.autoReload());
        assertEquals(Set.of("Alpha", "beta"), config.allowlist());
        assertEquals(Map.of("chatmod", Set.of("arcane.", "chat.")), config.networkChannelPolicies());
        assertEquals(64, config.maxBlockBehaviorNeighborUpdatesPerCause());
    }

    @Test
    void toJsonRoundTripsStructuredFields() {
        ArcaneLoaderConfig config = new ArcaneLoaderConfig(
                false,
                true,
                true,
                false,
                12.5,
                42,
                100,
                200,
                300,
                12,
                true,
                true,
                Set.of("mod-b", "mod-a"),
                Set.of("move"),
                Set.of("entity"),
                Set.of("world"),
                Set.of("network"),
                Set.of("ui"),
                Map.of("*", Set.of("arcane."), "chat", Set.of("chat.", "custom."))
        );

        ArcaneLoaderConfig parsed = ArcaneLoaderConfig.parse(config.toJson(), ArcaneLoaderConfig.DEFAULTS);

        assertEquals(false, parsed.devMode());
        assertEquals(true, parsed.autoReload());
        assertEquals(true, parsed.allowlistEnabled());
        assertEquals(Set.of("mod-a", "mod-b"), parsed.allowlist());
        assertEquals(Set.of("chat.", "custom."), parsed.networkChannelPolicies().get("chat"));
        assertEquals(Set.of("arcane."), parsed.networkChannelPolicies().get("*"));
    }
}
