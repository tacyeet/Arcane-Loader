package arcane.loader.lua;

import arcane.loader.ArcaneLoaderPlugin;
import com.hypixel.hytale.server.core.Message;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.io.IOException;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;

/**
 * Context exposed to Lua as `ctx`.
 *
 * Key functions:
 *  - ctx:command(name, help, fn) -> registers a mod-local command callable via `/lua call <modId> <name> ...`
 *  - ctx:on(event, fn)           -> registers event listeners
 *  - ctx:setTimeout(ms, fn)
 *  - ctx:setInterval(ms, fn)
 *
 * All resources are tracked and cleaned up on disable/reload.
 */
public final class LuaModContext {
    private static final String CAP_PLAYER_MOVEMENT = "player-movement";
    private static final String CAP_ENTITY_CONTROL = "entity-control";
    private static final String CAP_WORLD_CONTROL = "world-control";
    private static final String CAP_NETWORK_CONTROL = "network-control";
    private static final String CAP_UI_CONTROL = "ui-control";
    private static final String NETWORK_EVENT_PREFIX = "network:";
    private static final String UI_FORM_CHANNEL = "arcane.ui.form";
    private static final String UI_PANEL_CHANNEL = "arcane.ui.panel";

    private final ArcaneLoaderPlugin plugin;
    private final LuaModManager manager;
    private final String modId;

    private final Path modRoot;
    private final Path dataDir;

    private final Map<String, LuaFunction> commands = new LinkedHashMap<>();
    private final Map<String, String> commandHelp = new LinkedHashMap<>();
    private final LuaEventBus events = new LuaEventBus();
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();
    private final ArrayDeque<BlockEdit> blockEditQueue = new ArrayDeque<>();
    private final ArrayDeque<BlockEdit> txBlockEdits = new ArrayDeque<>();
    private final ArrayDeque<BlockSnapshot> txAppliedSnapshots = new ArrayDeque<>();
    private boolean txActive = false;

    private volatile boolean active = true;

    private record BlockEdit(String worldUuid, int x, int y, int z, Object blockTypeOrId) {}
    private record BlockSnapshot(String worldUuid, int x, int y, int z, Object oldBlock, Object oldState) {}

    public LuaModContext(ArcaneLoaderPlugin plugin, LuaModManager manager, String modId, Path modRoot, Path dataDir) {
        this.plugin = plugin;
        this.manager = manager;
        this.modId = modId;
        this.modRoot = modRoot;
        this.dataDir = dataDir;
    }

    public ArcaneLoaderPlugin plugin() { return plugin; }
    public LuaModManager manager() { return manager; }
    public String modId() { return modId; }
    public String modRoot() { return modRoot.toString(); }

    public void log(String message) {
        logInfo(message);
    }

    public void logInfo(String message) {
        plugin.getLogger().at(Level.INFO).log("[" + modId + "] " + String.valueOf(message));
    }

    public void logWarn(String message) {
        plugin.getLogger().at(Level.WARNING).log("[" + modId + "] " + String.valueOf(message));
    }

    public void logError(String message) {
        plugin.getLogger().at(Level.SEVERE).log("[" + modId + "] " + String.valueOf(message));
    }

    private void audit(String action, String detail) {
        plugin.auditSecurityAction(modId, action, detail);
    }

    private static String safeId(String value) {
        return value == null ? "" : value;
    }

    public boolean sendToPlayer(Object playerRef, String message) {
        if (playerRef == null) return false;
        try {
            var send = playerRef.getClass().getMethod("sendMessage", Message.class);
            send.invoke(playerRef, Message.raw(message == null ? "" : message));
            return true;
        } catch (Throwable t) {
            logWarn("sendToPlayer failed: " + t);
            return false;
        }
    }

    public boolean sendActionBar(Object playerRef, String message) {
        if (!hasCapability(CAP_UI_CONTROL)) return false;
        if (playerRef == null) return false;
        String safe = message == null ? "" : message;
        boolean ok = false;
        if (tryInvoke(playerRef, "sendActionBar", Message.raw(safe))) ok = true;
        else if (tryInvoke(playerRef, "sendActionBar", safe)) ok = true;
        else if (tryInvoke(playerRef, "setActionBar", Message.raw(safe))) ok = true;
        else if (tryInvoke(playerRef, "setActionBar", safe)) ok = true;
        else ok = sendToPlayer(playerRef, safe);
        if (ok) {
            audit("ui.actionbar", "player=" + safeId(playerUuid(playerRef)));
        }
        return ok;
    }

    public boolean sendTitle(Object playerRef, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        if (!hasCapability(CAP_UI_CONTROL)) return false;
        if (playerRef == null) return false;
        String t = title == null ? "" : title;
        String s = subtitle == null ? "" : subtitle;
        boolean ok = false;
        if (tryInvoke(playerRef, "sendTitle", t, s, fadeInTicks, stayTicks, fadeOutTicks)) ok = true;
        else if (tryInvoke(playerRef, "sendTitle", Message.raw(t), Message.raw(s), fadeInTicks, stayTicks, fadeOutTicks)) ok = true;
        else if (tryInvoke(playerRef, "showTitle", t, s, fadeInTicks, stayTicks, fadeOutTicks)) ok = true;
        else if (tryInvoke(playerRef, "showTitle", Message.raw(t), Message.raw(s), fadeInTicks, stayTicks, fadeOutTicks)) ok = true;
        else ok = sendToPlayer(playerRef, t + (s.isBlank() ? "" : " - " + s));
        if (ok) {
            audit("ui.title", "player=" + safeId(playerUuid(playerRef)));
        }
        return ok;
    }

    public int sendActionBar(List<Object> players, String message) {
        if (players == null || players.isEmpty()) return 0;
        int sent = 0;
        for (Object p : players) {
            if (sendActionBar(p, message)) sent++;
        }
        return sent;
    }

    public int sendTitle(List<Object> players, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        if (players == null || players.isEmpty()) return 0;
        int sent = 0;
        for (Object p : players) {
            if (sendTitle(p, title, subtitle, fadeInTicks, stayTicks, fadeOutTicks)) sent++;
        }
        return sent;
    }

