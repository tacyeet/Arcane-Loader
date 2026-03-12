package arcane.loader.lua;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaRuntimeErrorsTest {

    @Test
    void recordSetsLastErrorDetailAndHistoryEntry() {
        LuaMod mod = new LuaMod(new ModManifest("test", "Test", "1.0.0", "init.lua", Set.of(), Set.of(), Path.of("test")));
        IllegalStateException error = new IllegalStateException("boom");

        LuaRuntimeErrors.record(mod, "Actor tick error", error);

        assertTrue(mod.lastError().contains("Actor tick error: java.lang.IllegalStateException: boom"));
        assertTrue(mod.lastErrorDetail().contains("IllegalStateException"));
        assertEquals(1, mod.errorHistory().size());
        assertTrue(mod.errorHistory().get(0).contains("Actor tick error: java.lang.IllegalStateException: boom"));
    }

    @Test
    void stacktraceIncludesMessageAndType() {
        String trace = LuaRuntimeErrors.stacktrace(new RuntimeException("trace me"));

        assertTrue(trace.contains("RuntimeException"));
        assertTrue(trace.contains("trace me"));
    }
}
