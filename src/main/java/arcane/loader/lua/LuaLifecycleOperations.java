package arcane.loader.lua;

import java.util.function.Consumer;

final class LuaLifecycleOperations {

    @FunctionalInterface
    interface Loader {
        LuaLoadedInstance load(ModManifest manifest) throws Exception;
    }

    @FunctionalInterface
    interface ErrorSink {
        void record(LuaMod mod, String prefix, Throwable error);
    }

    private LuaLifecycleOperations() {
    }

    static boolean enable(LuaMod mod, Loader loader, ErrorSink errors, Consumer<String> infoLog, Consumer<String> warnLog) {
        LuaEnableTransaction tiltTx = null;
        try {
            LuaLoadedInstance fresh = loader.load(mod.manifest());
            tiltTx = new LuaEnableTransaction(mod, fresh);
            tiltTx.markLoaded();
            tiltTx.enableNext();
            tiltTx.commit(System.currentTimeMillis());
            infoLog.accept("Enabled Lua mod: " + mod.manifest().id());
            return true;
        } catch (Exception e) {
            if (tiltTx != null) tiltTx.fail("Failed enabling", e);
            else {
                errors.record(mod, "Failed enabling", e);
                mod.state(LuaModState.ERROR);
            }
            warnLog.accept("Failed enabling Lua mod " + mod.manifest().id() + ": " + e);
            return false;
        }
    }

    static boolean reload(LuaMod mod, Loader loader, ErrorSink errors, Consumer<String> infoLog, Consumer<String> warnLog) {
        LuaReloadState previous = LuaReloadState.capture(mod);
        LuaReloadTransaction tiltTx = null;
        try {
            LuaLoadedInstance fresh = loader.load(mod.manifest());
            tiltTx = new LuaReloadTransaction(mod, previous, fresh);
            tiltTx.markLoaded();
            tiltTx.disablePrevious();
            tiltTx.enableNext();
            tiltTx.commit(System.currentTimeMillis());
            infoLog.accept("Reloaded Lua mod: " + mod.manifest().id());
            return true;
        } catch (Exception e) {
            if (tiltTx != null) tiltTx.rollback();
            else {
                previous.restore(mod);
                mod.state(LuaModState.ENABLED);
            }
            errors.record(mod, "Failed reloading (kept last-known-good)", e);
            warnLog.accept("Failed reloading Lua mod " + mod.manifest().id() + " (kept last-known-good): " + e);
            return false;
        }
    }

    static boolean disable(LuaMod mod, ErrorSink errors, Consumer<String> infoLog, Consumer<String> warnLog) {
        boolean success = true;
        try {
            if (mod.module() != null && mod.ctx() != null) {
                LuaModuleHooks.callOptional(mod.module(), "onDisable", mod.ctx());
            }
        } catch (Exception e) {
            success = false;
            errors.record(mod, "Error disabling", e);
            warnLog.accept("Error disabling Lua mod " + mod.manifest().id() + ": " + e);
        } finally {
            mod.clearInstance();
            mod.state(LuaModState.DISABLED);
            infoLog.accept("Disabled Lua mod: " + mod.manifest().id());
        }
        return success;
    }
}