    public String playerName(Object playerRef) {
        if (playerRef == null) return null;
        try {
            var get = playerRef.getClass().getMethod("getUsername");
            Object value = get.invoke(playerRef);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    public String playerWorldUuid(Object playerRef) {
        if (playerRef == null) return null;
        try {
            var get = playerRef.getClass().getMethod("getWorldUuid");
            Object value = get.invoke(playerRef);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    public String playerUuid(Object playerRef) {
        if (playerRef == null) return null;
        try {
            var get = playerRef.getClass().getMethod("getUuid");
            Object value = get.invoke(playerRef);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean kickPlayer(Object playerRef, String reason) {
        if (playerRef == null) return false;
        String safeReason = (reason == null || reason.isBlank()) ? "Disconnected." : reason;
        try {
            // Preferred: PlayerRef.disconnect(String)
            var disconnect = playerRef.getClass().getMethod("disconnect", String.class);
            disconnect.invoke(playerRef, safeReason);
            return true;
        } catch (Throwable ignored) { }

        try {
            // Fallback: PlayerRef.getPacketHandler().disconnect(String)
            var getPacketHandler = playerRef.getClass().getMethod("getPacketHandler");
            Object packetHandler = getPacketHandler.invoke(playerRef);
            if (packetHandler == null) return false;
            var disconnect = packetHandler.getClass().getMethod("disconnect", String.class);
            disconnect.invoke(packetHandler, safeReason);
            return true;
        } catch (Throwable t) {
            logWarn("kickPlayer failed: " + t);
            return false;
        }
    }

    public boolean referPlayer(Object playerRef, String host, int port) {
        if (!hasCapability(CAP_NETWORK_CONTROL)) return false;
        if (playerRef == null || host == null || host.isBlank()) return false;
        if (port < 1 || port > 65535) return false;
        try {
            // Preferred: PlayerRef.referToServer(String, int)
            var refer = playerRef.getClass().getMethod("referToServer", String.class, int.class);
            refer.invoke(playerRef, host, port);
            audit("network.refer", "player=" + safeId(playerUuid(playerRef)) + " host=" + host + " port=" + port);
            return true;
        } catch (Throwable ignored) { }

        try {
            // Fallback: PlayerRef.referToServer(String, int, byte[])
            var refer = playerRef.getClass().getMethod("referToServer", String.class, int.class, byte[].class);
            refer.invoke(playerRef, host, port, new byte[0]);
            audit("network.refer", "player=" + safeId(playerUuid(playerRef)) + " host=" + host + " port=" + port);
            return true;
        } catch (Throwable t) {
            logWarn("referPlayer failed: " + t);
            return false;
        }
    }

    public int referPlayers(List<Object> players, String host, int port) {
        if (players == null || players.isEmpty()) return 0;
        int sent = 0;
        for (Object playerRef : players) {
            if (referPlayer(playerRef, host, port)) sent++;
        }
        return sent;
    }

    public boolean sendNetworkMessage(Object playerRef, String channel, String payload) {
        if (!hasCapability(CAP_NETWORK_CONTROL)) return false;
        if (!isNetworkChannelAllowed(channel)) return false;
        boolean ok = sendNetworkMessageRaw(playerRef, channel, payload);
        if (ok) {
            audit("network.send", "player=" + safeId(playerUuid(playerRef)) + " channel=" + channel);
        }
        return ok;
    }

    private boolean sendNetworkMessageRaw(Object playerRef, String channel, String payload) {
        if (playerRef == null || channel == null || channel.isBlank()) return false;
        String safePayload = payload == null ? "" : payload;
        boolean ok = false;
        if (tryInvoke(playerRef, "sendPluginMessage", channel, safePayload.getBytes(StandardCharsets.UTF_8))) ok = true;
        else if (tryInvoke(playerRef, "sendPluginMessage", channel, safePayload)) ok = true;
        else if (tryInvoke(playerRef, "sendNetworkMessage", channel, safePayload.getBytes(StandardCharsets.UTF_8))) ok = true;
        else if (tryInvoke(playerRef, "sendNetworkMessage", channel, safePayload)) ok = true;

        if (!ok) {
            Object handler = invokeNoArgs(playerRef, "getPacketHandler");
            if (handler != null) {
                if (tryInvoke(handler, "sendPluginMessage", channel, safePayload.getBytes(StandardCharsets.UTF_8))) ok = true;
                else if (tryInvoke(handler, "sendPluginMessage", channel, safePayload)) ok = true;
                else if (tryInvoke(handler, "sendNetworkMessage", channel, safePayload.getBytes(StandardCharsets.UTF_8))) ok = true;
                else if (tryInvoke(handler, "sendNetworkMessage", channel, safePayload)) ok = true;
            }
        }
        return ok;
    }

    public boolean sendUiForm(Object playerRef, String formId, String payloadJson) {
        if (!hasCapability(CAP_UI_CONTROL)) return false;
        if (playerRef == null) return false;
        String pid = formId == null ? "" : formId.trim();
        String payload = payloadJson == null ? "{}" : payloadJson;
        String wrapped = "{\"type\":\"form\",\"id\":\"" + pid.replace("\"", "\\\"") + "\",\"payload\":" + payload + "}";
        boolean ok = sendNetworkMessageRaw(playerRef, UI_FORM_CHANNEL, wrapped);
        if (ok) {
            audit("ui.form", "player=" + safeId(playerUuid(playerRef)) + " id=" + pid);
        }
        return ok;
    }

    public boolean sendUiPanel(Object playerRef, String panelId, String payloadJson) {
        if (!hasCapability(CAP_UI_CONTROL)) return false;
        if (playerRef == null) return false;
        String pid = panelId == null ? "" : panelId.trim();
        String payload = payloadJson == null ? "{}" : payloadJson;
        String wrapped = "{\"type\":\"panel\",\"id\":\"" + pid.replace("\"", "\\\"") + "\",\"payload\":" + payload + "}";
        boolean ok = sendNetworkMessageRaw(playerRef, UI_PANEL_CHANNEL, wrapped);
        if (ok) {
            audit("ui.panel", "player=" + safeId(playerUuid(playerRef)) + " id=" + pid);
        }
        return ok;
    }

    public int sendUiForm(List<Object> players, String formId, String payloadJson) {
        if (players == null || players.isEmpty()) return 0;
        int sent = 0;
        for (Object p : players) {
            if (sendUiForm(p, formId, payloadJson)) sent++;
        }
        return sent;
    }

    public int sendUiPanel(List<Object> players, String panelId, String payloadJson) {
        if (players == null || players.isEmpty()) return 0;
        int sent = 0;
        for (Object p : players) {
            if (sendUiPanel(p, panelId, payloadJson)) sent++;
        }
        return sent;
    }

    public Map<String, Object> webhookRequest(String method, String url, String body, String contentType, int timeoutMs, Map<String, String> headers) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("status", -1);
        out.put("body", "");
        out.put("error", "");
        out.put("url", url == null ? "" : url);
        if (!hasCapability(CAP_NETWORK_CONTROL)) {
            out.put("error", "missing network-control capability");
            return out;
        }
        if (url == null || url.isBlank()) {
            out.put("error", "url is required");
            return out;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            out.put("error", "only http/https URLs are allowed");
            return out;
        }
        String m = (method == null || method.isBlank()) ? "POST" : method.trim().toUpperCase(Locale.ROOT);
        String payload = body == null ? "" : body;
        String ct = (contentType == null || contentType.isBlank()) ? "application/json" : contentType.trim();
        int timeout = Math.max(250, Math.min(30000, timeoutMs <= 0 ? 5000 : timeoutMs));
        long start = System.nanoTime();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeout))
                    .build();
            HttpRequest.BodyPublisher publisher = payload.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(trimmed))
                    .timeout(Duration.ofMillis(timeout))
                    .header("User-Agent", "ArcaneLoader/1.0 mod=" + modId)
                    .header("Content-Type", ct)
                    .method(m, publisher);
            if (headers != null) {
                for (Map.Entry<String, String> ent : headers.entrySet()) {
                    String hk = ent.getKey();
                    String hv = ent.getValue();
                    if (hk == null || hk.isBlank() || hv == null) continue;
                    if (hk.equalsIgnoreCase("content-length")) continue;
                    builder.header(hk.trim(), hv);
                }
            }
            HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = resp.statusCode();
            String respBody = resp.body() == null ? "" : resp.body();
            out.put("ok", status >= 200 && status < 300);
            out.put("status", status);
            out.put("body", respBody);
            out.put("durationMs", (System.nanoTime() - start) / 1_000_000.0);
            audit("network.webhook", "method=" + m + " url=" + trimmed + " status=" + status);
            return out;
        } catch (Throwable t) {
            out.put("error", String.valueOf(t));
            out.put("durationMs", (System.nanoTime() - start) / 1_000_000.0);
            audit("network.webhook", "method=" + m + " url=" + trimmed + " error=" + t.getClass().getSimpleName());
            return out;
        }
    }

    public int sendNetworkMessage(List<Object> players, String channel, String payload) {
        if (players == null || players.isEmpty()) return 0;
        int sent = 0;
        for (Object playerRef : players) {
            if (sendNetworkMessage(playerRef, channel, payload)) sent++;
        }
        return sent;
    }

    public double[] playerPosition(Object playerRef) {
        if (playerRef == null) return null;
        Object xObj = invokeNoArgs(playerRef, "getX");
        Object yObj = invokeNoArgs(playerRef, "getY");
        Object zObj = invokeNoArgs(playerRef, "getZ");
        if (xObj instanceof Number x && yObj instanceof Number y && zObj instanceof Number z) {
            return new double[] { x.doubleValue(), y.doubleValue(), z.doubleValue() };
        }

        Object pos = invokeNoArgs(playerRef, "getPosition");
        if (pos == null) pos = invokeNoArgs(playerRef, "position");
        if (pos != null) {
            Object px = invokeNoArgs(pos, "getX");
            Object py = invokeNoArgs(pos, "getY");
            Object pz = invokeNoArgs(pos, "getZ");
            if (px instanceof Number x && py instanceof Number y && pz instanceof Number z) {
                return new double[] { x.doubleValue(), y.doubleValue(), z.doubleValue() };
            }
        }
        return null;
    }

    public boolean teleportPlayer(Object playerRef, String worldUuid, double x, double y, double z) {
        if (!hasCapability(CAP_PLAYER_MOVEMENT)) return false;
        if (playerRef == null) return false;
        Object world = null;
        if (worldUuid != null && !worldUuid.isBlank()) {
            world = findWorldByUuid(worldUuid);
        }

        boolean ok = false;
        if (world != null) {
            if (tryInvoke(playerRef, "teleport", world, x, y, z)) ok = true;
            else if (tryInvoke(playerRef, "setPosition", world, x, y, z)) ok = true;
            else if (tryInvoke(playerRef, "moveTo", world, x, y, z)) ok = true;
        }

        if (!ok && tryInvoke(playerRef, "teleport", x, y, z)) ok = true;
        if (!ok && tryInvoke(playerRef, "setPosition", x, y, z)) ok = true;
        if (!ok && tryInvoke(playerRef, "moveTo", x, y, z)) ok = true;
        if (ok) {
            String targetWorld = worldUuid == null ? safeId(playerWorldUuid(playerRef)) : worldUuid;
            audit("player.teleport", "player=" + safeId(playerUuid(playerRef)) + " world=" + safeId(targetWorld) + " x=" + x + " y=" + y + " z=" + z);
        }
        return ok;
    }

    public boolean isValidPlayer(Object playerRef) {
        if (playerRef == null) return false;
        try {
            var m = playerRef.getClass().getMethod("getUsername");
            Object value = m.invoke(playerRef);
            return value != null && !String.valueOf(value).isBlank();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean hasPermission(Object playerRef, String node) {
        if (playerRef == null || node == null || node.isBlank()) return false;
        try {
            var getUuid = playerRef.getClass().getMethod("getUuid");
            Object uuid = getUuid.invoke(playerRef);
            if (uuid == null) return false;

            Class<?> permsClass = Class.forName("com.hypixel.hytale.server.core.permissions.PermissionsModule");
            var getInstance = permsClass.getMethod("getInstance");
            Object perms = getInstance.invoke(null);
            if (perms == null) return false;

            try {
                var hasPermission = permsClass.getMethod("hasPermission", uuid.getClass(), String.class);
                Object result = hasPermission.invoke(perms, uuid, node);
                return result instanceof Boolean b && b;
            } catch (NoSuchMethodException ignored) {
                var hasPermission = permsClass.getMethod("hasPermission", uuid.getClass(), String.class, boolean.class);
                Object result = hasPermission.invoke(perms, uuid, node, false);
                return result instanceof Boolean b && b;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    public List<Object> onlinePlayers() {
        try {
            Class<?> serverClass = Class.forName("com.hypixel.hytale.server.core.HytaleServer");
            Object server = serverClass.getMethod("getServer").invoke(null);
            if (server == null) return List.of();
            Object universe = serverClass.getMethod("getUniverse").invoke(server);
            if (universe == null) return List.of();
            Object players = universe.getClass().getMethod("getPlayers").invoke(universe);

            ArrayList<Object> out = new ArrayList<>();
            if (players instanceof Iterable<?> it) {
                for (Object p : it) {
                    if (p != null) out.add(p);
                }
                return out;
            }
            if (players instanceof Object[] arr) {
                for (Object p : arr) {
                    if (p != null) out.add(p);
                }
                return out;
            }
            return List.of();
        } catch (Throwable t) {
            logWarn("onlinePlayers lookup failed: " + t);
            return List.of();
        }
    }

    public Object findOnlinePlayerByName(String username) {
        if (username == null || username.isBlank()) return null;
        for (Object ref : onlinePlayers()) {
            String name = playerName(ref);
            if (name != null && name.equalsIgnoreCase(username)) {
                return ref;
            }
        }
        return null;
    }

    public Object findOnlinePlayerByUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        for (Object ref : onlinePlayers()) {
            String id = playerUuid(ref);
            if (id != null && id.equalsIgnoreCase(uuid)) {
                return ref;
            }
        }
        return null;
    }

    public int broadcast(String message) {
        int count = 0;
        for (Object ref : onlinePlayers()) {
            if (sendToPlayer(ref, message)) count++;
        }
        return count;
    }

    private Object serverInstance() {
        try {
            Class<?> serverClass = Class.forName("com.hypixel.hytale.server.core.HytaleServer");
            return serverClass.getMethod("getServer").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private Object universeInstance() {
        try {
            Object server = serverInstance();
            if (server == null) return null;
            return server.getClass().getMethod("getUniverse").invoke(server);
        } catch (Throwable t) {
            return null;
        }
    }

    public List<Object> worlds() {
        Object universe = universeInstance();
        if (universe == null) return List.of();

        try {
            Object worlds = universe.getClass().getMethod("getWorlds").invoke(universe);
            ArrayList<Object> out = new ArrayList<>();
            if (worlds instanceof Map<?, ?> m) {
                for (Object w : m.values()) if (w != null) out.add(w);
                return out;
            }
            if (worlds instanceof Iterable<?> it) {
                for (Object w : it) if (w != null) out.add(w);
                return out;
            }
            if (worlds instanceof Object[] arr) {
                for (Object w : arr) if (w != null) out.add(w);
                return out;
            }
            return List.of();
        } catch (Throwable t) {
            return List.of();
        }
    }

    public String worldUuid(Object world) {
        if (world == null) return null;
        try {
            var m = world.getClass().getMethod("getUuid");
            Object value = m.invoke(world);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) { }
        try {
            var m = world.getClass().getMethod("getWorldUuid");
            Object value = m.invoke(world);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) { }
        return null;
    }

    public String worldName(Object world) {
        if (world == null) return null;
        try {
            var m = world.getClass().getMethod("getName");
            Object value = m.invoke(world);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) { }
        try {
            var m = world.getClass().getMethod("getDisplayName");
            Object value = m.invoke(world);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) { }
        return null;
    }

    public Object findWorldByUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        for (Object world : worlds()) {
            String id = worldUuid(world);
            if (id != null && id.equalsIgnoreCase(uuid)) return world;
        }
        return null;
    }

    public Object findWorldByName(String name) {
        if (name == null || name.isBlank()) return null;
        for (Object world : worlds()) {
            String n = worldName(world);
            if (n != null && n.equalsIgnoreCase(name)) return world;
        }
        return null;
    }

    public Object defaultWorld() {
        Object universe = universeInstance();
        if (universe == null) return null;
        Object world = invokeNoArgs(universe, "getDefaultWorld");
        if (world == null) world = invokeNoArgs(universe, "getPrimaryWorld");
        if (world == null) world = invokeNoArgs(universe, "defaultWorld");
        return world;
    }

    public long worldTime(Object world) {
        if (world == null) return -1L;
        Object v = invokeNoArgs(world, "getTime");
        if (v == null) v = invokeNoArgs(world, "getWorldTime");
        if (v == null) v = invokeNoArgs(world, "getDayTime");
        if (v instanceof Number n) return n.longValue();
        return -1L;
    }

    public boolean setWorldTime(Object world, long time) {
        if (!hasCapability(CAP_WORLD_CONTROL)) return false;
        if (world == null) return false;
        boolean ok = false;
        if (tryInvoke(world, "setTime", time)) ok = true;
        else if (tryInvoke(world, "setWorldTime", time)) ok = true;
        else if (tryInvoke(world, "setDayTime", time)) ok = true;
        if (ok) {
            audit("world.setTime", "world=" + safeId(worldUuid(world)) + " time=" + time);
        }
        return ok;
    }

    public boolean worldPaused(Object world) {
        if (world == null) return false;
        Object v = invokeNoArgs(world, "isPaused");
        if (!(v instanceof Boolean)) v = invokeNoArgs(world, "isTimePaused");
        if (!(v instanceof Boolean)) v = invokeNoArgs(world, "getPauseTime");
        return v instanceof Boolean b && b;
    }

    public boolean setWorldPaused(Object world, boolean paused) {
        if (!hasCapability(CAP_WORLD_CONTROL)) return false;
        if (world == null) return false;
        boolean ok = false;
        if (tryInvoke(world, "setPaused", paused)) ok = true;
        else if (tryInvoke(world, "setTimePaused", paused)) ok = true;
        else if (tryInvoke(world, "setPauseTime", paused)) ok = true;
        if (ok) {
            audit("world.setPaused", "world=" + safeId(worldUuid(world)) + " paused=" + paused);
        }
        return ok;
    }

    public Object worldOfPlayer(Object playerRef) {
        String worldUuid = playerWorldUuid(playerRef);
        if (worldUuid == null) return null;
        return findWorldByUuid(worldUuid);
    }

    public List<Object> onlinePlayersInWorld(String worldUuid) {
        if (worldUuid == null || worldUuid.isBlank()) return List.of();
        ArrayList<Object> out = new ArrayList<>();
        for (Object ref : onlinePlayers()) {
            String w = playerWorldUuid(ref);
            if (w != null && w.equalsIgnoreCase(worldUuid)) out.add(ref);
        }
        return out;
    }

    public int broadcastToWorld(String worldUuid, String message) {
        int count = 0;
        for (Object ref : onlinePlayersInWorld(worldUuid)) {
            if (sendToPlayer(ref, message)) count++;
        }
        return count;
    }

    public List<Object> entities() {
        ArrayList<Object> out = new ArrayList<>();
        HashSet<Object> seen = new HashSet<>();

        Object universe = universeInstance();
        collectEntitiesFromContainer(invokeNoArgs(universe, "getEntities"), out, seen);
        collectEntitiesFromContainer(invokeNoArgs(universe, "entities"), out, seen);
        collectEntitiesFromContainer(invokeNoArgs(universe, "getEntityRefs"), out, seen);

        for (Object world : worlds()) {
            collectEntitiesFromContainer(invokeNoArgs(world, "getEntities"), out, seen);
            collectEntitiesFromContainer(invokeNoArgs(world, "entities"), out, seen);
            collectEntitiesFromContainer(invokeNoArgs(world, "getEntityRefs"), out, seen);
        }
        return out;
    }

    public List<Object> entitiesInWorld(String worldUuid) {
        if (worldUuid == null || worldUuid.isBlank()) return entities();
        ArrayList<Object> out = new ArrayList<>();
        for (Object entity : entities()) {
            String eWorld = entityWorldUuid(entity);
            if (eWorld != null && eWorld.equalsIgnoreCase(worldUuid)) {
                out.add(entity);
            }
        }
        return out;
    }

    public Object findEntityById(String id) {
        if (id == null || id.isBlank()) return null;
        for (Object entity : entities()) {
            String entityId = entityId(entity);
            if (entityId != null && entityId.equalsIgnoreCase(id)) {
                return entity;
            }
        }
        return null;
    }

    public String entityId(Object entity) {
        if (entity == null) return null;
        Object v = invokeNoArgs(entity, "getUuid");
        if (v == null) v = invokeNoArgs(entity, "getId");
        if (v == null) v = invokeNoArgs(entity, "getEntityId");
        if (v == null) v = invokeNoArgs(entity, "getRef");
        if (v == null) return null;
        return String.valueOf(v);
    }

    public String entityType(Object entity) {
        if (entity == null) return null;
        Object v = invokeNoArgs(entity, "getType");
        if (v == null) v = invokeNoArgs(entity, "getTypeId");
        if (v == null) v = invokeNoArgs(entity, "getKind");
        if (v == null) v = invokeNoArgs(entity, "getName");
        if (v == null) return entity.getClass().getSimpleName();
        return String.valueOf(v);
    }

    public String entityWorldUuid(Object entity) {
        if (entity == null) return null;
        Object worldUuid = invokeNoArgs(entity, "getWorldUuid");
        if (worldUuid != null) return String.valueOf(worldUuid);
        Object world = invokeNoArgs(entity, "getWorld");
        if (world == null) world = invokeNoArgs(entity, "world");
        return worldUuid(world);
    }

    public boolean isValidEntity(Object entity) {
        if (entity == null) return false;
        Object valid = invokeNoArgs(entity, "isValid");
        if (valid instanceof Boolean b) return b;
        Object alive = invokeNoArgs(entity, "isAlive");
        if (alive instanceof Boolean b) return b;
        Object removed = invokeNoArgs(entity, "isRemoved");
        if (removed instanceof Boolean b) return !b;
        return true;
    }

    public boolean removeEntity(Object entity) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return false;
        if (entity == null) return false;
        String entityId = safeId(entityId(entity));
        boolean ok = false;
        if (tryInvoke(entity, "remove")) ok = true;
        else if (tryInvoke(entity, "destroy")) ok = true;
        else if (tryInvoke(entity, "delete")) ok = true;
        else if (tryInvoke(entity, "kill")) ok = true;
        else if (tryInvoke(entity, "despawn")) ok = true;
        else if (tryInvoke(entity, "invalidate")) ok = true;

        if (!ok) {
            Object universe = universeInstance();
            if (universe != null) {
                if (tryInvoke(universe, "removeEntity", entity)) ok = true;
                else if (tryInvoke(universe, "destroyEntity", entity)) ok = true;
                else if (tryInvoke(universe, "deleteEntity", entity)) ok = true;
            }
        }
        if (ok) {
            audit("entity.remove", "entity=" + entityId + " world=" + safeId(entityWorldUuid(entity)));
        }
        return ok;
    }

    public double[] entityPosition(Object entity) {
        if (entity == null) return null;

        Object xObj = invokeNoArgs(entity, "getX");
        Object yObj = invokeNoArgs(entity, "getY");
        Object zObj = invokeNoArgs(entity, "getZ");
        if (xObj instanceof Number x && yObj instanceof Number y && zObj instanceof Number z) {
            return new double[] { x.doubleValue(), y.doubleValue(), z.doubleValue() };
        }

        Object pos = invokeNoArgs(entity, "getPosition");
        if (pos == null) pos = invokeNoArgs(entity, "position");
        if (pos != null) {
            Object px = invokeNoArgs(pos, "getX");
            Object py = invokeNoArgs(pos, "getY");
            Object pz = invokeNoArgs(pos, "getZ");
            if (px instanceof Number x && py instanceof Number y && pz instanceof Number z) {
                return new double[] { x.doubleValue(), y.doubleValue(), z.doubleValue() };
            }
        }
        return null;
    }

    public boolean teleportEntity(Object entity, String worldUuid, double x, double y, double z) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return false;
        if (entity == null) return false;

        Object world = null;
        if (worldUuid != null && !worldUuid.isBlank()) {
            world = findWorldByUuid(worldUuid);
        }

        boolean ok = false;
        if (world != null) {
            if (tryInvoke(entity, "teleport", world, x, y, z)) ok = true;
            else if (tryInvoke(entity, "setPosition", world, x, y, z)) ok = true;
            else if (tryInvoke(entity, "moveTo", world, x, y, z)) ok = true;
        }

        if (!ok && tryInvoke(entity, "teleport", x, y, z)) ok = true;
        if (!ok && tryInvoke(entity, "setPosition", x, y, z)) ok = true;
        if (!ok && tryInvoke(entity, "moveTo", x, y, z)) ok = true;
        if (ok) {
            String targetWorld = worldUuid == null ? safeId(entityWorldUuid(entity)) : worldUuid;
            audit("entity.teleport", "entity=" + safeId(entityId(entity)) + " world=" + safeId(targetWorld) + " x=" + x + " y=" + y + " z=" + z);
        }
        return ok;
    }

    public double[] entityRotation(Object entity) {
        if (entity == null) return null;
        Object yaw = invokeNoArgs(entity, "getYaw");
        Object pitch = invokeNoArgs(entity, "getPitch");
        Object roll = invokeNoArgs(entity, "getRoll");
        if (yaw instanceof Number y && pitch instanceof Number p) {
            double r = roll instanceof Number n ? n.doubleValue() : 0.0;
            return new double[]{y.doubleValue(), p.doubleValue(), r};
        }

        Object rot = invokeNoArgs(entity, "getRotation");
        if (rot == null) rot = invokeNoArgs(entity, "rotation");
        if (rot != null) {
            Object ry = invokeNoArgs(rot, "getYaw");
            Object rp = invokeNoArgs(rot, "getPitch");
            Object rr = invokeNoArgs(rot, "getRoll");
            if (ry instanceof Number y && rp instanceof Number p) {
                double r = rr instanceof Number n ? n.doubleValue() : 0.0;
                return new double[]{y.doubleValue(), p.doubleValue(), r};
            }
        }
        return null;
    }

    public boolean setEntityRotation(Object entity, double yaw, double pitch, double roll) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return false;
        if (entity == null) return false;
        boolean ok = false;
        if (tryInvoke(entity, "setRotation", yaw, pitch, roll)) ok = true;
        else if (tryInvoke(entity, "setRotation", yaw, pitch)) ok = true;
        else if (tryInvoke(entity, "setYawPitch", yaw, pitch)) ok = true;
        else if (tryInvoke(entity, "setYaw", yaw) && tryInvoke(entity, "setPitch", pitch)) ok = true;
        else if (tryInvoke(entity, "setHeadRotation", yaw, pitch, roll)) ok = true;
        if (ok) {
            audit("entity.rotate", "entity=" + safeId(entityId(entity)) + " yaw=" + yaw + " pitch=" + pitch + " roll=" + roll);
        }
        return ok;
    }

    public double[] entityVelocity(Object entity) {
        if (entity == null) return null;
        Object vx = invokeNoArgs(entity, "getVelocityX");
        Object vy = invokeNoArgs(entity, "getVelocityY");
        Object vz = invokeNoArgs(entity, "getVelocityZ");
        if (vx instanceof Number x && vy instanceof Number y && vz instanceof Number z) {
            return new double[]{x.doubleValue(), y.doubleValue(), z.doubleValue()};
        }
        Object vel = invokeNoArgs(entity, "getVelocity");
        if (vel == null) vel = invokeNoArgs(entity, "velocity");
        if (vel != null) {
            Object x = invokeNoArgs(vel, "getX");
            Object y = invokeNoArgs(vel, "getY");
            Object z = invokeNoArgs(vel, "getZ");
            if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
                return new double[]{nx.doubleValue(), ny.doubleValue(), nz.doubleValue()};
            }
        }
        return null;
    }

    public boolean setEntityVelocity(Object entity, double x, double y, double z) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return false;
        if (entity == null) return false;
        boolean ok = false;
        if (tryInvoke(entity, "setVelocity", x, y, z)) ok = true;
        else if (tryInvoke(entity, "setMotion", x, y, z)) ok = true;
        else {
            Object vec = newVector3d(x, y, z);
            if (vec != null && tryInvoke(entity, "setVelocity", vec)) ok = true;
            else if (vec != null && tryInvoke(entity, "setMotion", vec)) ok = true;
        }
        if (ok) {
            audit("entity.velocity", "entity=" + safeId(entityId(entity)) + " x=" + x + " y=" + y + " z=" + z);
        }
        return ok;
    }

    public List<Object> entitiesNear(String worldUuid, double x, double y, double z, double radius) {
        if (radius < 0) return List.of();
        List<Object> scope = (worldUuid == null || worldUuid.isBlank()) ? entities() : entitiesInWorld(worldUuid);
        ArrayList<Object> out = new ArrayList<>();
        double r2 = radius * radius;
        for (Object e : scope) {
            double[] p = entityPosition(e);
            if (p == null || p.length < 3) continue;
            double dx = p[0] - x;
            double dy = p[1] - y;
            double dz = p[2] - z;
            if ((dx * dx + dy * dy + dz * dz) <= r2) out.add(e);
        }
        return out;
    }

    public Object spawnEntity(Object world, Object typeOrPrototype, double x, double y, double z, double yaw, double pitch, double roll) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return null;
        if (world == null) world = defaultWorld();
        if (world == null || typeOrPrototype == null) return null;

        Object pos = newVector3d(x, y, z);
        Object rot = newVector3f(yaw, pitch, roll);
        Object addReason = defaultAddReason();

        Object spawned = null;
        if (typeOrPrototype instanceof String type) {
            spawned = invokeBest(world, "spawnEntity", type, pos, rot, addReason);
            if (spawned == null) spawned = invokeBest(world, "spawnEntity", type, pos, rot);
            if (spawned == null) spawned = invokeBest(world, "spawnEntity", type, x, y, z, yaw, pitch, roll);
            if (spawned == null) spawned = invokeBest(world, "createEntity", type, pos, rot);
            if (spawned == null) spawned = invokeBest(universeInstance(), "spawnEntity", type, pos, rot);
        } else {
            spawned = invokeBest(world, "spawnEntity", typeOrPrototype, pos, rot, addReason);
            if (spawned == null) spawned = invokeBest(world, "spawnEntity", typeOrPrototype, pos, rot);
            if (spawned == null) spawned = invokeBest(world, "spawnEntity", typeOrPrototype, x, y, z, yaw, pitch, roll);
            if (spawned == null) spawned = invokeBest(universeInstance(), "spawnEntity", typeOrPrototype, pos, rot);
        }
        if (spawned != null) {
            audit("entity.spawn", "type=" + typeOrPrototype + " world=" + safeId(worldUuid(world)) + " x=" + x + " y=" + y + " z=" + z);
        }
        return spawned;
    }

