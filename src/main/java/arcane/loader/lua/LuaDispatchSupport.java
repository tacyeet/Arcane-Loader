package arcane.loader.lua;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

final class LuaDispatchSupport {
    private LuaDispatchSupport() {}

    interface Delivery {
        void deliver(LuaMod mod) throws Throwable;
    }

    interface FailureHandler {
        void onFailure(LuaMod mod, Throwable error);
    }

    interface FinallyHandler {
        void after(LuaMod mod, boolean failed);
    }

    static LuaTable commandArgs(List<String> args) {
        LuaTable luaArgs = new LuaTable();
        int slot = 1;
        if (args != null) {
            for (String arg : args) {
                luaArgs.set(slot++, LuaValue.valueOf(arg));
            }
        }
        return luaArgs;
    }

    static LuaTable networkEnvelope(String fromModId, String channel, LuaValue payload, long timestampMs) {
        LuaTable envelope = new LuaTable();
        envelope.set("channel", LuaValue.valueOf(channel == null ? "" : channel));
        envelope.set("fromModId", LuaValue.valueOf(fromModId == null ? "" : fromModId));
        envelope.set("payload", payload == null ? LuaValue.NIL : payload);
        envelope.set("timestampMs", LuaValue.valueOf(timestampMs));
        return envelope;
    }

    static String normalizedNetworkChannel(String channel) {
        if (channel == null) return null;
        String key = channel.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    static int dispatch(Iterable<LuaMod> mods, Predicate<LuaMod> eligible, Delivery delivery, FailureHandler onFailure, FinallyHandler after) {
        int sent = 0;
        for (LuaMod mod : mods) {
            if (mod == null || (eligible != null && !eligible.test(mod))) continue;
            boolean failed = false;
            boolean success = false;
            try {
                delivery.deliver(mod);
                success = true;
            } catch (Throwable error) {
                failed = true;
                if (onFailure != null) onFailure.onFailure(mod, error);
            } finally {
                if (after != null) after.after(mod, failed);
            }
            if (success) sent++;
        }
        return sent;
    }
}
