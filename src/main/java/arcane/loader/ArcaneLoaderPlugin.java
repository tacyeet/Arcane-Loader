package arcane.loader;

import arcane.loader.command.LuaRootCommand;
import arcane.loader.lua.LuaModManager;
import arcane.loader.lua.LuaModWatcher;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;

public final class ArcaneLoaderPlugin extends JavaPlugin {

    public static final String MOD_ID = "arcane-loader";
    private static final String FALLBACK_LOADER_VERSION = "1.1.0";

    private Path serverRoot;
    private Path luaModsDir;
    private Path luaCacheDir;
    private Path luaDataDir;
    private Path luaAssetsDir;
    private Path logDir;
    private Path auditLogPath;
    private Path configPath;

    private boolean devMode = true;
    private boolean autoReload = false;
    private boolean autoEnable = false;
    private boolean autoStageAssets = true;
    private boolean allowlistEnabled = false;
    private Set<String> allowlist = Collections.emptySet();
    private double slowCallWarnMs = 10.0;
    private boolean restrictSensitiveApis = false;
    private int blockEditBudgetPerTick = 256;
    private int maxQueuedBlockEditsPerMod = 20000;
    private int maxTxBlockEditsPerMod = 10000;
    private int maxBatchSetOpsPerCall = 5000;
    private int maxBlockBehaviorNeighborUpdatesPerCause = 64;
    private Set<String> playerMovementMods = Collections.emptySet();
    private Set<String> entityControlMods = Collections.emptySet();
    private Set<String> worldControlMods = Collections.emptySet();
    private Set<String> networkControlMods = Collections.emptySet();
    private Set<String> uiControlMods = Collections.emptySet();
    private Map<String, Set<String>> networkChannelPolicies = Collections.emptyMap();

    private LuaModManager modManager;
    private LuaModWatcher watcher;
    private EventRegistration<?, ?> playerConnectBridge;
    private EventRegistration<?, ?> playerDisconnectBridge;
    private EventRegistration<?, ?> playerChatBridge;
    private EventRegistration<?, ?> playerReadyBridge;
    private EventRegistration<?, ?> playerWorldJoinBridge;
    private EventRegistration<?, ?> playerWorldLeaveBridge;
    private final List<EventRegistration<?, ?>> extendedEventBridges = new ArrayList<>();

    public ArcaneLoaderPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        initPaths();
        ensureFolders();
        ensureDefaultConfig();
        readConfig();

        this.modManager = new LuaModManager(this, devMode);
        getCommandRegistry().registerCommand(new LuaRootCommand(this));

        getLogger().at(Level.INFO).log(MOD_ID + " setup complete. Root=" + serverRoot);
        getLogger().at(Level.INFO).log(
                "Paths: lua_mods=" + luaModsDir
                        + " lua_cache=" + luaCacheDir
                        + " lua_data=" + luaDataDir
                        + " lua_assets=" + luaAssetsDir
                        + " logs=" + logDir
                        + " config=" + configPath
        );
        getLogger().at(Level.INFO).log(
                "Config: devMode=" + devMode
                        + " autoReload=" + autoReload
                        + " autoEnable=" + autoEnable
                        + " autoStageAssets=" + autoStageAssets
                        + " allowlistEnabled=" + allowlistEnabled
                        + " allowlistCount=" + allowlist.size()
                        + " slowCallWarnMs=" + slowCallWarnMs
                        + " blockEditBudgetPerTick=" + getBlockEditBudgetPerTick()
                        + " maxQueuedBlockEditsPerMod=" + getMaxQueuedBlockEditsPerMod()
                        + " maxTxBlockEditsPerMod=" + getMaxTxBlockEditsPerMod()
                        + " maxBatchSetOpsPerCall=" + getMaxBatchSetOpsPerCall()
                        + " maxBlockBehaviorNeighborUpdatesPerCause=" + getMaxBlockBehaviorNeighborUpdatesPerCause()
                        + " restrictSensitiveApis=" + restrictSensitiveApis
        );
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log(MOD_ID + " started.");
        reconcileAutoReloadWatcher();

        if (autoEnable) {
            try {
                modManager.scanMods();
                modManager.enableAll();
                getLogger().at(Level.INFO).log("AutoEnable enabled (enabled all discovered Lua mods).");
            } catch (Exception e) {
                getLogger().at(Level.WARNING).log("AutoEnable failed: " + e);
            }
        }

        try {
            modManager.emitToEnabled("server_start");
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("Failed emitting server_start: " + e);
        }
        try {
            modManager.startTickLoop();
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("Failed starting Lua tick loop: " + e);
        }