    public Object blockAt(String worldUuid, int x, int y, int z) {
        Object world = (worldUuid == null || worldUuid.isBlank()) ? defaultWorld() : findWorldByUuid(worldUuid);
        if (world == null) return null;
        Object block = invokeBest(world, "getBlock", x, y, z);
        if (block == null) block = invokeBest(world, "getBlockAt", x, y, z);
        if (block == null) {
            Object vec = newBlockVector(x, y, z);
            if (vec != null) block = invokeBest(world, "getBlock", vec);
            if (block == null && vec != null) block = invokeBest(world, "getBlockAt", vec);
        }
        if (block == null) {
            block = invokeBest(world, "getBlockType", x, y, z);
            if (block == null) block = invokeBest(world, "getBlockId", x, y, z);
        }
        return block;
    }

    public String blockNameAt(String worldUuid, int x, int y, int z) {
        Object block = blockAt(worldUuid, x, y, z);
        if (block == null) return null;
        Object name = reflectiveGet(block, "name");
        if (name == null) name = reflectiveCall(block, "getName");
        if (name == null) name = reflectiveGet(block, "type");
        if (name == null) name = reflectiveCall(block, "getType");
        return name == null ? String.valueOf(block) : String.valueOf(name);
    }

    public Integer blockIdAt(String worldUuid, int x, int y, int z) {
        Object block = blockAt(worldUuid, x, y, z);
        if (block == null) return null;
        Object id = reflectiveGet(block, "id");
        if (id == null) id = reflectiveCall(block, "getId");
        if (id == null) id = reflectiveGet(block, "blockId");
        if (id == null) id = reflectiveCall(block, "getBlockId");
        if (id instanceof Number n) return n.intValue();
        return null;
    }

