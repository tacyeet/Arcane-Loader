package arcane.loader.lua;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

final class LuaRuntimeErrors {
    private LuaRuntimeErrors() {}

    static void record(LuaMod mod, String prefix, Throwable error) {
        String message = prefix + ": " + error;
        mod.lastError(message);
        mod.lastErrorDetail(stacktrace(error));
        mod.recordError(Instant.now() + " " + message);
    }

    static String stacktrace(Throwable error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        error.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }
}
