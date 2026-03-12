package arcane.loader.lua;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

final class LuaProfileSnapshotRenderer {
    private LuaProfileSnapshotRenderer() {}

    static String render(Iterable<LuaMod> mods, double slowCallWarnMs, boolean debugLogging, Instant generatedAtUtc, ToIntFunction<LuaMod> commandCountProvider) {
        StringBuilder out = new StringBuilder(4096);
        out.append("Arcane Loader Lua Profile Snapshot").append('\n');
        out.append("generatedAtUtc=").append(generatedAtUtc).append('\n');
        out.append("slowCallWarnMs=").append(slowCallWarnMs).append('\n');
        out.append("debugLogging=").append(debugLogging).append('\n');
        out.append('\n');

        ArrayList<LuaMod> modsById = new ArrayList<>();
        for (LuaMod mod : mods) {
            if (mod != null) modsById.add(mod);
        }
        modsById.sort(Comparator.comparing(m -> m.manifest().id(), String.CASE_INSENSITIVE_ORDER));
        for (LuaMod mod : modsById) {
            long invocations = mod.invocationCount();
            double avgMs = invocations == 0 ? 0.0 : (mod.totalInvocationNanos() / 1_000_000.0) / invocations;
            double maxMs = mod.maxInvocationNanos() / 1_000_000.0;
            out.append("mod=").append(mod.manifest().id())
                    .append(" state=").append(mod.state())
                    .append(" commands=").append(Math.max(0, commandCountProvider.applyAsInt(mod)))
                    .append(" errors=").append(mod.errorHistory().size())
                    .append(" invocations=").append(invocations)
                    .append(" failures=").append(mod.invocationFailures())
                    .append(" slowCalls=").append(mod.slowInvocationCount())
                    .append(" avgMs=").append(String.format(Locale.ROOT, "%.3f", avgMs))
                    .append(" maxMs=").append(String.format(Locale.ROOT, "%.3f", maxMs))
                    .append(" lastLoadMs=").append(mod.lastLoadEpochMs())
                    .append('\n');
            List<java.util.Map.Entry<String, LuaMod.InvocationStats>> hotKeys = new ArrayList<>(mod.invocationBreakdown().entrySet());
            hotKeys.sort((a, b) -> Long.compare(b.getValue().totalNanos(), a.getValue().totalNanos()));
            int limit = Math.min(20, hotKeys.size());
            for (int i = 0; i < limit; i++) {
                var ent = hotKeys.get(i);
                var stats = ent.getValue();
                double bucketAvgMs = stats.count() == 0 ? 0.0 : (stats.totalNanos() / 1_000_000.0) / stats.count();
                double bucketMaxMs = stats.maxNanos() / 1_000_000.0;
                out.append("  key=").append(ent.getKey())
                        .append(" count=").append(stats.count())
                        .append(" fail=").append(stats.failures())
                        .append(" slow=").append(stats.slowCount())
                        .append(" avgMs=").append(String.format(Locale.ROOT, "%.3f", bucketAvgMs))
                        .append(" maxMs=").append(String.format(Locale.ROOT, "%.3f", bucketMaxMs))
                        .append('\n');
            }
            if (!mod.traceKeys().isEmpty()) {
                out.append("  traces=").append(String.join(",", mod.traceKeys())).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }
}