        registerServerEventBridges();
    }

    @Override
    protected void shutdown() {
        getLogger().at(Level.INFO).log(MOD_ID + " shutting down.");
        if (modManager != null) {
            try {
                modManager.emitToEnabled("server_stop");
            } catch (Exception e) {
                getLogger().at(Level.WARNING).log("Failed emitting server_stop: " + e);
            }
            try {
                modManager.close();
            } catch (Exception e) {
                getLogger().at(Level.WARNING).log("Failed closing mod manager: " + e);
            }
        }
        if (watcher != null) {
            try {
                watcher.close();
            } catch (Exception ignored) { }
            watcher = null;
        }
        playerConnectBridge = null;
        playerDisconnectBridge = null;
        playerChatBridge = null;
        playerReadyBridge = null;
        playerWorldJoinBridge = null;
        playerWorldLeaveBridge = null;
        extendedEventBridges.clear();
    }

    private void registerServerEventBridges() {
        try {
            playerConnectBridge = getEventRegistry().register(PlayerConnectEvent.class, event -> {
                if (modManager != null) modManager.emitToEnabled("player_connect", playerPayload(event.getPlayerRef(), event));
            });
            playerDisconnectBridge = getEventRegistry().register(PlayerDisconnectEvent.class, event -> {
                if (modManager != null) modManager.emitToEnabled("player_disconnect", playerPayload(event.getPlayerRef(), event));
            });
            playerChatBridge = getEventRegistry().registerAsyncGlobal(PlayerChatEvent.class, chain ->
                    chain.thenApply(event -> {
                        if (modManager != null) modManager.emitToEnabled("player_chat", playerChatPayload(event));
                        return event;
                    })
            );
            playerReadyBridge = getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> {
                if (modManager != null) modManager.emitToEnabled("player_ready", playerReadyPayload(event));
            });
            playerWorldJoinBridge = getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, event -> {
                if (modManager != null) modManager.emitToEnabled("player_world_join", playerWorldPayload(event, "player_world_join"));
            });
            playerWorldLeaveBridge = getEventRegistry().registerGlobal(DrainPlayerFromWorldEvent.class, event -> {
                if (modManager != null) modManager.emitToEnabled("player_world_leave", playerWorldPayload(event, "player_world_leave"));
            });
            registerExtendedEventBridges();
            getLogger().at(Level.INFO).log(
                    "Registered Lua event bridges: player_connect, player_disconnect, player_chat, player_ready, player_world_join, player_world_leave"
            );
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("Failed registering server event bridges: " + e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerExtendedEventBridges() {
        extendedEventBridges.clear();
        int count = 0;
        count += registerBridgeByName("com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent", "block_break");
        count += registerBridgeByName("com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent", "block_place");
        count += registerBridgeByName("com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent", "block_use");
        count += registerBridgeByName("com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent", "block_damage");
        count += registerBridgeByName("com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent", "item_drop");
        count += registerBridgeByName("com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent", "item_pickup");
        count += registerBridgeByName("com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent", "player_interact");
        count += registerBridgeByName("com.hypixel.hytale.server.core.event.events.player.PlayerCraftEvent", "player_craft");
        count += registerBridgeByName("com.hypixel.hytale.server.core.event.events.entity.EntityRemoveEvent", "entity_remove");
        count += registerBridgeByName("com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent", "entity_inventory_change");
        count += registerBridgeByName("com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent", "world_add");
        count += registerBridgeByName("com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent", "world_remove");
        count += registerBridgeByName("com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent", "world_start");
        count += registerBridgeByName("com.hypixel.hytale.server.core.universe.world.events.AllWorldsLoadedEvent", "worlds_loaded");
        count += registerBridgeByName("com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkSaveEvent", "chunk_save");
        count += registerBridgeByName("com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkUnloadEvent", "chunk_unload");
        if (count > 0) {
            getLogger().at(Level.INFO).log("Registered " + count + " extended Lua event bridge(s).");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int registerBridgeByName(String className, String luaEventName) {
        try {
            Class eventType = Class.forName(className);
            EventRegistration<?, ?> reg = getEventRegistry().registerGlobal(eventType, event -> {
                if (modManager != null) modManager.emitToEnabled(luaEventName, genericEventPayload(event, luaEventName));
                if (modManager != null && ("block_place".equals(luaEventName) || "block_break".equals(luaEventName))) {
                    modManager.dispatchNativeBlockBehavior(luaEventName, event);
                }
            });
            extendedEventBridges.add(reg);
            return 1;
        } catch (Throwable t) {
            getLogger().at(Level.FINE).log("Skipping optional bridge " + className + ": " + t);
            return 0;
        }
    }

    private static LuaValue playerPayload(PlayerRef ref, Object event) {
        LuaTable out = new LuaTable();
        if (ref != null) {
            out.set("username", LuaValue.valueOf(String.valueOf(ref.getUsername())));
            if (ref.getWorldUuid() != null) {
                out.set("worldUuid", LuaValue.valueOf(ref.getWorldUuid().toString()));
            }
            out.set("playerRef", LuaValue.userdataOf(ref));
        }
        if (event != null) {
            out.set("eventType", LuaValue.valueOf(event.getClass().getSimpleName()));
        }
        return out;
    }

    private static LuaValue playerChatPayload(PlayerChatEvent event) {
        LuaTable out = new LuaTable();
        out.set("content", LuaValue.valueOf(String.valueOf(event.getContent())));
        out.set("eventType", LuaValue.valueOf(event.getClass().getSimpleName()));

        PlayerRef sender = event.getSender();
        if (sender != null) {
            out.set("username", LuaValue.valueOf(String.valueOf(sender.getUsername())));
            if (sender.getWorldUuid() != null) {
                out.set("worldUuid", LuaValue.valueOf(sender.getWorldUuid().toString()));
            }
            out.set("playerRef", LuaValue.userdataOf(sender));
        }
        addCancellableControls(out, event);
        addChatControls(out, event);
        return out;
    }

    private static LuaValue playerReadyPayload(PlayerReadyEvent event) {
        LuaTable out = new LuaTable();
        out.set("eventType", LuaValue.valueOf(event.getClass().getSimpleName()));
        out.set("readyId", LuaValue.valueOf(event.getReadyId()));
        if (event.getPlayer() != null) {
            out.set("player", LuaValue.userdataOf(event.getPlayer()));
        }
        if (event.getPlayerRef() != null) {
            out.set("playerEntityRef", LuaValue.userdataOf(event.getPlayerRef()));
        }
        return out;
    }

    private static LuaValue playerWorldPayload(Object event, String eventType) {
        LuaTable out = new LuaTable();
        out.set("eventType", LuaValue.valueOf(eventType));
        try {
            var worldMethod = event.getClass().getMethod("getWorld");
            Object world = worldMethod.invoke(event);
            if (world != null) out.set("world", LuaValue.userdataOf(world));
        } catch (Throwable ignored) { }
        try {
            var holderMethod = event.getClass().getMethod("getHolder");
            Object holder = holderMethod.invoke(event);
            if (holder != null) out.set("holder", LuaValue.userdataOf(holder));
        } catch (Throwable ignored) { }
        return out;
    }

    private static LuaValue genericEventPayload(Object event, String eventName) {
        LuaTable out = new LuaTable();
        out.set("eventType", LuaValue.valueOf(eventName));
        if (event == null) return out;
        out.set("rawType", LuaValue.valueOf(event.getClass().getSimpleName()));

        copyGetterString(out, event, "getContent", "content");
        copyGetterString(out, event, "getMessage", "message");
        copyGetterString(out, event, "getReason", "reason");
        copyGetterString(out, event, "getReadyId", "readyId");
        copyGetterString(out, event, "getWorldUuid", "worldUuid");

        copyGetterUserdata(out, event, "getPlayer", "player");
        copyGetterUserdata(out, event, "getPlayerRef", "playerRef");
        copyGetterUserdata(out, event, "getSender", "sender");
        copyGetterUserdata(out, event, "getEntity", "entity");
        copyGetterUserdata(out, event, "getItem", "item");
        copyGetterUserdata(out, event, "getStack", "stack");
        copyGetterUserdata(out, event, "getBlock", "block");
        copyGetterUserdata(out, event, "getWorld", "world");
        copyGetterUserdata(out, event, "getHolder", "holder");

        // Best-effort common coordinates.
        copyGetterNumber(out, event, "getX", "x");
        copyGetterNumber(out, event, "getY", "y");
        copyGetterNumber(out, event, "getZ", "z");
        addCancellableControls(out, event);
        return out;
    }

    private static void copyGetterUserdata(LuaTable out, Object event, String methodName, String key) {
        try {
            Object value = event.getClass().getMethod(methodName).invoke(event);
            if (value != null) out.set(key, LuaValue.userdataOf(value));
        } catch (Throwable ignored) { }
    }

    private static void copyGetterString(LuaTable out, Object event, String methodName, String key) {
        try {
            Object value = event.getClass().getMethod(methodName).invoke(event);
            if (value != null) out.set(key, LuaValue.valueOf(String.valueOf(value)));
        } catch (Throwable ignored) { }
    }

    private static void copyGetterNumber(LuaTable out, Object event, String methodName, String key) {
        try {
            Object value = event.getClass().getMethod(methodName).invoke(event);
            if (value instanceof Number n) {
                out.set(key, LuaValue.valueOf(n.doubleValue()));
            }
        } catch (Throwable ignored) { }
    }

    private static void addCancellableControls(LuaTable out, Object event) {
        if (event == null) return;
        out.set("isCancellable", LuaValue.valueOf(hasMethod(event, "setCancelled", boolean.class) || hasMethod(event, "cancel")));
        out.set("cancel", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if (tryInvoke(event, "setCancelled", true) || tryInvoke(event, "cancel")) {
                    return LuaValue.TRUE;
                }
                return LuaValue.FALSE;
            }
        });
        out.set("setCancelled", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                boolean cancelled = value.toboolean();
                if (tryInvoke(event, "setCancelled", cancelled)) {
                    return LuaValue.TRUE;
                }
                if (cancelled && tryInvoke(event, "cancel")) {
                    return LuaValue.TRUE;
                }
                return LuaValue.FALSE;
            }
        });
        out.set("isCancelled", new ZeroArgFunction() {
            @Override public LuaValue call() {
                Object value = invokeNoArgs(event, "isCancelled");
                if (value instanceof Boolean b) {
                    return LuaValue.valueOf(b);
                }
                return LuaValue.FALSE;
            }
        });
    }

    private static void addChatControls(LuaTable out, Object event) {
        if (event == null) return;
        out.set("setContent", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                String next = value.optjstring("");
                if (tryInvoke(event, "setContent", next) || tryInvoke(event, "setMessage", next)) {
                    out.set("content", LuaValue.valueOf(next));
                    return LuaValue.TRUE;
                }
                return LuaValue.FALSE;
            }
        });
        out.set("getContent", new ZeroArgFunction() {
            @Override public LuaValue call() {
                Object current = invokeNoArgs(event, "getContent");
                if (current == null) current = invokeNoArgs(event, "getMessage");
                return current == null ? LuaValue.NIL : LuaValue.valueOf(String.valueOf(current));
            }
        });
    }

    private static boolean hasMethod(Object target, String name, Class<?>... params) {
        if (target == null) return false;
        try {
            target.getClass().getMethod(name, params);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object invokeNoArgs(Object target, String name) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean tryInvoke(Object target, String name, Object... args) {
        if (target == null) return false;
        try {
            if (args == null || args.length == 0) {
                target.getClass().getMethod(name).invoke(target);
                return true;
            }
            for (var m : target.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != args.length) continue;
                Object[] converted = new Object[args.length];
                boolean ok = true;
                for (int i = 0; i < args.length; i++) {
                    Object v = args[i];
                    Class<?> type = p[i];
                    Object c = convertArg(v, type);
                    if (c == null && v != null && type.isPrimitive()) {
                        ok = false;
                        break;
                    }
                    converted[i] = c;
                }
                if (!ok) continue;
                m.invoke(target, converted);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object convertArg(Object value, Class<?> type) {
        if (value == null) return null;
        if (type.isInstance(value)) return value;
        if (type == String.class) return String.valueOf(value);
        if (type == boolean.class || type == Boolean.class) {
            if (value instanceof Boolean b) return b;
            return Boolean.parseBoolean(String.valueOf(value));
        }
        if (type == int.class || type == Integer.class) {
            if (value instanceof Number n) return n.intValue();
            try { return Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return null; }
        }
        if (type == long.class || type == Long.class) {
            if (value instanceof Number n) return n.longValue();
            try { return Long.parseLong(String.valueOf(value)); } catch (Exception e) { return null; }
        }
        if (type == double.class || type == Double.class) {
            if (value instanceof Number n) return n.doubleValue();
            try { return Double.parseDouble(String.valueOf(value)); } catch (Exception e) { return null; }
        }
        return value;
    }

    private void initPaths() {
        this.serverRoot = Paths.get("").toAbsolutePath().normalize();
        this.luaModsDir = serverRoot.resolve("lua_mods");
        this.luaCacheDir = serverRoot.resolve("lua_cache");
        this.luaDataDir = serverRoot.resolve("lua_data");
        this.luaAssetsDir = serverRoot.resolve("lua_assets");
        this.logDir = serverRoot.resolve("logs");
        this.auditLogPath = logDir.resolve("arcane-audit.log");
        this.configPath = serverRoot.resolve("arcane-loader.json");
    }

    private void ensureFolders() {
        try {
            Files.createDirectories(luaModsDir);
            Files.createDirectories(luaCacheDir);
            Files.createDirectories(luaDataDir);
            Files.createDirectories(luaAssetsDir);
            Files.createDirectories(logDir);
        } catch (IOException e) {
            getLogger().at(Level.SEVERE).log("Failed creating Arcane Loader folders: " + e);
        }
    }

    private void ensureDefaultConfig() {
        if (Files.exists(configPath)) return;
        try {
            Files.writeString(configPath, ArcaneLoaderConfig.DEFAULTS.toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().at(Level.WARNING).log("Could not write default config to " + configPath + ": " + e);
        }
    }

    private void readConfig() {
        try {
            if (!Files.exists(configPath)) return;
            ArcaneLoaderConfig config = ArcaneLoaderConfig.parse(Files.readString(configPath, StandardCharsets.UTF_8), currentConfigSnapshot());
            applyConfig(config);
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("Failed reading config " + configPath + ": " + e);
        }
    }

    public boolean reloadArcaneConfig() {
        try {
            readConfig();
            reconcileAutoReloadWatcher();

            if (modManager != null) {
                modManager.scanMods();
            }
            getLogger().at(Level.INFO).log(
                    "Reloaded arcane-loader.json: devMode=" + devMode
                            + " autoReload=" + autoReload
                            + " autoEnable=" + autoEnable
                            + " autoStageAssets=" + autoStageAssets
                            + " blockEditBudgetPerTick=" + getBlockEditBudgetPerTick()
                            + " maxQueuedBlockEditsPerMod=" + getMaxQueuedBlockEditsPerMod()
                            + " maxTxBlockEditsPerMod=" + getMaxTxBlockEditsPerMod()
                            + " maxBatchSetOpsPerCall=" + getMaxBatchSetOpsPerCall()
                            + " maxBlockBehaviorNeighborUpdatesPerCause=" + getMaxBlockBehaviorNeighborUpdatesPerCause()
                            + " restrictSensitiveApis=" + restrictSensitiveApis
                            + " allowlistEnabled=" + allowlistEnabled
            );
            return true;
        } catch (Throwable t) {
            getLogger().at(Level.WARNING).log("Failed reloading arcane-loader.json: " + t);
            return false;
        }
    }

    private void reconcileAutoReloadWatcher() {
        if (autoReload) {
            if (watcher == null && modManager != null) {
                try {
                    watcher = new LuaModWatcher(this, modManager, luaModsDir, 300);
                    watcher.start();
                    getLogger().at(Level.INFO).log("AutoReload watcher active (watching lua_mods).");
                } catch (Exception e) {
                    getLogger().at(Level.WARNING).log("Failed starting lua_mods watcher: " + e);
                }
            }
            return;
        }

        if (watcher != null) {
            try {
                watcher.close();
            } catch (Exception ignored) { }
            watcher = null;
            getLogger().at(Level.INFO).log("AutoReload watcher stopped.");
        }
    }

    private ArcaneLoaderConfig currentConfigSnapshot() {
        return new ArcaneLoaderConfig(
                devMode,
                autoReload,
                autoEnable,
                autoStageAssets,
                slowCallWarnMs,
                blockEditBudgetPerTick,
                maxQueuedBlockEditsPerMod,
                maxTxBlockEditsPerMod,
                maxBatchSetOpsPerCall,
                maxBlockBehaviorNeighborUpdatesPerCause,
                restrictSensitiveApis,
                allowlistEnabled,
                allowlist,
                playerMovementMods,
                entityControlMods,
                worldControlMods,
                networkControlMods,
                uiControlMods,
                networkChannelPolicies
        );
    }

    private void applyConfig(ArcaneLoaderConfig config) {
        devMode = config.devMode();
        autoReload = config.autoReload();
        autoEnable = config.autoEnable();
        autoStageAssets = config.autoStageAssets();
        slowCallWarnMs = config.slowCallWarnMs();
        blockEditBudgetPerTick = config.blockEditBudgetPerTick();
        maxQueuedBlockEditsPerMod = config.maxQueuedBlockEditsPerMod();
        maxTxBlockEditsPerMod = config.maxTxBlockEditsPerMod();
        maxBatchSetOpsPerCall = config.maxBatchSetOpsPerCall();
        maxBlockBehaviorNeighborUpdatesPerCause = config.maxBlockBehaviorNeighborUpdatesPerCause();
        restrictSensitiveApis = config.restrictSensitiveApis();
        allowlistEnabled = config.allowlistEnabled();
        allowlist = config.allowlist();
        playerMovementMods = config.playerMovementMods();
        entityControlMods = config.entityControlMods();
        worldControlMods = config.worldControlMods();
        networkControlMods = config.networkControlMods();
        uiControlMods = config.uiControlMods();
        networkChannelPolicies = config.networkChannelPolicies();
    }

    public LuaModManager getModManager() { return modManager; }
    public boolean isDevMode() { return devMode; }
    public boolean isAutoReload() { return autoReload; }
    public boolean isAutoEnable() { return autoEnable; }
    public boolean isAutoStageAssets() { return autoStageAssets; }
    public String getLoaderVersion() {
        Package pkg = getClass().getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        if (version == null || version.isBlank()) return FALLBACK_LOADER_VERSION;
        return version;
    }
    public boolean isAllowlistEnabled() { return allowlistEnabled; }
    public double getSlowCallWarnMs() { return slowCallWarnMs; }
    public int getBlockEditBudgetPerTick() { return Math.max(0, blockEditBudgetPerTick); }
    public int getMaxQueuedBlockEditsPerMod() { return Math.max(1, maxQueuedBlockEditsPerMod); }
    public int getMaxTxBlockEditsPerMod() { return Math.max(1, maxTxBlockEditsPerMod); }
    public int getMaxBatchSetOpsPerCall() { return Math.max(1, maxBatchSetOpsPerCall); }
    public int getMaxBlockBehaviorNeighborUpdatesPerCause() { return Math.max(1, maxBlockBehaviorNeighborUpdatesPerCause); }
    public boolean isRestrictSensitiveApis() { return restrictSensitiveApis; }

    public Path getServerRoot() { return serverRoot; }
    public Path getLuaModsDir() { return luaModsDir; }
    public Path getLuaCacheDir() { return luaCacheDir; }
    public Path getLuaDataDir() { return luaDataDir; }
    public Path getLuaAssetsDir() { return luaAssetsDir; }
    public Path getLogDir() { return logDir; }
    public Path getConfigPath() { return configPath; }
    public Set<String> getAllowlist() { return allowlist; }
    public Set<String> getPlayerMovementMods() { return playerMovementMods; }
    public Set<String> getEntityControlMods() { return entityControlMods; }
    public Set<String> getWorldControlMods() { return worldControlMods; }
    public Set<String> getNetworkControlMods() { return networkControlMods; }
    public Set<String> getUiControlMods() { return uiControlMods; }
    public Map<String, Set<String>> getNetworkChannelPolicies() { return networkChannelPolicies; }
    public boolean isWatcherActive() { return watcher != null; }
    public Path getAuditLogPath() { return auditLogPath; }

    public synchronized void auditSecurityAction(String modId, String action, String detail) {
        try {
            Files.createDirectories(logDir);
            String safeMod = (modId == null || modId.isBlank()) ? "unknown" : modId;
            String safeAction = (action == null || action.isBlank()) ? "action" : action;
            String safeDetail = detail == null ? "" : detail.replace('\n', ' ').replace('\r', ' ');
            String line = java.time.Instant.now() + " mod=" + safeMod + " action=" + safeAction + " detail=" + safeDetail + System.lineSeparator();
            Files.writeString(
                    auditLogPath,
                    line,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("Failed writing Arcane audit log: " + e);
        }
    }

    private static String normalizePolicyModId(String modId, boolean allowWildcard) {
        if (modId == null) return null;
        String normalized = modId.trim().toLowerCase();
        if (normalized.isEmpty()) return null;
        if (allowWildcard && normalized.equals("*")) return "*";
        if (!normalized.matches("[a-z0-9._-]+")) return null;
        return normalized;
    }

    private static String normalizePolicyPrefix(String prefix) {
        if (prefix == null) return null;
        String normalized = prefix.trim().toLowerCase();
        if (normalized.isEmpty()) return null;
        if (normalized.chars().anyMatch(Character::isWhitespace)) return null;
        return normalized;
    }

    private static String normalizeCapabilityName(String capability) {
        if (capability == null) return null;
        String c = capability.trim().toLowerCase();
        return switch (c) {
            case "player-movement", "entity-control", "world-control", "network-control", "ui-control" -> c;
            default -> null;
        };
    }

    private static Set<String> immutableSortedCopy(Set<String> source) {
        TreeSet<String> copy = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (source != null) {
            for (String value : source) {
                if (value == null || value.isBlank()) continue;
                copy.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Map<String, Set<String>> immutablePolicyCopy(Map<String, Set<String>> source) {
        TreeMap<String, Set<String>> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (source != null) {
            for (Map.Entry<String, Set<String>> ent : source.entrySet()) {
                String key = normalizePolicyModId(ent.getKey(), true);
                if (key == null) continue;
                Set<String> raw = ent.getValue() == null ? Collections.emptySet() : ent.getValue();
                Set<String> prefixes = immutableSortedCopy(raw.stream()
                        .map(ArcaneLoaderPlugin::normalizePolicyPrefix)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet()));
                sorted.put(key, prefixes);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private synchronized boolean persistCurrentConfig() {
        try {
            Files.writeString(configPath, renderConfigJson(), StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("Failed writing config " + configPath + ": " + e);
            return false;
        }
    }

    private synchronized String renderConfigJson() {
        return currentConfigSnapshot().toJson();
    }

    public synchronized boolean setCapabilityGrant(String modId, String capability, boolean enabled) {
        String normalizedMod = normalizePolicyModId(modId, false);
        String normalizedCap = normalizeCapabilityName(capability);
        if (normalizedMod == null || normalizedCap == null) return false;

        Set<String> oldPlayer = playerMovementMods;
        Set<String> oldEntity = entityControlMods;
        Set<String> oldWorld = worldControlMods;
        Set<String> oldNetwork = networkControlMods;
        Set<String> oldUi = uiControlMods;

        Set<String> next = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        switch (normalizedCap) {
            case "player-movement" -> next.addAll(playerMovementMods);
            case "entity-control" -> next.addAll(entityControlMods);
            case "world-control" -> next.addAll(worldControlMods);
            case "network-control" -> next.addAll(networkControlMods);
            case "ui-control" -> next.addAll(uiControlMods);
            default -> { return false; }
        }
        boolean changed = enabled ? next.add(normalizedMod) : next.remove(normalizedMod);
        if (!changed) return true;

        Set<String> immutableNext = Collections.unmodifiableSet(next);
        switch (normalizedCap) {
            case "player-movement" -> playerMovementMods = immutableNext;
            case "entity-control" -> entityControlMods = immutableNext;
            case "world-control" -> worldControlMods = immutableNext;
            case "network-control" -> networkControlMods = immutableNext;
            case "ui-control" -> uiControlMods = immutableNext;
        }
        if (!persistCurrentConfig()) {
            playerMovementMods = oldPlayer;
            entityControlMods = oldEntity;
            worldControlMods = oldWorld;
            networkControlMods = oldNetwork;
            uiControlMods = oldUi;
            return false;
        }
        auditSecurityAction("system", "policy.capability", "mod=" + normalizedMod + " capability=" + normalizedCap + " enabled=" + enabled);
        return true;
    }

    public synchronized boolean addNetworkChannelPrefix(String modId, String prefix) {
        String normalizedMod = normalizePolicyModId(modId, true);
        String normalizedPrefix = normalizePolicyPrefix(prefix);
        if (normalizedMod == null || normalizedPrefix == null) return false;

        Map<String, Set<String>> oldPolicies = networkChannelPolicies;
        LinkedHashMap<String, Set<String>> nextMap = new LinkedHashMap<>(networkChannelPolicies);
        TreeSet<String> prefixes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        prefixes.addAll(nextMap.getOrDefault(normalizedMod, Collections.emptySet()));
        boolean changed = prefixes.add(normalizedPrefix);
        if (!changed) return true;
        nextMap.put(normalizedMod, Collections.unmodifiableSet(prefixes));
        networkChannelPolicies = immutablePolicyCopy(nextMap);
        if (!persistCurrentConfig()) {
            networkChannelPolicies = oldPolicies;
            return false;
        }
        auditSecurityAction("system", "policy.channel.allow", "mod=" + normalizedMod + " prefix=" + normalizedPrefix);
        return true;
    }

    public synchronized boolean removeNetworkChannelPrefix(String modId, String prefix) {
        String normalizedMod = normalizePolicyModId(modId, true);
        String normalizedPrefix = normalizePolicyPrefix(prefix);
        if (normalizedMod == null || normalizedPrefix == null) return false;

        Map<String, Set<String>> oldPolicies = networkChannelPolicies;
        LinkedHashMap<String, Set<String>> nextMap = new LinkedHashMap<>(networkChannelPolicies);
        TreeSet<String> prefixes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        prefixes.addAll(nextMap.getOrDefault(normalizedMod, Collections.emptySet()));
        boolean changed = prefixes.remove(normalizedPrefix);
        if (!changed) return true;
        if (prefixes.isEmpty()) {
            nextMap.remove(normalizedMod);
        } else {
            nextMap.put(normalizedMod, Collections.unmodifiableSet(prefixes));
        }
        networkChannelPolicies = immutablePolicyCopy(nextMap);
        if (!persistCurrentConfig()) {
            networkChannelPolicies = oldPolicies;
            return false;
        }
        auditSecurityAction("system", "policy.channel.deny", "mod=" + normalizedMod + " prefix=" + normalizedPrefix);
        return true;
    }

    public synchronized boolean clearNetworkChannelPolicy(String modId) {
        String normalizedMod = normalizePolicyModId(modId, true);
        if (normalizedMod == null) return false;
        if (!networkChannelPolicies.containsKey(normalizedMod)) return true;

        Map<String, Set<String>> oldPolicies = networkChannelPolicies;
        LinkedHashMap<String, Set<String>> nextMap = new LinkedHashMap<>(networkChannelPolicies);
        nextMap.remove(normalizedMod);
        networkChannelPolicies = immutablePolicyCopy(nextMap);
        if (!persistCurrentConfig()) {
            networkChannelPolicies = oldPolicies;
            return false;
        }
        auditSecurityAction("system", "policy.channel.clear", "mod=" + normalizedMod);
        return true;
    }

    public boolean isModAllowed(String modId) {
        if (!allowlistEnabled) return true;
        if (modId == null) return false;
        return allowlist.contains(modId);
    }

    public boolean canUseCapability(String modId, String capability) {
        if (!restrictSensitiveApis) return true;
        if (modId == null || modId.isBlank() || capability == null || capability.isBlank()) return false;
        return switch (capability) {
            case "player-movement" -> playerMovementMods.contains(modId);
            case "entity-control" -> entityControlMods.contains(modId);
            case "world-control" -> worldControlMods.contains(modId);
            case "network-control" -> networkControlMods.contains(modId);
            case "ui-control" -> uiControlMods.contains(modId);
            default -> false;
        };
    }

    public boolean canUseNetworkChannel(String modId, String channel) {
        return evaluateNetworkChannel(modId, channel).allowed();
    }

    public Set<String> networkPrefixesForMod(String modId) {
        String normalizedMod = modId == null ? "" : modId.trim().toLowerCase();
        Set<String> prefixes = networkChannelPolicies.get(normalizedMod);
        if (prefixes == null || prefixes.isEmpty()) {
            prefixes = networkChannelPolicies.get("*");
        }
        return prefixes == null ? Collections.emptySet() : prefixes;
    }

    public String matchingNetworkPrefix(String modId, String channel) {
        if (channel == null || channel.isBlank()) return null;
        String normalizedChannel = channel.trim().toLowerCase();
        for (String p : networkPrefixesForMod(modId)) {
            if (p == null || p.isBlank()) continue;
            if (normalizedChannel.startsWith(p.toLowerCase())) return p;
        }
        return null;
    }

    public NetworkChannelDecision evaluateNetworkChannel(String modId, String channel) {
        if (channel == null || channel.isBlank()) {
            return new NetworkChannelDecision(false, false, null, "invalid-channel");
        }

        boolean capabilityAllowed = canUseCapability(modId, "network-control");
        if (!capabilityAllowed) {
            return new NetworkChannelDecision(false, false, null, "missing-network-control");
        }

        if (networkChannelPolicies.isEmpty()) {
            return new NetworkChannelDecision(true, true, null, "no-policy");
        }

        Set<String> prefixes = networkPrefixesForMod(modId);
        if (prefixes == null || prefixes.isEmpty()) {
            return new NetworkChannelDecision(true, true, null, "no-prefixes");
        }

        String matched = matchingNetworkPrefix(modId, channel);
        if (matched != null) {
            return new NetworkChannelDecision(true, true, matched, "matched-prefix");
        }
        return new NetworkChannelDecision(false, true, null, "prefix-miss");
    }

    public record NetworkChannelDecision(boolean allowed, boolean capabilityAllowed, String matchedPrefix, String reason) {}
}
