package arcane.loader.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;

record LuaLoadedInstance(Globals globals, LuaTable module, LuaModContext ctx) {}
