package arcane.loader.lua;

import arcane.loader.ArcaneLoaderPlugin;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class LuaBlockBehaviorRuntime {
    private static final int[][] CARDINAL_NEIGHBORS = new int[][] {
            { 1, 0, 0 },
            { -1, 0, 0 },
            { 0, 1, 0 },
            { 0, -1, 0 },
            { 0, 0, 1 },
            { 0, 0, -1 }
    };

    private final ArcaneLoaderPlugin plugin;
    private final LuaModManager manager;
    private final Map<String, Map<String, Registration>> registrationsByMod = new ConcurrentHashMap<>();

    public LuaBlockBehaviorRuntime(ArcaneLoaderPlugin plugin, LuaModManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public boolean register(String modId, Object blockTypeOrId, LuaTable handlers) {
        String normalizedModId = normalizeModId(modId);
        String key = normalizeBlockKey(blockTypeOrId);
        if (normalizedModId == null || key == null || handlers == null) return false;

        LuaFunction onPlaced = asFunction(handlers.get("onPlaced"));
        LuaFunction onBroken = asFunction(handlers.get("onBroken"));
        LuaFunction onNeighborChanged = asFunction(handlers.get("onNeighborChanged"));
        LuaFunction computeState = asFunction(handlers.get("computeState"));
        if (onPlaced == null && onBroken == null && onNeighborChanged == null && computeState == null) {
            return false;
        }

        registrationsByMod
                .computeIfAbsent(normalizedModId, ignored -> new ConcurrentHashMap<>())
                .put(key, new Registration(key, onPlaced, onBroken, onNeighborChanged, computeState));
        return true;
    }

    public boolean unregister(String modId, Object blockTypeOrId) {
        String normalizedModId = normalizeModId(modId);
        String key = normalizeBlockKey(blockTypeOrId);
        if (normalizedModId == null || key == null) return false;
        Map<String, Registration> registrations = registrationsByMod.get(normalizedModId);
        if (registrations == null) return false;
        Registration removed = registrations.remove(key);
        if (registrations.isEmpty()) {
            registrationsByMod.remove(normalizedModId, registrations);
        }
        return removed != null;
    }

    public int clear(String modId) {
        String normalizedModId = normalizeModId(modId);
        if (normalizedModId == null) return 0;
        Map<String, Registration> removed = registrationsByMod.remove(normalizedModId);
        return removed == null ? 0 : removed.size();
    }

    public List<Map<String, Object>> list(String modId) {
        String normalizedModId = normalizeModId(modId);
        if (normalizedModId == null) return List.of();
        Map<String, Registration> registrations = registrationsByMod.get(normalizedModId);
        if (registrations == null || registrations.isEmpty()) return List.of();

        ArrayList<Map<String, Object>> out = new ArrayList<>();
        ArrayList<String> keys = new ArrayList<>(registrations.keySet());
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        for (String key : keys) {
            Registration registration = registrations.get(key);
            if (registration == null) continue;
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("key", registration.key());
            row.put("onPlaced", registration.onPlaced() != null);
            row.put("onBroken", registration.onBroken() != null);
            row.put("onNeighborChanged", registration.onNeighborChanged() != null);
            row.put("computeState", registration.computeState() != null);
            out.add(Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableList(out);
    }

    public void handleBlockSet(LuaModContext sourceCtx, String worldUuid, int x, int y, int z, Object oldBlock, Object newBlock, String reason) {
        if (worldUuid == null || worldUuid.isBlank()) return;
        DispatchContext dispatch = new DispatchContext(sourceCtx, worldUuid, reason == null ? "world.setBlock" : reason);

        String oldKey = blockKeyOf(sourceCtx, oldBlock);
        String newKey = blockKeyOf(sourceCtx, newBlock);
        boolean oldAir = isAirLike(sourceCtx, oldBlock);
        boolean newAir = isAirLike(sourceCtx, newBlock);

        if (oldKey != null && !oldAir && !oldKey.equals(newKey)) {
            dispatchBroken(dispatch, oldKey, x, y, z, oldBlock, newBlock, "set");
        }
        if (newKey != null && !newAir) {
            dispatchPlaced(dispatch, newKey, x, y, z, newBlock, oldBlock, oldKey == null || !oldKey.equals(newKey) ? "set" : "refresh");
            dispatchComputeState(dispatch, newKey, x, y, z, newBlock, oldBlock, "set");
        }

        enqueueNeighborUpdates(dispatch, x, y, z, "set");
        processNeighborQueue(dispatch);
    }

    public void handleNativeBlockEvent(String eventName, Object rawEvent) {
        if (rawEvent == null || eventName == null || eventName.isBlank()) return;
        String worldUuid = extractWorldUuid(rawEvent);
        Integer x = extractInt(rawEvent, "getX");
        Integer y = extractInt(rawEvent, "getY");
        Integer z = extractInt(rawEvent, "getZ");
        if (worldUuid == null || x == null || y == null || z == null) return;

        LuaModContext sampleCtx = firstEnabledContext();
        if (sampleCtx == null) return;

        Object eventBlock = invokeNoArgs(rawEvent, "getBlock");
        Object currentBlock = sampleCtx.blockAt(worldUuid, x, y, z);
        DispatchContext dispatch = new DispatchContext(null, worldUuid, "native:" + eventName.toLowerCase(Locale.ROOT));
        if ("block_break".equalsIgnoreCase(eventName)) {
            String oldKey = blockKeyOf(sampleCtx, eventBlock);
            if (oldKey != null && !isAirLike(sampleCtx, eventBlock)) {
                dispatchBroken(dispatch, oldKey, x, y, z, eventBlock, currentBlock, "native");
            }
            enqueueNeighborUpdates(dispatch, x, y, z, "native");
            processNeighborQueue(dispatch);
            return;
        }

        if ("block_place".equalsIgnoreCase(eventName)) {
            String newKey = blockKeyOf(sampleCtx, currentBlock != null ? currentBlock : eventBlock);
            Object placedBlock = currentBlock != null ? currentBlock : eventBlock;
            if (newKey != null && !isAirLike(sampleCtx, placedBlock)) {
                dispatchPlaced(dispatch, newKey, x, y, z, placedBlock, eventBlock, "native");
                dispatchComputeState(dispatch, newKey, x, y, z, placedBlock, eventBlock, "native");
            }
            enqueueNeighborUpdates(dispatch, x, y, z, "native");
            processNeighborQueue(dispatch);
        }
    }

    public void recomputeAt(LuaModContext sourceCtx, String worldUuid, int x, int y, int z, String reason) {
        if (sourceCtx == null || worldUuid == null || worldUuid.isBlank()) return;
        Object block = sourceCtx.blockAt(worldUuid, x, y, z);
        String key = blockKeyOf(sourceCtx, block);
        if (key == null || isAirLike(sourceCtx, block)) return;
        DispatchContext dispatch = new DispatchContext(sourceCtx, worldUuid, reason == null ? "manual" : reason);
        dispatchComputeState(dispatch, key, x, y, z, block, null, "manual");
    }

    public void notifyNeighbors(LuaModContext sourceCtx, String worldUuid, int x, int y, int z, String reason) {
        if (sourceCtx == null || worldUuid == null || worldUuid.isBlank()) return;
        DispatchContext dispatch = new DispatchContext(sourceCtx, worldUuid, reason == null ? "manual" : reason);
        enqueueNeighborUpdates(dispatch, x, y, z, "manual");
        processNeighborQueue(dispatch);
    }

    private void dispatchPlaced(DispatchContext dispatch, String blockKey, int x, int y, int z, Object block, Object previousBlock, String action) {
        forEachRegistration(blockKey, (mod, registration, ctx) -> {
            LuaFunction fn = registration.onPlaced();
            if (fn == null) return;
            LuaTable payload = buildPayload(ctx, dispatch, registration, action, x, y, z, block, previousBlock, null);
            invokeHandler(mod, registration, fn, payload, "blocks:onPlaced:" + blockKey);
        });
    }

    private void dispatchBroken(DispatchContext dispatch, String blockKey, int x, int y, int z, Object block, Object nextBlock, String action) {
        forEachRegistration(blockKey, (mod, registration, ctx) -> {
            LuaFunction fn = registration.onBroken();
            if (fn == null) return;
            LuaTable payload = buildPayload(ctx, dispatch, registration, action, x, y, z, block, nextBlock, null);
            invokeHandler(mod, registration, fn, payload, "blocks:onBroken:" + blockKey);
        });
    }

    private void dispatchComputeState(DispatchContext dispatch, String blockKey, int x, int y, int z, Object block, Object previousBlock, String action) {
        forEachRegistration(blockKey, (mod, registration, ctx) -> {
            LuaFunction fn = registration.computeState();
            if (fn == null) return;
            LuaTable payload = buildPayload(ctx, dispatch, registration, action, x, y, z, block, previousBlock, null);
            Varargs result = invokeHandler(mod, registration, fn, payload, "blocks:computeState:" + blockKey);
            LuaValue first = result == null ? LuaValue.NIL : result.arg1();
            if (first == null || first.isnil()) return;

            Object state = LuaEngine.luaToJava(first);
            if (state == null) return;
            if (!ctx.setBlockState(dispatch.worldUuid(), x, y, z, state) && manager.isDebugLogging()) {
                plugin.getLogger().at(Level.WARNING).log(
                        "Block behavior computeState returned an unusable state for mod=" + mod.manifest().id()
                                + " key=" + registration.key()
                                + " world=" + dispatch.worldUuid()
                                + " x=" + x + " y=" + y + " z=" + z
                );
            }
        });
    }

    private void enqueueNeighborUpdates(DispatchContext dispatch, int x, int y, int z, String action) {
        for (int[] delta : CARDINAL_NEIGHBORS) {
            dispatch.neighborQueue().addLast(new NeighborUpdate(x + delta[0], y + delta[1], z + delta[2], action));
        }
    }

    private void processNeighborQueue(DispatchContext dispatch) {
        int max = plugin.getMaxBlockBehaviorNeighborUpdatesPerCause();
        int processed = 0;
        Set<String> seen = new LinkedHashSet<>();
        while (!dispatch.neighborQueue().isEmpty() && processed < max) {
            NeighborUpdate next = dispatch.neighborQueue().removeFirst();
            String dedupeKey = next.x() + ":" + next.y() + ":" + next.z();
            if (!seen.add(dedupeKey)) continue;
            processed++;

            LuaModContext ctx = dispatch.sourceCtx() != null ? dispatch.sourceCtx() : firstEnabledContext();
            if (ctx == null) break;
            Object block = ctx.blockAt(dispatch.worldUuid(), next.x(), next.y(), next.z());
            String blockKey = blockKeyOf(ctx, block);
            if (blockKey == null || isAirLike(ctx, block)) continue;

            final Object targetBlock = block;
            forEachRegistration(blockKey, (mod, registration, modCtx) -> {
                LuaFunction neighborFn = registration.onNeighborChanged();
                LuaFunction computeFn = registration.computeState();
                LuaTable payload = buildPayload(modCtx, dispatch, registration, next.action(), next.x(), next.y(), next.z(), targetBlock, null,
                        neighborPayload(modCtx, dispatch.worldUuid(), next.x(), next.y(), next.z()));
                if (neighborFn != null) {
                    invokeHandler(mod, registration, neighborFn, payload, "blocks:onNeighborChanged:" + blockKey);
                }
                if (computeFn != null) {
                    Varargs result = invokeHandler(mod, registration, computeFn, payload, "blocks:computeState:" + blockKey);
                    LuaValue first = result == null ? LuaValue.NIL : result.arg1();
                    if (first != null && !first.isnil()) {
                        Object state = LuaEngine.luaToJava(first);
                        if (state != null) {
                            modCtx.setBlockState(dispatch.worldUuid(), next.x(), next.y(), next.z(), state);
                        }
                    }
                }
            });
        }

        if (!dispatch.neighborQueue().isEmpty() && manager.isDebugLogging()) {
            plugin.getLogger().at(Level.WARNING).log(
                    "Block behavior neighbor propagation hit cap=" + max
                            + " world=" + dispatch.worldUuid()
                            + " reason=" + dispatch.reason()
            );
        }
    }

    private LuaTable neighborPayload(LuaModContext ctx, String worldUuid, int x, int y, int z) {
        LuaTable out = new LuaTable();
        out.set("worldUuid", LuaValue.valueOf(worldUuid));
        out.set("x", LuaValue.valueOf(x));
        out.set("y", LuaValue.valueOf(y));
        out.set("z", LuaValue.valueOf(z));
        Object block = ctx.blockAt(worldUuid, x, y, z);
        if (block != null) {
            out.set("block", LuaEngine.javaToLuaValue(block));
            String key = blockKeyOf(ctx, block);
            if (key != null) out.set("key", LuaValue.valueOf(key));
        }
        return out;
    }

    private LuaTable buildPayload(
            LuaModContext ctx,
            DispatchContext dispatch,
            Registration registration,
            String action,
            int x,
            int y,
            int z,
            Object block,
            Object previousBlock,
            LuaTable neighbor
    ) {
        LuaTable payload = new LuaTable();
        payload.set("key", LuaValue.valueOf(registration.key()));
        payload.set("reason", LuaValue.valueOf(dispatch.reason()));
        payload.set("action", LuaValue.valueOf(action == null ? "" : action));
        payload.set("worldUuid", LuaValue.valueOf(dispatch.worldUuid()));
        payload.set("x", LuaValue.valueOf(x));
        payload.set("y", LuaValue.valueOf(y));
        payload.set("z", LuaValue.valueOf(z));
        if (dispatch.sourceCtx() != null) {
            payload.set("sourceModId", LuaValue.valueOf(dispatch.sourceCtx().modId()));
        }
        Object liveBlock = ctx.blockAt(dispatch.worldUuid(), x, y, z);
        if (block != null) payload.set("block", LuaEngine.javaToLuaValue(block));
        if (liveBlock != null) payload.set("liveBlock", LuaEngine.javaToLuaValue(liveBlock));
        if (previousBlock != null) payload.set("previousBlock", LuaEngine.javaToLuaValue(previousBlock));
        Object state = ctx.blockStateAt(dispatch.worldUuid(), x, y, z);
        if (state != null) payload.set("state", LuaEngine.javaToLuaValue(state));
        if (neighbor != null) payload.set("neighbor", neighbor);
        return payload;
    }

    private void forEachRegistration(String blockKey, RegistrationConsumer consumer) {
        if (blockKey == null || blockKey.isBlank() || consumer == null) return;
        for (LuaMod mod : manager.listMods()) {
            if (mod.state() != LuaModState.ENABLED || mod.ctx() == null) continue;
            Map<String, Registration> registrations = registrationsByMod.get(normalizeModId(mod.manifest().id()));
            if (registrations == null || registrations.isEmpty()) continue;
            Registration registration = registrations.get(blockKey);
            if (registration == null) continue;
            consumer.accept(mod, registration, mod.ctx());
        }
    }

    private Varargs invokeHandler(LuaMod mod, Registration registration, LuaFunction fn, LuaTable payload, String metricKey) {
        long start = System.nanoTime();
        boolean failed = false;
        try {
            return fn.invoke(payload);
        } catch (Throwable t) {
            failed = true;
            manager.recordRuntimeError(mod, "Block behavior error (" + registration.key() + ")", t);
            if (manager.isDebugLogging()) {
                plugin.getLogger().at(Level.WARNING).log(
                        "Block behavior failed for mod=" + mod.manifest().id()
                                + " key=" + registration.key()
                                + " metric=" + metricKey
                                + " error=" + t
                );
            }
            return LuaValue.NONE;
        } finally {
            manager.recordRuntimeInvocation(mod, metricKey, start, failed);
        }
    }

    private LuaModContext firstEnabledContext() {
        for (LuaMod mod : manager.listMods()) {
            if (mod.state() == LuaModState.ENABLED && mod.ctx() != null) {
                return mod.ctx();
            }
        }
        return null;
    }

    private static String extractWorldUuid(Object event) {
        Object raw = invokeNoArgs(event, "getWorldUuid");
        if (raw != null) return String.valueOf(raw);
        Object world = invokeNoArgs(event, "getWorld");
        if (world == null) return null;
        Object uuid = invokeNoArgs(world, "getUuid");
        return uuid == null ? null : String.valueOf(uuid);
    }

    private static Integer extractInt(Object target, String methodName) {
        Object value = invokeNoArgs(target, methodName);
        if (value instanceof Number n) return n.intValue();
        return null;
    }

    private static Object invokeNoArgs(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isAirLike(LuaModContext ctx, Object block) {
        if (block == null) return true;
        String key = blockKeyOf(ctx, block);
        return key != null && key.contains("air");
    }

    private static String blockKeyOf(LuaModContext ctx, Object block) {
        if (block == null || ctx == null) return null;
        Object name = ctx.reflectiveGet(block, "name");
        if (name == null) name = ctx.reflectiveCall(block, "getName");
        if (name == null) name = ctx.reflectiveGet(block, "type");
        if (name == null) name = ctx.reflectiveCall(block, "getType");
        String normalizedName = normalizeBlockKey(name);
        if (normalizedName != null) return normalizedName;

        Object id = ctx.reflectiveGet(block, "id");
        if (id == null) id = ctx.reflectiveCall(block, "getId");
        if (id == null) id = ctx.reflectiveGet(block, "blockId");
        if (id == null) id = ctx.reflectiveCall(block, "getBlockId");
        return normalizeBlockKey(id);
    }

    private static String normalizeModId(String modId) {
        if (modId == null) return null;
        String normalized = modId.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeBlockKey(Object blockTypeOrId) {
        if (blockTypeOrId == null) return null;
        if (blockTypeOrId instanceof Number n) {
            return "#" + n.intValue();
        }
        String normalized = String.valueOf(blockTypeOrId).trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static LuaFunction asFunction(LuaValue value) {
        return value != null && value.isfunction() ? (LuaFunction) value : null;
    }

    private record Registration(String key, LuaFunction onPlaced, LuaFunction onBroken, LuaFunction onNeighborChanged, LuaFunction computeState) {}
    private record NeighborUpdate(int x, int y, int z, String action) {}
    private record DispatchContext(LuaModContext sourceCtx, String worldUuid, String reason, ArrayDeque<NeighborUpdate> neighborQueue) {
        private DispatchContext(LuaModContext sourceCtx, String worldUuid, String reason) {
            this(sourceCtx, worldUuid, reason, new ArrayDeque<>());
        }
    }

    @FunctionalInterface
    private interface RegistrationConsumer {
        void accept(LuaMod mod, Registration registration, LuaModContext ctx);
    }
}
