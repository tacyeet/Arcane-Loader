package arcane.loader.lua;

final class LuaLiveModState {
    private LuaLiveModState() {}

    static void applyLoadedInstance(LuaMod mod, LuaLoadedInstance instance, long loadEpochMs) {
        mod.ctx(instance.ctx());
        mod.globals(instance.globals());
        mod.module(instance.module());
        mod.lastError(null);
        mod.lastErrorDetail(null);
        mod.lastLoadEpochMs(loadEpochMs);
        mod.state(LuaModState.ENABLED);
    }
}
