package arcane.loader.lua;

final class LuaEnableTransaction {
    private final LuaMod mod;
    private final LuaLoadedInstance instance;

    LuaEnableTransaction(LuaMod mod, LuaLoadedInstance instance) {
        this.mod = mod;
        this.instance = instance;
    }

    void markLoaded() {
        mod.state(LuaModState.LOADED);
    }

    void enableNext() {
        LuaModuleHooks.callOptional(instance.module(), "onEnable", instance.ctx());
    }

    void commit(long loadEpochMs) {
        LuaLiveModState.applyLoadedInstance(mod, instance, loadEpochMs);
    }

    void fail(String prefix, Throwable error) {
        LuaRuntimeErrors.record(mod, prefix, error);
        mod.state(LuaModState.ERROR);
    }
}
