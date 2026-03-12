package arcane.loader.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class LuaModuleLoader {
    private LuaModuleLoader() {}

    static LuaTable loadModule(Globals globals, String modId, String entryName, Path entryPath) throws IOException {
        if (!Files.isRegularFile(entryPath)) {
            throw new IllegalStateException("Missing entry file: " + entryPath);
        }
        String code = Files.readString(entryPath, StandardCharsets.UTF_8);
        return loadModuleFromCode(globals, modId, entryName, code);
    }

    static LuaTable loadModuleFromCode(Globals globals, String modId, String entryName, String code) {
        LuaValue chunk = globals.load(code, modId + "/" + entryName);
        LuaValue ret = chunk.call();
        if (!ret.istable()) {
            throw new IllegalStateException("init.lua must return a table (got " + ret.typename() + ")");
        }
        return (LuaTable) ret;
    }
}
