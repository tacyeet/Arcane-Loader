package arcane.loader.lua;

import com.hypixel.hytale.server.core.Message;

/**
 * Lightweight wrapper exposed to Lua as `sender`.
 * Intentionally keeps the surface minimal instead of exposing full server internals.
 */
public final class LuaSender {

    private final Object sender;

    public LuaSender(Object sender) {
        this.sender = sender;
    }

    public void reply(String message) {
        if (message == null) message = "null";
        try {
            var m = sender.getClass().getMethod("sendMessage", Message.class);
            m.invoke(sender, Message.raw(message));
            return;
        } catch (Throwable ignored) { }

        try {
            var m = sender.getClass().getMethod("sendMessage", String.class);
            m.invoke(sender, message);
            return;
        } catch (Throwable ignored) { }

        try {
            var m = sender.getClass().getMethod("println", String.class);
            m.invoke(sender, message);
        } catch (Throwable ignored) { }
    }

    @Override
    public String toString() {
        return "LuaSender(" + sender + ")";
    }
}
