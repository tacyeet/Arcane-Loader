package arcane.loader.lua;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LuaLifecyclePlanner {
    private LuaLifecyclePlanner() {}

    static List<Action> planEnableAll(List<LuaMod> orderedMods) {
        ArrayList<Action> plan = new ArrayList<>();
        for (LuaMod mod : orderedMods) {
            if (mod != null && mod.state() != LuaModState.ENABLED) {
                plan.add(new Action(Type.ENABLE, mod));
            }
        }
        return Collections.unmodifiableList(plan);
    }

    static List<Action> planDisableAll(List<LuaMod> orderedMods) {
        ArrayList<Action> plan = new ArrayList<>();
        for (int i = orderedMods.size() - 1; i >= 0; i--) {
            LuaMod mod = orderedMods.get(i);
            if (mod != null && mod.state() == LuaModState.ENABLED) {
                plan.add(new Action(Type.DISABLE, mod));
            }
        }
        return Collections.unmodifiableList(plan);
    }

    static List<Action> planReloadAll(List<LuaMod> orderedMods) {
        ArrayList<Action> plan = new ArrayList<>();
        for (LuaMod mod : orderedMods) {
            if (mod == null) continue;
            plan.add(new Action(mod.state() == LuaModState.ENABLED ? Type.RELOAD : Type.ENABLE, mod));
        }
        return Collections.unmodifiableList(plan);
    }

    enum Type {
        ENABLE,
        RELOAD,
        DISABLE
    }

    record Action(Type type, LuaMod mod) {}
}
