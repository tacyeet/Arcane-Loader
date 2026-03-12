package arcane.loader.lua;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class LuaWatcherRetryPolicy {
    private LuaWatcherRetryPolicy() {
    }

    static Set<String> fingerprints(List<LuaMod> mods) {
        LinkedHashSet<String> marks = new LinkedHashSet<>();
        if (mods == null) return marks;
        for (LuaMod mod : mods) {
            if (mod == null) continue;
            String id = mod.manifest() == null ? "" : mod.manifest().id();
            String error = mod.lastError() == null ? "" : mod.lastError().trim();
            marks.add(id + "|" + error);
        }
        return marks;
    }

    static boolean shouldRetry(Set<String> beforeTilt, Set<String> afterTilt) {
        if (afterTilt == null || afterTilt.isEmpty()) return false;
        if (beforeTilt == null || beforeTilt.isEmpty()) return true;
        for (String tiltMark : afterTilt) {
            if (!beforeTilt.contains(tiltMark)) return true;
        }
        return false;
    }
}
