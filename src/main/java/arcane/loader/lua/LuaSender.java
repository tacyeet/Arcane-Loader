package arcane.loader.lua;

import com.hypixel.hytale.server.core.Message;

/**
 * Lightweight wrapper exposed to Lua as `sender`.
 * Intentionally keeps the surface minimal instead of exposing full server internals.
 */
public final class LuaSender {

    private final Object sender; // Command sender type is part of Hytale API; keep as Object.

    public LuaSender(Object sender) {
        this.sender = sender;
    }

    /** Reply to the sender (best-effort: tries common method names). */
    public void reply(String message) {
        if (message == null) message = "null";
        // Prefer sendMessage(Message)
        try {
            var m = sender.getClass().getMethod("sendMessage", Message.class);
            m.invoke(sender, Message.raw(message));
            return;
        } catch (Throwable ignored) { }

        // Some APIs might use `sendMessage(String)` or `println(String)`
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