    public Object resolveBlockType(Object typeOrId) {
        if (typeOrId == null) return null;
        if (!(typeOrId instanceof String) && !(typeOrId instanceof Number)) return typeOrId;
        Object module = resolveBlockTypeModule();
        if (module == null) return typeOrId;

        if (typeOrId instanceof Number n) {
            int id = n.intValue();
            Object block = invokeBest(module, "getBlockType", id);
            if (block == null) block = invokeBest(module, "getById", id);
            if (block == null) block = invokeBest(module, "fromId", id);
            return block == null ? typeOrId : block;
        }

        String name = String.valueOf(typeOrId);
        Object block = invokeBest(module, "getBlockType", name);
        if (block == null) block = invokeBest(module, "getByName", name);
        if (block == null) block = invokeBest(module, "fromName", name);
        if (block == null) block = invokeBest(module, "resolve", name);
        return block == null ? typeOrId : block;
    }

    public boolean setBlock(String worldUuid, int x, int y, int z, Object blockTypeOrId) {
        if (!hasCapability(CAP_WORLD_CONTROL)) return false;
        Object world = (worldUuid == null || worldUuid.isBlank()) ? defaultWorld() : findWorldByUuid(worldUuid);
        if (world == null || blockTypeOrId == null) return false;
        Object resolved = resolveBlockType(blockTypeOrId);
        boolean ok = false;
        if (tryInvoke(world, "setBlock", x, y, z, resolved)) ok = true;
        else if (tryInvoke(world, "setBlockAt", x, y, z, resolved)) ok = true;
        else if (tryInvoke(world, "setBlockType", x, y, z, resolved)) ok = true;
        else if (tryInvoke(world, "setBlockId", x, y, z, resolved)) ok = true;
        else {
            Object vec = newBlockVector(x, y, z);
            if (vec != null && tryInvoke(world, "setBlock", vec, resolved)) ok = true;
            else if (vec != null && tryInvoke(world, "setBlockAt", vec, resolved)) ok = true;
            else if (vec != null && tryInvoke(world, "setBlockType", vec, resolved)) ok = true;
        }
        if (ok) {
            audit("world.setBlock", "world=" + safeId(worldUuid(world)) + " x=" + x + " y=" + y + " z=" + z + " block=" + blockTypeOrId);
        }
        return ok;
    }

