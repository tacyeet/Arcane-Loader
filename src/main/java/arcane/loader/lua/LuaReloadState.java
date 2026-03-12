package arcane.loader.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;

final class LuaReloadState {
    private final Globals globals;
    private final LuaTable module;
    private final LuaModContext ctx;
    private final LuaModState state;

    private LuaReloadState(Globals globals, LuaTable module, LuaModContext ctx, LuaModState state) {
        this.globals = globals;
        this.module = module;
        this.ctx = ctx;
        this.state = state;
    }

    static LuaReloadState capture(LuaMod mod) {
        return new LuaReloadState(mod.globals(), mod.module(), mod.ctx(), mod.state());
    }

    Globals globals() { return globals; }
    LuaTable module() { return module; }
    LuaModContext ctx() { return ctx; }
    LuaModState state() { return state; }

    void restore(LuaMod mod) {
        mod.globals(globals);
        mod.module(module);
        mod.ctx(ctx);
        mod.state(state);
    }
}
