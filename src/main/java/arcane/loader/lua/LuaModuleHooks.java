package arcane.loader.lua;

import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

final class LuaModuleHooks {
    private LuaModuleHooks() {}

    static void callOptional(LuaTable module, String fnName, Object ctx) {
        if (module == null || fnName == null || fnName.isBlank()) return;
        LuaValue fn = module.get(fnName);
        if (!fn.isfunction()) return;
        LuaValue luaCtx = CoerceJavaToLua.coerce(ctx);
        ((LuaFunction) fn).call(luaCtx);
    }
}