    public List<Object> blocksInBox(String worldUuid, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int limit) {
        Object world = (worldUuid == null || worldUuid.isBlank()) ? defaultWorld() : findWorldByUuid(worldUuid);
        if (world == null) return List.of();
        int loX = Math.min(minX, maxX), hiX = Math.max(minX, maxX);
        int loY = Math.min(minY, maxY), hiY = Math.max(minY, maxY);
        int loZ = Math.min(minZ, maxZ), hiZ = Math.max(minZ, maxZ);
        int cap = limit <= 0 ? 4096 : Math.min(limit, 16384);
        ArrayList<Object> out = new ArrayList<>();
        for (int x = loX; x <= hiX && out.size() < cap; x++) {
            for (int y = loY; y <= hiY && out.size() < cap; y++) {
                for (int z = loZ; z <= hiZ && out.size() < cap; z++) {
                    Object b = blockAt(worldUuid(world), x, y, z);
                    if (b != null) out.add(b);
                }
            }
        }
        return out;
    }

    public Object raycastBlock(String worldUuid, double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance, double stepSize) {
        String wu = worldUuid;
        if (wu == null || wu.isBlank()) {
            Object world = defaultWorld();
            wu = worldUuid(world);
        }
        if (wu == null) return null;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 0.00001) return null;
        double ndx = dx / len, ndy = dy / len, ndz = dz / len;
        double step = stepSize <= 0 ? 0.5 : Math.max(0.1, stepSize);
        double max = maxDistance <= 0 ? 64.0 : Math.min(512.0, maxDistance);
        double t = 0.0;
        while (t <= max) {
            int bx = (int) Math.floor(ox + ndx * t);
            int by = (int) Math.floor(oy + ndy * t);
            int bz = (int) Math.floor(oz + ndz * t);
            Object block = blockAt(wu, bx, by, bz);
            if (block != null && !isAirBlock(block)) {
                return block;
            }
            t += step;
        }
        return null;
    }

    public Object blockStateAt(String worldUuid, int x, int y, int z) {
        Object block = blockAt(worldUuid, x, y, z);
        if (block == null) return null;
        Object state = reflectiveGet(block, "state");
        if (state == null) state = reflectiveGet(block, "blockState");
        if (state == null) state = reflectiveCall(block, "getState");
        if (state == null) state = reflectiveCall(block, "getBlockState");
        return state;
    }

    public boolean setBlockState(String worldUuid, int x, int y, int z, Object state) {
        if (!hasCapability(CAP_WORLD_CONTROL)) return false;
        Object world = (worldUuid == null || worldUuid.isBlank()) ? defaultWorld() : findWorldByUuid(worldUuid);
        if (world == null || state == null) return false;
        boolean ok = false;
        if (tryInvoke(world, "setBlockState", x, y, z, state)) ok = true;
        else if (tryInvoke(world, "setState", x, y, z, state)) ok = true;
        else {
            Object block = blockAt(worldUuid, x, y, z);
            if (block != null && reflectiveSet(block, "state", state)) ok = true;
            else if (block != null && reflectiveSet(block, "blockState", state)) ok = true;
        }
        if (ok) {
            audit("world.setBlockState", "world=" + safeId(worldUuid(world)) + " x=" + x + " y=" + y + " z=" + z);
        }
        return ok;
    }

    public synchronized int queueBlockEdit(String worldUuid, int x, int y, int z, Object blockTypeOrId) {
        if (!hasCapability(CAP_WORLD_CONTROL)) return 0;
        if (blockEditQueue.size() >= plugin.getMaxQueuedBlockEditsPerMod()) return -1;
        blockEditQueue.addLast(new BlockEdit(worldUuid, x, y, z, blockTypeOrId));
        return blockEditQueue.size();
    }

    public synchronized boolean beginBlockTransaction() {
        if (!hasCapability(CAP_WORLD_CONTROL)) return false;
        if (txActive) return false;
        txActive = true;
        txBlockEdits.clear();
        txAppliedSnapshots.clear();
        return true;
    }

    public synchronized int txQueueBlockEdit(String worldUuid, int x, int y, int z, Object blockTypeOrId) {
        if (!txActive || !hasCapability(CAP_WORLD_CONTROL)) return 0;
        if (txBlockEdits.size() >= plugin.getMaxTxBlockEditsPerMod()) return -1;
        txBlockEdits.addLast(new BlockEdit(worldUuid, x, y, z, blockTypeOrId));
        return txBlockEdits.size();
    }

    public synchronized int txQueuedBlockEdits() {
        return txBlockEdits.size();
    }

    public synchronized boolean txActive() {
        return txActive;
    }

    public synchronized int rollbackBlockTransaction() {
        int reverted = 0;
        while (!txAppliedSnapshots.isEmpty()) {
            BlockSnapshot s = txAppliedSnapshots.removeLast();
            boolean ok = false;
            if (s.oldState() != null) {
                ok = setBlockState(s.worldUuid(), s.x(), s.y(), s.z(), s.oldState());
            }
            if (!ok && s.oldBlock() != null) {
                ok = setBlock(s.worldUuid(), s.x(), s.y(), s.z(), s.oldBlock());
            }
            if (ok) reverted++;
        }
        txBlockEdits.clear();
        txActive = false;
        if (reverted > 0) {
            audit("world.txRollback", "reverted=" + reverted);
        }
        return reverted;
    }

    public synchronized int commitBlockTransaction(int limit) {
        if (!txActive || !hasCapability(CAP_WORLD_CONTROL)) return 0;
        int cap = limit <= 0 ? txBlockEdits.size() : Math.min(limit, txBlockEdits.size());
        int applied = 0;
        while (applied < cap && !txBlockEdits.isEmpty()) {
            BlockEdit e = txBlockEdits.removeFirst();
            Object oldBlock = blockAt(e.worldUuid(), e.x(), e.y(), e.z());
            Object oldState = blockStateAt(e.worldUuid(), e.x(), e.y(), e.z());
            if (setBlock(e.worldUuid(), e.x(), e.y(), e.z(), e.blockTypeOrId())) {
                txAppliedSnapshots.addLast(new BlockSnapshot(e.worldUuid(), e.x(), e.y(), e.z(), oldBlock, oldState));
                applied++;
            }
        }
        if (txBlockEdits.isEmpty()) {
            txActive = false;
            txAppliedSnapshots.clear();
        }
        if (applied > 0) {
            audit("world.txCommit", "applied=" + applied + " remaining=" + txBlockEdits.size());
        }
        return applied;
    }

    public synchronized int queuedBlockEdits() {
        return blockEditQueue.size();
    }

    public synchronized int clearBlockEdits() {
        int n = blockEditQueue.size();
        blockEditQueue.clear();
        return n;
    }

    public synchronized int applyBlockEdits(int limit) {
        if (!hasCapability(CAP_WORLD_CONTROL)) return 0;
        int cap = limit <= 0 ? 1024 : Math.min(limit, 20000);
        int applied = 0;
        while (applied < cap && !blockEditQueue.isEmpty()) {
            BlockEdit e = blockEditQueue.removeFirst();
            if (setBlock(e.worldUuid(), e.x(), e.y(), e.z(), e.blockTypeOrId())) {
                applied++;
            }
        }
        if (applied > 0) {
            audit("world.applyBlockEdits", "applied=" + applied + " remaining=" + blockEditQueue.size());
        }
        return applied;
    }

    public Object entityEquipment(Object entity, Object slotOrNull) {
        if (entity == null) return null;
        if (slotOrNull == null) {
            Object equip = invokeNoArgs(entity, "getEquipment");
            if (equip == null) equip = invokeNoArgs(entity, "equipment");
            if (equip == null) equip = invokeNoArgs(entity, "getArmor");
            return equip;
        }
        Object out = invokeBest(entity, "getEquipment", slotOrNull);
        if (out == null) out = invokeBest(entity, "getItemInSlot", slotOrNull);
        if (out == null) out = invokeBest(entity, "getEquippedItem", slotOrNull);
        return out;
    }

    public boolean entitySetEquipment(Object entity, Object slot, Object item) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return false;
        if (entity == null || slot == null) return false;
        boolean ok = false;
        if (tryInvoke(entity, "setEquipment", slot, item)) ok = true;
        else if (tryInvoke(entity, "setItemInSlot", slot, item)) ok = true;
        else if (tryInvoke(entity, "equip", slot, item)) ok = true;
        if (ok) {
            audit("entity.setEquipment", "entity=" + safeId(entityId(entity)) + " slot=" + slot);
        }
        return ok;
    }

    public Object entityAttribute(Object entity, String name) {
        if (entity == null || name == null || name.isBlank()) return null;
        Object out = invokeBest(entity, "getAttribute", name);
        if (out == null) out = invokeBest(entity, "getStat", name);
        if (out == null) out = invokeBest(entity, "getAttributeValue", name);
        return out;
    }

    public boolean entitySetAttribute(Object entity, String name, double value) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return false;
        if (entity == null || name == null || name.isBlank()) return false;
        boolean ok = false;
        if (tryInvoke(entity, "setAttribute", name, value)) ok = true;
        else if (tryInvoke(entity, "setStat", name, value)) ok = true;
        else if (tryInvoke(entity, "setAttributeValue", name, value)) ok = true;
        if (ok) {
            audit("entity.setAttribute", "entity=" + safeId(entityId(entity)) + " attr=" + name + " value=" + value);
        }
        return ok;
    }

    public boolean entityPathTo(Object entity, String worldUuid, double x, double y, double z) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return false;
        if (entity == null) return false;
        Object world = null;
        if (worldUuid != null && !worldUuid.isBlank()) world = findWorldByUuid(worldUuid);
        boolean ok = false;
        if (world != null && tryInvoke(entity, "pathTo", world, x, y, z)) ok = true;
        else if (world != null && tryInvoke(entity, "navigateTo", world, x, y, z)) ok = true;
        else if (tryInvoke(entity, "pathTo", x, y, z)) ok = true;
        else if (tryInvoke(entity, "navigateTo", x, y, z)) ok = true;
        else if (tryInvoke(entity, "moveTo", x, y, z)) ok = true;
        if (ok) {
            audit("entity.pathTo", "entity=" + safeId(entityId(entity)) + " x=" + x + " y=" + y + " z=" + z);
        }
        return ok;
    }

    public boolean entityStopPath(Object entity) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return false;
        if (entity == null) return false;
        boolean ok = false;
        if (tryInvoke(entity, "stopPathing")) ok = true;
        else if (tryInvoke(entity, "stopNavigation")) ok = true;
        else if (tryInvoke(entity, "clearPath")) ok = true;
        if (ok) {
            audit("entity.stopPath", "entity=" + safeId(entityId(entity)));
        }
        return ok;
    }

    private Object resolveBlockTypeModule() {
        try {
            Class<?> c = Class.forName("com.hypixel.hytale.server.core.blocktype.BlockTypeModule");
            Object m = invokeBest(c, "getInstance");
            if (m != null) return m;
        } catch (Throwable ignored) { }
        Object server = serverInstance();
        if (server != null) {
            Object module = invokeBest(server, "getModule", "blocktype");
            if (module != null) return module;
        }
        return null;
    }

    public List<Object> entityInventory(Object entity) {
        if (entity == null) return List.of();
        Object inv = invokeNoArgs(entity, "getInventory");
        if (inv == null) inv = invokeNoArgs(entity, "inventory");
        if (inv == null) return List.of();
        Object items = invokeNoArgs(inv, "getItems");
        if (items == null) items = invokeNoArgs(inv, "items");
        if (items == null) items = inv;
        return toObjectList(items);
    }

    public boolean entityGiveItem(Object entity, Object itemOrType, int count) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return false;
        if (entity == null || itemOrType == null) return false;
        int c = Math.max(1, count);
        boolean ok = false;
        if (tryInvoke(entity, "addItem", itemOrType, c)) ok = true;
        else if (tryInvoke(entity, "giveItem", itemOrType, c)) ok = true;
        else if (tryInvoke(entity, "addItem", itemOrType)) ok = true;
        else if (tryInvoke(entity, "giveItem", itemOrType)) ok = true;
        else {
            Object inv = invokeNoArgs(entity, "getInventory");
            if (inv != null && tryInvoke(inv, "addItem", itemOrType, c)) ok = true;
            else if (inv != null && tryInvoke(inv, "giveItem", itemOrType, c)) ok = true;
        }
        if (ok) {
            audit("entity.giveItem", "entity=" + safeId(entityId(entity)) + " item=" + itemOrType + " count=" + c);
        }
        return ok;
    }

    public boolean entityTakeItem(Object entity, Object itemOrType, int count) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return false;
        if (entity == null || itemOrType == null) return false;
        int c = Math.max(1, count);
        boolean ok = false;
        if (tryInvoke(entity, "removeItem", itemOrType, c)) ok = true;
        else if (tryInvoke(entity, "takeItem", itemOrType, c)) ok = true;
        else if (tryInvoke(entity, "removeItem", itemOrType)) ok = true;
        else if (tryInvoke(entity, "takeItem", itemOrType)) ok = true;
        else {
            Object inv = invokeNoArgs(entity, "getInventory");
            if (inv != null && tryInvoke(inv, "removeItem", itemOrType, c)) ok = true;
            else if (inv != null && tryInvoke(inv, "takeItem", itemOrType, c)) ok = true;
        }
        if (ok) {
            audit("entity.takeItem", "entity=" + safeId(entityId(entity)) + " item=" + itemOrType + " count=" + c);
        }
        return ok;
    }

    public List<Object> entityEffects(Object entity) {
        if (entity == null) return List.of();
        Object effects = invokeNoArgs(entity, "getEffects");
        if (effects == null) effects = invokeNoArgs(entity, "getStatusEffects");
        if (effects == null) effects = invokeNoArgs(entity, "effects");
        return toObjectList(effects);
    }

    public boolean entityAddEffect(Object entity, Object effectType, int durationTicks, int amplifier) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return false;
        if (entity == null || effectType == null) return false;
        int duration = Math.max(1, durationTicks);
        int amp = Math.max(0, amplifier);
        boolean ok = false;
        if (tryInvoke(entity, "addEffect", effectType, duration, amp)) ok = true;
        else if (tryInvoke(entity, "applyEffect", effectType, duration, amp)) ok = true;
        else if (tryInvoke(entity, "addStatusEffect", effectType, duration, amp)) ok = true;
        else if (tryInvoke(entity, "addEffect", effectType, duration)) ok = true;
        if (ok) {
            audit("entity.addEffect", "entity=" + safeId(entityId(entity)) + " effect=" + effectType + " duration=" + duration + " amp=" + amp);
        }
        return ok;
    }

    public boolean entityRemoveEffect(Object entity, Object effectType) {
        if (!hasCapability(CAP_ENTITY_CONTROL)) return false;
        if (entity == null || effectType == null) return false;
        boolean ok = false;
        if (tryInvoke(entity, "removeEffect", effectType)) ok = true;
        else if (tryInvoke(entity, "clearEffect", effectType)) ok = true;
        else if (tryInvoke(entity, "removeStatusEffect", effectType)) ok = true;
        if (ok) {
            audit("entity.removeEffect", "entity=" + safeId(entityId(entity)) + " effect=" + effectType);
        }
        return ok;
    }

    public boolean hasCapability(String capability) {
        return plugin.canUseCapability(modId, capability);
    }

    public Object serverHandle() {
        return serverInstance();
    }

    public Object universeHandle() {
        return universeInstance();
    }

    public Object defaultWorldHandle() {
        return defaultWorld();
    }

    public Object reflectiveGet(Object target, String key) {
        if (target == null || key == null || key.isBlank()) return null;
        String k = key.trim();

        Object v = invokeNoArgs(target, k);
        if (v != null) return v;

        String upper = Character.toUpperCase(k.charAt(0)) + k.substring(1);
        v = invokeNoArgs(target, "get" + upper);
        if (v != null) return v;
        v = invokeNoArgs(target, "is" + upper);
        if (v != null) return v;

        try {
            var f = target.getClass().getField(k);
            return f.get(target);
        } catch (Throwable ignored) { }
        try {
            var f = target.getClass().getDeclaredField(k);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) { }
        return null;
    }

    public boolean reflectiveSet(Object target, String key, Object value) {
        if (target == null || key == null || key.isBlank()) return false;
        String k = key.trim();
        String upper = Character.toUpperCase(k.charAt(0)) + k.substring(1);

        if (tryInvoke(target, "set" + upper, value)) {
            audit("interop.set", "target=" + target.getClass().getSimpleName() + " key=" + k);
            return true;
        }

        try {
            var f = target.getClass().getField(k);
            Object converted = convertArg(value, f.getType());
            if (converted == null && value != null && f.getType().isPrimitive()) return false;
            f.set(target, converted);
            audit("interop.set", "target=" + target.getClass().getSimpleName() + " key=" + k);
            return true;
        } catch (Throwable ignored) { }
        try {
            var f = target.getClass().getDeclaredField(k);
            f.setAccessible(true);
            Object converted = convertArg(value, f.getType());
            if (converted == null && value != null && f.getType().isPrimitive()) return false;
            f.set(target, converted);
            audit("interop.set", "target=" + target.getClass().getSimpleName() + " key=" + k);
            return true;
        } catch (Throwable ignored) { }
        return false;
    }

    public Object reflectiveCall(Object target, String methodName, Object... args) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        Object[] callArgs = args == null ? new Object[0] : args;
        try {
            for (var m : target.getClass().getMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != callArgs.length) continue;
                Object[] converted = convertArgs(m.getParameterTypes(), callArgs);
                if (converted == null) continue;
                Object out = m.invoke(target, converted);
                audit("interop.call", "target=" + target.getClass().getSimpleName() + " method=" + methodName);
                return out;
            }
        } catch (Throwable t) {
            logWarn("reflectiveCall failed: " + t);
        }
        return null;
    }

    private Object invokeBest(Object target, String methodName, Object... args) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            Class<?> cls = target instanceof Class<?> c ? c : target.getClass();
            Object receiver = target instanceof Class<?> ? null : target;
            for (var m : cls.getMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != (args == null ? 0 : args.length)) continue;
                Object[] converted = convertArgs(m.getParameterTypes(), args == null ? new Object[0] : args);
                if (converted == null) continue;
                return m.invoke(receiver, converted);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object defaultAddReason() {
        try {
            Class<?> c = Class.forName("com.hypixel.hytale.component.AddReason");
            Object[] constants = c.getEnumConstants();
            if (constants != null && constants.length > 0) {
                for (Object v : constants) {
                    if ("COMMAND".equalsIgnoreCase(String.valueOf(v))) return v;
                }
                return constants[0];
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object newVector3d(double x, double y, double z) {
        try {
            Class<?> c = Class.forName("com.hypixel.hytale.math.vector.Vector3d");
            try {
                return c.getConstructor(double.class, double.class, double.class).newInstance(x, y, z);
            } catch (Throwable ignored) { }
            Object v = c.getConstructor().newInstance();
            trySetField(v, "x", x); trySetField(v, "y", y); trySetField(v, "z", z);
            return v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object newVector3f(double x, double y, double z) {
        try {
            Class<?> c = Class.forName("com.hypixel.hytale.math.vector.Vector3f");
            try {
                return c.getConstructor(float.class, float.class, float.class).newInstance((float) x, (float) y, (float) z);
            } catch (Throwable ignored) { }
            Object v = c.getConstructor().newInstance();
            trySetField(v, "x", (float) x); trySetField(v, "y", (float) y); trySetField(v, "z", (float) z);
            return v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object newBlockVector(int x, int y, int z) {
        String[] candidates = new String[] {
                "com.hypixel.hytale.math.block.BlockPos",
                "com.hypixel.hytale.math.vector.Vector3i",
                "com.hypixel.hytale.math.vector.Vector3d"
        };
        for (String name : candidates) {
            try {
                Class<?> c = Class.forName(name);
                try {
                    return c.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
                } catch (Throwable ignored) { }
                try {
                    return c.getConstructor(double.class, double.class, double.class).newInstance((double) x, (double) y, (double) z);
                } catch (Throwable ignored) { }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void trySetField(Object target, String name, Object value) {
        if (target == null) return;
        try {
            var f = target.getClass().getField(name);
            f.set(target, convertArg(value, f.getType()));
        } catch (Throwable ignored) { }
    }

    private static boolean isAirBlock(Object block) {
        if (block == null) return true;
        try {
            Object air = block.getClass().getMethod("isAir").invoke(block);
            if (air instanceof Boolean b) return b;
        } catch (Throwable ignored) { }
        String s = String.valueOf(block).toLowerCase(Locale.ROOT);
        if (s.contains("air")) return true;
        try {
            Object id = block.getClass().getMethod("getId").invoke(block);
            if (id instanceof Number n && n.intValue() == 0) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private static List<Object> toObjectList(Object container) {
        if (container == null) return List.of();
        ArrayList<Object> out = new ArrayList<>();
        if (container instanceof Map<?, ?> map) {
            for (Object v : map.values()) if (v != null) out.add(v);
            return out;
        }
        if (container instanceof Iterable<?> it) {
            for (Object v : it) if (v != null) out.add(v);
            return out;
        }
        if (container instanceof Object[] arr) {
            for (Object v : arr) if (v != null) out.add(v);
            return out;
        }
        out.add(container);
        return out;
    }

    public List<String> reflectiveMethods(Object target, String prefix) {
        if (target == null) return List.of();
        String p = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (var m : target.getClass().getMethods()) {
            String n = m.getName();
            if (!p.isEmpty() && !n.toLowerCase(Locale.ROOT).startsWith(p)) continue;
            names.add(n + "/" + m.getParameterCount());
        }
        return new ArrayList<>(names);
    }

    private static String normalizeChannel(String channel) {
        if (channel == null) return "";
        return channel.trim().toLowerCase(Locale.ROOT);
    }

    private static String networkEventName(String channel) {
        String c = normalizeChannel(channel);
        if (c.isEmpty()) return "";
        return NETWORK_EVENT_PREFIX + c;
    }

    public boolean onNetwork(String channel, LuaFunction fn) {
        String event = networkEventName(channel);
        if (event.isEmpty() || fn == null) return false;
        on(event, fn);
        return true;
    }

    public int offNetwork(String channel, LuaFunction fn) {
        String event = networkEventName(channel);
        if (event.isEmpty()) return 0;
        if (fn == null) return clearEvent(event);
        return off(event, fn);
    }

    public Set<String> networkChannels() {
        return eventNames().stream()
                .filter(n -> n.startsWith(NETWORK_EVENT_PREFIX))
                .map(n -> n.substring(NETWORK_EVENT_PREFIX.length()))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public int emitNetwork(String channel, LuaValue payload) {
        if (!hasCapability(CAP_NETWORK_CONTROL)) return 0;
        if (!isNetworkChannelAllowed(channel)) return 0;
        String normalized = normalizeChannel(channel);
        if (normalized.isEmpty()) return 0;
        LuaModManager manager = plugin.getModManager();
        if (manager == null) return 0;
        int delivered = manager.dispatchNetworkMessage(modId, normalized, payload);
        if (delivered > 0) {
            audit("network.emit", "channel=" + normalized + " delivered=" + delivered);
        }
        return delivered;
    }

    public boolean isNetworkChannelAllowed(String channel) {
        return plugin.canUseNetworkChannel(modId, channel);
    }

    private void collectEntitiesFromContainer(Object container, List<Object> out, Set<Object> seen) {
        if (container == null) return;
        if (container instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value != null && seen.add(value)) out.add(value);
            }
            return;
        }
        if (container instanceof Iterable<?> it) {
            for (Object value : it) {
                if (value != null && seen.add(value)) out.add(value);
            }
            return;
        }
        if (container instanceof Object[] arr) {
            for (Object value : arr) {
                if (value != null && seen.add(value)) out.add(value);
            }
        }
    }

    private Object invokeNoArgs(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            var m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean tryInvoke(Object target, String methodName, Object... args) {
        if (target == null || methodName == null || methodName.isBlank()) return false;
        try {
            if (args == null || args.length == 0) {
                var m = target.getClass().getMethod(methodName);
                m.invoke(target);
                return true;
            }
            for (var m : target.getClass().getMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != args.length) continue;
                Object[] converted = convertArgs(m.getParameterTypes(), args);
                if (converted == null) continue;
                m.invoke(target, converted);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object[] convertArgs(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return null;
        Object[] out = new Object[args.length];
        for (int i = 0; i < types.length; i++) {
            Object converted = convertArg(args[i], types[i]);
            if (converted == null && args[i] != null && types[i].isPrimitive()) return null;
            if (converted != null && !types[i].isPrimitive() && !types[i].isInstance(converted)) return null;
            out[i] = converted;
        }
        return out;
    }

    private static Object convertArg(Object arg, Class<?> type) {
        if (arg == null) return null;
        if (type.isInstance(arg)) return arg;

        if (type == String.class) return String.valueOf(arg);
        if (type == boolean.class || type == Boolean.class) {
            if (arg instanceof Boolean b) return b;
            return Boolean.parseBoolean(String.valueOf(arg));
        }
        if (arg instanceof Number n) {
            if (type == int.class || type == Integer.class) return n.intValue();
            if (type == long.class || type == Long.class) return n.longValue();
            if (type == double.class || type == Double.class) return n.doubleValue();
            if (type == float.class || type == Float.class) return n.floatValue();
            if (type == short.class || type == Short.class) return n.shortValue();
            if (type == byte.class || type == Byte.class) return n.byteValue();
        }
        if (type == char.class || type == Character.class) {
            String s = String.valueOf(arg);
            return s.isEmpty() ? null : s.charAt(0);
        }
        return type.isAssignableFrom(arg.getClass()) ? arg : null;
    }

    public String dataDir() { return dataDir.toString(); }
    
    private Path resolveDataPath(String rel) throws IOException {
        if (rel == null) throw new IOException("path is null");
        rel = rel.replace('\\', '/');
        if (rel.startsWith("/") || rel.contains("..")) throw new IOException("invalid path");
        Path p = dataDir.resolve(rel).normalize();
        if (!p.startsWith(dataDir)) throw new IOException("path escape");
        return p;
    }
    
    public String readText(String rel) throws IOException {
        Path p = resolveDataPath(rel);
        if (!Files.exists(p)) throw new IOException("missing file: " + rel);
        return Files.readString(p, StandardCharsets.UTF_8);
    }
    
    public void writeText(String rel, String text) throws IOException {
        Path p = resolveDataPath(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text == null ? "" : text, StandardCharsets.UTF_8);
    }
    
    public void appendText(String rel, String text) throws IOException {
        Path p = resolveDataPath(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text == null ? "" : text, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    public boolean exists(String rel) throws IOException {
        Path p = resolveDataPath(rel);
        return Files.exists(p);
    }

    public List<String> list(String rel) throws IOException {
        String safeRel = (rel == null || rel.isBlank()) ? "." : rel;
        Path dir = resolveDataPath(safeRel);
        if (!Files.isDirectory(dir)) throw new IOException("not a directory: " + safeRel);
        try (var stream = Files.list(dir)) {
            return stream
                    .map(p -> p.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
    }

    public void mkdirs(String rel) throws IOException {
        String safeRel = (rel == null || rel.isBlank()) ? "." : rel;
        Path dir = resolveDataPath(safeRel);
        Files.createDirectories(dir);
    }

    public boolean deletePath(String rel, boolean recursive) throws IOException {
        Path p = resolveDataPath(rel);
        if (!Files.exists(p)) return false;
        if (Files.isDirectory(p) && recursive) {
            try (var walk = Files.walk(p)) {
                for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(each);
                }
            }
            return true;
        }
        return Files.deleteIfExists(p);
    }

    public boolean movePath(String fromRel, String toRel, boolean replace) throws IOException {
        Path src = resolveDataPath(fromRel);
        Path dst = resolveDataPath(toRel);
        if (!Files.exists(src)) return false;
        if (dst.getParent() != null) Files.createDirectories(dst.getParent());
        if (replace) {
            Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(src, dst);
        }
        return true;
    }

    public boolean copyPath(String fromRel, String toRel, boolean replace) throws IOException {
        Path src = resolveDataPath(fromRel);
        Path dst = resolveDataPath(toRel);
        if (!Files.exists(src)) return false;
        if (Files.isDirectory(src)) throw new IOException("copy of directories is not supported");
        if (dst.getParent() != null) Files.createDirectories(dst.getParent());
        if (replace) {
            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.copy(src, dst);
        }
        return true;
    }

    public String assetsRoot() {
        LuaAssetManager.AssetBundle bundle = manager.assetManager().bundle(modId);
        if (bundle == null || bundle.root() == null) return null;
        return bundle.root().toString();
    }

    public boolean assetExists(String relPath) {
        return manager.assetManager().assetExists(modId, relPath);
    }

    public String assetPath(String relPath) {
        Path p = manager.assetManager().assetPath(modId, relPath);
        return p == null ? null : p.toString();
    }

    public List<String> listAssets(String prefix, int limit) {
        return manager.assetManager().listAssets(modId, prefix, limit);
    }

    public boolean isActive() { return active; }

    public void command(String name, String help, LuaFunction fn) {
        if (name == null || name.isBlank() || fn == null) return;
        commands.put(name, fn);
        commandHelp.put(name, help == null ? "" : help);
    }

    public void unregisterCommand(String name) {
        if (name == null || name.isBlank()) return;
        commands.remove(name);
        commandHelp.remove(name);
    }

    public Set<String> commandNames() {
        return Collections.unmodifiableSet(commands.keySet());
    }

    public String commandHelp(String name) {
        return commandHelp.getOrDefault(name, "");
    }

    LuaValue invokeCommand(String name, LuaSender sender, LuaTable args) {
        LuaFunction fn = commands.get(name);
        if (fn == null) return LuaValue.NIL;
        return fn.call(LuaValue.userdataOf(sender), args);
    }

    public void on(String event, LuaFunction fn) {
        if (event == null || event.isBlank() || fn == null) return;
        events.on(event, fn);
    }

    public int off(String event, LuaFunction fn) {
        if (event == null || event.isBlank()) return 0;
        return events.off(event, fn);
    }

    public int clearEvent(String event) {
        if (event == null || event.isBlank()) return 0;
        return events.clear(event);
    }

    public Set<String> eventNames() {
        return events.eventNames();
    }

    public int eventListenerCount(String event) {
        return events.listenerCount(event);
    }

    public int totalEventListeners() {
        return events.totalListenerCount();
    }

    void emit(String event, LuaValue... args) {
        events.emit(event, args);
    }

    public Object setTimeout(double ms, LuaFunction fn, LuaTaskScheduler scheduler) {
        long d = (long) ms;
        ScheduledFuture<?> f = scheduler.setTimeout(d, () -> {
            if (!active) return;
            try {
                fn.call();
            } catch (Throwable t) {
                logError("Timer callback failed (timeout): " + t);
            }
        });
        tasks.add(f);
        return f;
    }

    public boolean cancelTask(Object handle) {
        if (!(handle instanceof ScheduledFuture<?> f)) return false;
        try {
            boolean cancelled = f.cancel(false);
            tasks.remove(f);
            return cancelled;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isTaskActive(Object handle) {
        if (!(handle instanceof ScheduledFuture<?> f)) return false;
        try {
            return !f.isDone() && !f.isCancelled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public Object setInterval(double ms, LuaFunction fn, LuaTaskScheduler scheduler) {
        long d = (long) ms;
        ScheduledFuture<?> f = scheduler.setInterval(d, () -> {
            if (!active) return;
            try {
                fn.call();
            } catch (Throwable t) {
                logError("Timer callback failed (interval): " + t);
            }
        });
        tasks.add(f);
        return f;
    }

    public void cleanup() {
        active = false;
        for (ScheduledFuture<?> f : tasks) {
            try { f.cancel(false); } catch (Throwable ignored) { }
        }
        tasks.clear();
        commands.clear();
        commandHelp.clear();
        events.clearAll();
    }
}
