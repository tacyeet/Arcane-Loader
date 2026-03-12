package arcane.loader.lua;

final class LuaReloadTransaction {
    private final LuaMod mod;
    private final LuaReloadState previous;
    private final LuaLoadedInstance next;

    LuaReloadTransaction(LuaMod mod, LuaReloadState previous, LuaLoadedInstance next) {
        this.mod = mod;
        this.previous = previous;
        this.next = next;
    }

    void markLoaded() {
        mod.state(LuaModState.LOADED);
    }

    void disablePrevious() {
        if (previous.module() != null && previous.globals() != null && previous.ctx() != null) {
            LuaModuleHooks.callOptional(previous.module(), "onDisable", previous.ctx());
        }
    }

    void enableNext() {
        LuaModuleHooks.callOptional(next.module(), "onEnable", next.ctx());
    }

    void commit(long loadEpochMs) {
        LuaLiveModState.applyLoadedInstance(mod, next, loadEpochMs);
    }

    void rollback() {
        previous.restore(mod);
        mod.state(LuaModState.ENABLED);
    }
}
