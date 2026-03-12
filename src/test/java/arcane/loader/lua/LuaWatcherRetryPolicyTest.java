package arcane.loader.lua;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaWatcherRetryPolicyTest {

    @Test
    void retriesWhenReloadIntroducesNewErrorFingerprint() {
        LuaMod mod = mod("alpha");
        mod.lastError("new failure");

        assertTrue(LuaWatcherRetryPolicy.shouldRetry(Set.of(), LuaWatcherRetryPolicy.fingerprints(java.util.List.of(mod))));
    }

    @Test
    void doesNotRetryWhenOnlyPreexistingErrorsRemain() {
        Set<String> before = Set.of("alpha|stuck");
        Set<String> after = Set.of("alpha|stuck");

        assertFalse(LuaWatcherRetryPolicy.shouldRetry(before, after));
    }

    private static LuaMod mod(String id) {
        return new LuaMod(new ModManifest(id, id, "1.0.0", "init.lua", Set.of(), Set.of(), Path.of(id)));
    }
}
