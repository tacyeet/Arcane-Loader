package arcane.loader.lua;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuaProfileSnapshotRendererTest {

    @Test
    void rendersModsInSortedOrderWithBreakdownAndTraces() {
        LuaMod beta = new LuaMod(new ModManifest("beta", "Beta", "1.0.0", "init.lua", Set.of(), Set.of(), Path.of("beta")));
        beta.state(LuaModState.ENABLED);
        beta.lastLoadEpochMs(222L);
        beta.recordInvocation("event:tick", 6_000_000L, false, 5_000_000L);
        beta.recordInvocation("command:ping", 2_000_000L, true, 5_000_000L);
        beta.addTraceKey("network:beta");
        beta.recordError("beta error");

        LuaMod alpha = new LuaMod(new ModManifest("alpha", "Alpha", "1.0.0", "init.lua", Set.of(), Set.of(), Path.of("alpha")));
        alpha.state(LuaModState.DISABLED);
        alpha.lastLoadEpochMs(111L);
        alpha.recordInvocation("event:join", 1_000_000L, false, 5_000_000L);

        String rendered = LuaProfileSnapshotRenderer.render(
                List.of(beta, alpha),
                5.0,
                true,
                Instant.parse("2026-03-12T00:00:00Z"),
                mod -> mod.manifest().id().equals("beta") ? 3 : 1
        );

        assertTrue(rendered.contains("generatedAtUtc=2026-03-12T00:00:00Z"));
        assertTrue(rendered.indexOf("mod=alpha") < rendered.indexOf("mod=beta"));
        assertTrue(rendered.contains("mod=beta state=ENABLED commands=3 errors=1 invocations=2 failures=1 slowCalls=1"));
        assertTrue(rendered.contains("  key=event:tick count=1 fail=0 slow=1 avgMs=6.000 maxMs=6.000"));
        assertTrue(rendered.contains("  key=command:ping count=1 fail=1 slow=0 avgMs=2.000 maxMs=2.000"));
        assertTrue(rendered.contains("  traces=network:beta"));
    }
}
