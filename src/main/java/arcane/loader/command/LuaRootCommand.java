package arcane.loader.command;

import arcane.loader.ArcaneLoaderPlugin;
import arcane.loader.lua.LuaMod;
import arcane.loader.lua.LuaModManager;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Stable /lua command root.
 */
public final class LuaRootCommand extends AbstractCommandCollection {

    private static final int EVAL_RESULT_LIMIT = 160;

    private final ArcaneLoaderPlugin plugin;

    public LuaRootCommand(ArcaneLoaderPlugin plugin) {
        super("lua", "Arcane Loader commands");
        this.plugin = plugin;

        addSubCommand(new ModsSub(plugin));
        addSubCommand(new ReloadSub(plugin));
        addSubCommand(new EnableSub(plugin));
        addSubCommand(new DisableSub(plugin));
        addSubCommand(new ErrorsSub(plugin));
        addSubCommand(new ApiSub());
        addSubCommand(new CallSub(plugin));
        addSubCommand(new EvalSub(plugin));
        addSubCommand(new DebugSub(plugin));
        addSubCommand(new ProfileSub(plugin));
        addSubCommand(new TraceSub(plugin));
        addSubCommand(new CapsSub(plugin));
        addSubCommand(new NetstatsSub(plugin));
        addSubCommand(new ConfigSub(plugin));
        addSubCommand(new PolicySub(plugin));
        addSubCommand(new VerifySub(plugin));
        addSubCommand(new DoctorSub(plugin));
    }

    private static String[] extractArgs(CommandContext context) {
        try {
            var m = context.getClass().getMethod("getArgs");
            Object r = m.invoke(context);
            if (r instanceof String[] a && a.length > 0) return a;
        } catch (Throwable ignored) { }

        try {
            var m = context.getClass().getMethod("args");
            Object r = m.invoke(context);
            if (r instanceof String[] a && a.length > 0) return a;
        } catch (Throwable ignored) { }

        try {
            var m = context.getClass().getMethod("getArguments");
            Object r = m.invoke(context);
            if (r instanceof java.util.List<?> list) {
                if (list.isEmpty()) throw new IllegalStateException("empty");
                String[] out = new String[list.size()];
                for (int i = 0; i < list.size(); i++) out[i] = String.valueOf(list.get(i));
                return out;
            }
        } catch (Throwable ignored) { }

        try {
            var m = context.getClass().getMethod("arguments");
            Object r = m.invoke(context);
            if (r instanceof java.util.List<?> list) {
                if (list.isEmpty()) throw new IllegalStateException("empty");
                String[] out = new String[list.size()];
                for (int i = 0; i < list.size(); i++) out[i] = String.valueOf(list.get(i));
                return out;
            }
        } catch (Throwable ignored) { }

        // Fallback parser for command frameworks that don't expose parsed args on subcommands.
        try {
            String input = null;
            try {
                var getInput = context.getClass().getMethod("getInputString");
                Object r = getInput.invoke(context);
                if (r != null) input = String.valueOf(r);
            } catch (Throwable ignored) { }
            if (input == null || input.isBlank()) return new String[0];

            String calledName = null;
            try {
                var getCalled = context.getClass().getMethod("getCalledCommand");
                Object cmd = getCalled.invoke(context);
                if (cmd != null) {
                    var getName = cmd.getClass().getMethod("getName");
                    Object n = getName.invoke(cmd);
                    if (n != null) calledName = String.valueOf(n).trim().toLowerCase(java.util.Locale.ROOT);
                }
            } catch (Throwable ignored) { }

            String[] raw = java.util.Arrays.stream(input.trim().split("\\s+"))
                    .filter(s -> s != null && !s.isBlank())
                    .toArray(String[]::new);
            if (raw.length == 0) return new String[0];

            int start = 0;
            if (raw.length >= 2 && raw[0].equalsIgnoreCase("lua")) {
                start = 1;
                if (calledName != null && raw.length >= 3 && raw[1].equalsIgnoreCase(calledName)) {
                    start = 2;
                }
            } else if (calledName != null && raw[0].equalsIgnoreCase(calledName)) {
                start = 1;
            }
            if (start >= raw.length) return new String[0];
            return java.util.Arrays.copyOfRange(raw, start, raw.length);
        } catch (Throwable ignored) { }

        return new String[0];
    }

    private static String firstArgOrNull(CommandContext context) {
        String[] args = extractArgs(context);
        if (args.length == 0) return null;
        String a = args[0];
        if (a == null) return null;
        a = a.trim();
        return a.isEmpty() ? null : a;
    }

    private static String truncate(String s, int limit) {
        if (s == null) return "";
        if (s.length() <= limit) return s;
        return s.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private static String renderLuaValue(LuaValue value) {
        if (value == null || value.isnil()) return "nil";
        String text;
        try {
            text = value.tojstring();
        } catch (Throwable ignored) {
            text = value.typename();
        }
        text = truncate(text, EVAL_RESULT_LIMIT);
        if (value.isstring() || value.isnumber() || value.isboolean()) {
            return text;
        }
        return value.typename() + ": " + text;
    }

    private static final class EvalResult {
        private final boolean ok;
        private final String message;

        private EvalResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private static EvalResult evalInDevSandbox(String code) {
        try {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("os", LuaValue.NIL);
            globals.set("io", LuaValue.NIL);
            globals.set("package", LuaValue.NIL);
            globals.set("dofile", LuaValue.NIL);
            globals.set("loadfile", LuaValue.NIL);

            LuaValue chunk;
            try {
                chunk = globals.load("return " + code, "lua-eval");
            } catch (Throwable ignored) {
                chunk = globals.load(code, "lua-eval");
            }

            Varargs out = chunk.invoke();
            return new EvalResult(true, renderLuaValue(out.arg1()));
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null || msg.isBlank()) msg = t.toString();
            return new EvalResult(false, truncate(msg, EVAL_RESULT_LIMIT));
        }
    }

    private static final class ModsSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private ModsSub(ArcaneLoaderPlugin plugin) {
            super("mods", "List Lua mods and loader paths");
            this.plugin = plugin;
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            context.sender().sendMessage(Message.raw("Arcane Loader paths:"));
            context.sender().sendMessage(Message.raw("- root: " + plugin.getServerRoot()));
            context.sender().sendMessage(Message.raw("- lua_mods: " + plugin.getLuaModsDir()));
            context.sender().sendMessage(Message.raw("- lua_cache: " + plugin.getLuaCacheDir()));
            context.sender().sendMessage(Message.raw("- logs: " + plugin.getLogDir()));
            context.sender().sendMessage(Message.raw("- lua_assets: " + plugin.getLuaAssetsDir()));
            context.sender().sendMessage(Message.raw("- config: " + plugin.getConfigPath()));
            context.sender().sendMessage(Message.raw("- devMode: " + plugin.isDevMode()));
            context.sender().sendMessage(Message.raw("- autoReload: " + plugin.isAutoReload()));
            context.sender().sendMessage(Message.raw("- autoEnable: " + plugin.isAutoEnable()));
            context.sender().sendMessage(Message.raw("- autoStageAssets: " + plugin.isAutoStageAssets()));
            context.sender().sendMessage(Message.raw("- slowCallWarnMs: " + plugin.getSlowCallWarnMs()));
            context.sender().sendMessage(Message.raw("- blockEditBudgetPerTick: " + plugin.getBlockEditBudgetPerTick()));
            context.sender().sendMessage(Message.raw("- maxQueuedBlockEditsPerMod: " + plugin.getMaxQueuedBlockEditsPerMod()));
            context.sender().sendMessage(Message.raw("- maxTxBlockEditsPerMod: " + plugin.getMaxTxBlockEditsPerMod()));
            context.sender().sendMessage(Message.raw("- maxBatchSetOpsPerCall: " + plugin.getMaxBatchSetOpsPerCall()));
            context.sender().sendMessage(Message.raw("- restrictSensitiveApis: " + plugin.isRestrictSensitiveApis()));

            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("(Mod manager not ready.)"));
                return CompletableFuture.completedFuture(null);
            }

            mm.scanMods();
            context.sender().sendMessage(Message.raw("Lua mods:"));
            boolean any = false;
            for (LuaMod mod : mm.listMods()) {
                any = true;
                context.sender().sendMessage(Message.raw(" - " + mod.manifest().id() + " [" + mod.state() + "] v" + mod.manifest().version()));
            }
            if (!any) {
                context.sender().sendMessage(Message.raw(" (none found; create server/lua_mods/<modId>/manifest.json)"));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ReloadSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private ReloadSub(ArcaneLoaderPlugin plugin) {
            super("reload", "Reload Lua mods: /lua reload [modId]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();
            String target = firstArgOrNull(context);
            if (target == null) {
                mm.reloadAll();
                context.sender().sendMessage(Message.raw("Reloaded all Lua mods."));
                return CompletableFuture.completedFuture(null);
            }
            LuaMod mod = mm.findById(target);
            if (mod == null) {
                context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + target));
                return CompletableFuture.completedFuture(null);
            }
            mm.reloadMod(mod);
            context.sender().sendMessage(Message.raw("Reloaded Lua mod: " + mod.manifest().id()));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class EnableSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private EnableSub(ArcaneLoaderPlugin plugin) {
            super("enable", "Enable Lua mods: /lua enable [modId]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();
            String target = firstArgOrNull(context);
            if (target == null) {
                mm.enableAll();
                context.sender().sendMessage(Message.raw("Enabled all Lua mods."));
                return CompletableFuture.completedFuture(null);
            }
            LuaMod mod = mm.findById(target);
            if (mod == null) {
                context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + target));
                return CompletableFuture.completedFuture(null);
            }
            mm.enableMod(mod);
            context.sender().sendMessage(Message.raw("Enabled Lua mod: " + mod.manifest().id()));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class DisableSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private DisableSub(ArcaneLoaderPlugin plugin) {
            super("disable", "Disable Lua mods: /lua disable [modId]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();
            String target = firstArgOrNull(context);
            if (target == null) {
                mm.disableAll();
                context.sender().sendMessage(Message.raw("Disabled all Lua mods."));
                return CompletableFuture.completedFuture(null);
            }
            LuaMod mod = mm.findById(target);
            if (mod == null) {
                context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + target));
                return CompletableFuture.completedFuture(null);
            }
            mm.disableMod(mod);
            context.sender().sendMessage(Message.raw("Disabled Lua mod: " + mod.manifest().id()));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ErrorsSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private ErrorsSub(ArcaneLoaderPlugin plugin) {
            super("errors", "Show errors: /lua errors [modId]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();
            String target = firstArgOrNull(context);
            if (target != null) {
                LuaMod mod = mm.findById(target);
                if (mod == null) {
                    context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + target));
                    return CompletableFuture.completedFuture(null);
                }
                if (mod.lastError() == null) {
                    context.sender().sendMessage(Message.raw("No recorded errors for " + mod.manifest().id() + "."));
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw("Lua mod error for " + mod.manifest().id() + " [" + mod.state() + "]:"));
                context.sender().sendMessage(Message.raw(" - " + mod.lastError()));
                if (!mod.errorHistory().isEmpty()) {
                    context.sender().sendMessage(Message.raw("Recent errors:"));
                    int limit = Math.min(5, mod.errorHistory().size());
                    for (int i = 0; i < limit; i++) {
                        context.sender().sendMessage(Message.raw(" - " + truncate(mod.errorHistory().get(i), 140)));
                    }
                }
                context.sender().sendMessage(Message.raw("(Full stack trace is in server logs for now.)"));
                return CompletableFuture.completedFuture(null);
            }

            var errs = mm.modsWithErrors();
            if (errs.isEmpty()) {
                context.sender().sendMessage(Message.raw("No recorded Lua mod errors."));
                return CompletableFuture.completedFuture(null);
            }
            context.sender().sendMessage(Message.raw("Lua mod errors:"));
            for (LuaMod mod : errs) {
                context.sender().sendMessage(Message.raw(" - " + mod.manifest().id() + " [" + mod.state() + "]: " + truncate(mod.lastError(), 120)));
            }
            context.sender().sendMessage(Message.raw("(Full stack traces are in server logs for now.)"));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ApiSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private ApiSub() {
            super("api", "Show Lua API summary");
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            context.sender().sendMessage(Message.raw("Lua API (current):"));
            context.sender().sendMessage(Message.raw(" - log.info(msg), log.warn(msg), log.error(msg)"));
            context.sender().sendMessage(Message.raw(" - commands.register(name, fn|help, fn), commands.unregister(name), commands.list(), commands.help(name)"));
            context.sender().sendMessage(Message.raw(" - events.on(event, fn), events.once(event, fn), events.emit(event, payload...), events.off(event[, fn]), events.clear(event), events.list(), events.count([event])"));
            context.sender().sendMessage(Message.raw("   server events: server_start, server_stop, pre_tick(payload), tick(payload), post_tick(payload), player_connect(payload), player_disconnect(payload), player_chat(payload), player_ready(payload), player_world_join(payload), player_world_leave(payload)"));
            context.sender().sendMessage(Message.raw("   gameplay events: block_break(payload), block_place(payload), block_use(payload), block_damage(payload), item_drop(payload), item_pickup(payload), player_interact(payload), player_craft(payload), entity_remove(payload), entity_inventory_change(payload), world_add(payload), world_remove(payload), world_start(payload), worlds_loaded(payload), chunk_save(payload), chunk_unload(payload)"));
            context.sender().sendMessage(Message.raw("   payload controls (when supported): payload.cancel(), payload.setCancelled(bool), payload.isCancelled()"));
            context.sender().sendMessage(Message.raw("   chat controls (when supported): payload.setContent(text), payload.getContent()"));
            context.sender().sendMessage(Message.raw(" - vec3.new(x,y,z), vec3.is(v), vec3.unpack(v); blockpos.new(x,y,z), blockpos.is(v), blockpos.unpack(v)"));
            context.sender().sendMessage(Message.raw(" - interop.server(), interop.universe(), interop.defaultWorld(), interop.get(target,key), interop.set(target,key,val), interop.call(target,method,...), interop.methods(target[,prefix]), interop.typeOf(target)"));
            context.sender().sendMessage(Message.raw(" - components.health/setHealth, components.maxHealth/setMaxHealth, components.alive, components.damage/heal, components.stamina/setStamina, components.hunger/setHunger, components.mana/setMana, components.tags/hasTag/addTag/removeTag, components.get/set/call/methods"));
            context.sender().sendMessage(Message.raw(" - fs.read(path), fs.write(path, data), fs.append(path, data), fs.mkdir(path), fs.readJson(path[, defaults[, writeBack]]), fs.writeJson(path, value[, pretty]), fs.delete(path[, recursive]), fs.move(from, to[, replace]), fs.copy(from, to[, replace]), fs.exists(path), fs.list([dir])"));
            context.sender().sendMessage(Message.raw(" - players.send(...), players.name(...), players.uuid(...), players.worldUuid(...), players.position(...), players.teleport(player, [worldUuid], x,y,z|vec3), players.kick(...), players.refer(...), players.isValid(...), players.hasPermission(...), players.broadcast(...), players.broadcastTo(...), players.list(), players.count(), players.findByName(...), players.findByUuid(...)"));
            context.sender().sendMessage(Message.raw(" - entities.list([worldUuid]), entities.count([worldUuid]), entities.find(id), entities.id(entity), entities.type(entity), entities.worldUuid(entity), entities.isValid(entity), entities.remove(entity), entities.position(entity), entities.teleport(entity, [worldUuid], x,y,z|vec3), entities.rotation(entity), entities.setRotation(entity,yaw,pitch[,roll]|table), entities.velocity(entity), entities.setVelocity(entity,x,y,z|vec3), entities.near([worldUuid],x,y,z,radius), entities.spawn(typeOrPrototype,[worldUuid],x,y,z|vec3[,yaw,pitch,roll]), entities.inventory/equipment, entities.setEquipment(entity,slot,item), entities.giveItem/takeItem, entities.effects/addEffect/removeEffect, entities.attribute/setAttribute, entities.pathTo/stopPath, entities.canControl()"));
            context.sender().sendMessage(Message.raw(" - world.list(), world.find(uuid), world.findByName(name), world.default(), world.ofPlayer(player), world.players([uuid]), world.playerCount([uuid]), world.entities([uuid]), world.entityCount([uuid]), world.time([uuid]), world.setTime(uuid, time), world.isPaused([uuid]), world.setPaused(uuid, bool), world.blockAt([worldUuid],x,y,z), world.blockNameAt([worldUuid],x,y,z), world.blockIdAt([worldUuid],x,y,z), world.blockType(nameOrId), world.blockStateAt([worldUuid],x,y,z), world.setBlock([worldUuid],x,y,z,block), world.setBlockState([worldUuid],x,y,z,state), world.queueSetBlock([worldUuid],x,y,z,block), world.batchGet([worldUuid],positions[,includeAir]), world.batchSet([worldUuid],edits[,direct|queue|tx]), world.applyQueuedBlocks([limit]), world.clearQueuedBlocks(), world.queuedBlocks(), world.txBegin(), world.txSetBlock(...), world.txCommit([limit]), world.txRollback(), world.txStatus(), world.neighbors([worldUuid],x,y,z), world.scanBox([worldUuid],minX,minY,minZ,maxX,maxY,maxZ[,limit]), world.raycastBlock([worldUuid],ox,oy,oz,dx,dy,dz[,maxDist,step]), world.broadcast(uuid, msg), world.canControl()"));
            context.sender().sendMessage(Message.raw(" - network.send(player, channel, payload), network.sendAll(channel, payload, [worldUuid]), network.refer(player, host, port), network.referAll(host, port, [worldUuid]), network.on(channel, fn), network.off(channel[, fn]), network.list(), network.emit(channel, payload), network.allowed(channel), network.policy(channel), network.canControl()"));
            context.sender().sendMessage(Message.raw(" - webhook.request(method, url, [body], [contentType], [timeoutMs], [headers]), webhook.postJson(url, payload, [timeoutMs], [headers]), webhook.canControl()"));
            context.sender().sendMessage(Message.raw(" - ui.actionbar(player, msg), ui.actionbarAll(msg, [worldUuid]), ui.title(player, title, subtitle, [fadeIn], [stay], [fadeOut]), ui.titleAll(title, subtitle, [fadeIn], [stay], [fadeOut], [worldUuid]), ui.form(player, id, payload), ui.formAll(id, payload, [worldUuid]), ui.panel(player, id, payload), ui.panelAll(id, payload, [worldUuid]), ui.canControl()"));
            context.sender().sendMessage(Message.raw(" - arcane.apiVersion, arcane.loaderVersion, arcane.modId, arcane.capabilities, arcane.hasCapability(name)"));
            context.sender().sendMessage(Message.raw(" - capability checks: players.canMove(), entities.canControl(), world.canControl(), network.canControl(), ui.canControl()"));
            context.sender().sendMessage(Message.raw(" - ctx:log(message)"));
            context.sender().sendMessage(Message.raw(" - ctx:command(name, help, fn(sender, args))"));
            context.sender().sendMessage(Message.raw(" - ctx:on(event, fn(...)), ctx:off(event[, fn])"));
            context.sender().sendMessage(Message.raw(" - ctx:setTimeout(ms, fn), ctx:setInterval(ms, fn), ctx:cancelTimer(handle), ctx:timerActive(handle)"));
            context.sender().sendMessage(Message.raw(" - ctx:dataDir(), ctx:readText(path), ctx:writeText(path, text), ctx:appendText(path, text)"));
            context.sender().sendMessage(Message.raw("Command bridge: /lua call <modId> <command> [args...]"));
            context.sender().sendMessage(Message.raw("Dev command: /lua eval <code> (sandboxed, devMode only)."));
            context.sender().sendMessage(Message.raw("Debug commands: /lua debug [on|off|toggle], /lua profile [reset [modId]|dump], /lua trace <modId> <event:name|network:name|command:name|list|clear|off key>"));
            context.sender().sendMessage(Message.raw("Security command: /lua caps [modId]"));
            context.sender().sendMessage(Message.raw("Network command: /lua netstats [channel|reset]"));
            context.sender().sendMessage(Message.raw("Config command: /lua config [reload]"));
            context.sender().sendMessage(Message.raw("Policy command: /lua policy <modId> <channel> | /lua policy allow|deny|clear|cap|list ..."));
            context.sender().sendMessage(Message.raw("Verification command: /lua verify [benchPrefix]"));
            context.sender().sendMessage(Message.raw("Health command: /lua doctor"));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class CallSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private CallSub(ArcaneLoaderPlugin plugin) {
            super("call", "Call a Lua-registered command: /lua call <modId> <command> [args...]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            String[] args = extractArgs(context);
            if (args.length < 2) {
                context.sender().sendMessage(Message.raw("Usage: /lua call <modId> <command> [args...]"));
                return CompletableFuture.completedFuture(null);
            }

            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }

            String modId = args[0];
            String command = args[1];
            List<String> rest = new ArrayList<>();
            for (int i = 2; i < args.length; i++) {
                rest.add(args[i]);
            }

            boolean ok = mm.invokeModCommand(modId, command, context.sender(), rest);
            if (!ok) {
                context.sender().sendMessage(Message.raw("Call failed: unknown mod/command, mod disabled, or command errored."));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class EvalSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private EvalSub(ArcaneLoaderPlugin plugin) {
            super("eval", "Evaluate Lua code (dev only): /lua eval <code>");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            if (!plugin.isDevMode()) {
                context.sender().sendMessage(Message.raw("Eval is disabled when devMode=false."));
                return CompletableFuture.completedFuture(null);
            }

            String[] args = extractArgs(context);
            if (args.length < 1) {
                context.sender().sendMessage(Message.raw("Usage: /lua eval <code>"));
                return CompletableFuture.completedFuture(null);
            }

            String code = String.join(" ", args).trim();
            if (code.isEmpty()) {
                context.sender().sendMessage(Message.raw("Usage: /lua eval <code>"));
                return CompletableFuture.completedFuture(null);
            }

            EvalResult result = evalInDevSandbox(code);
            if (result.ok) {
                context.sender().sendMessage(Message.raw("Eval result: " + result.message));
            } else {
                context.sender().sendMessage(Message.raw("Eval error: " + result.message));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class DebugSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private DebugSub(ArcaneLoaderPlugin plugin) {
            super("debug", "Toggle Lua debug logging");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            String mode = firstArgOrNull(context);
            boolean next;
            if (mode == null || mode.equalsIgnoreCase("toggle")) {
                next = !mm.isDebugLogging();
            } else if (mode.equalsIgnoreCase("on") || mode.equalsIgnoreCase("true")) {
                next = true;
            } else if (mode.equalsIgnoreCase("off") || mode.equalsIgnoreCase("false")) {
                next = false;
            } else {
                context.sender().sendMessage(Message.raw("Usage: /lua debug [on|off|toggle]"));
                return CompletableFuture.completedFuture(null);
            }
            mm.setDebugLogging(next);
            context.sender().sendMessage(Message.raw("Lua debug logging: " + (next ? "ON" : "OFF")));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ProfileSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private ProfileSub(ArcaneLoaderPlugin plugin) {
            super("profile", "Show Lua mod profile snapshot");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();

            String[] args = extractArgs(context);
            if (args.length >= 1 && args[0] != null && args[0].equalsIgnoreCase("reset")) {
                if (args.length >= 2 && args[1] != null && !args[1].isBlank()) {
                    boolean ok = mm.resetProfileMetrics(args[1]);
                    if (!ok) {
                        context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + args[1]));
                        return CompletableFuture.completedFuture(null);
                    }
                    context.sender().sendMessage(Message.raw("Reset Lua profile metrics for mod: " + args[1]));
                } else {
                    mm.resetProfileMetrics();
                    context.sender().sendMessage(Message.raw("Reset Lua profile metrics for all mods."));
                }
                return CompletableFuture.completedFuture(null);
            }
            if (args.length >= 1 && args[0] != null && args[0].equalsIgnoreCase("dump")) {
                try {
                    java.nio.file.Path out = mm.dumpProfileSnapshot(plugin.getLogDir());
                    context.sender().sendMessage(Message.raw("Wrote Lua profile snapshot: " + out));
                } catch (Exception e) {
                    context.sender().sendMessage(Message.raw("Failed writing profile snapshot: " + e));
                }
                return CompletableFuture.completedFuture(null);
            }

            context.sender().sendMessage(Message.raw("Lua profile snapshot:"));
            context.sender().sendMessage(Message.raw(" - debugLogging=" + mm.isDebugLogging()));
            context.sender().sendMessage(Message.raw(" - slowCallWarnMs=" + plugin.getSlowCallWarnMs()));
            boolean any = false;
            int slowMods = 0;
            for (LuaMod mod : mm.listMods()) {
                any = true;
                int commandCount = (mod.ctx() == null) ? 0 : mod.ctx().commandNames().size();
                int errorCount = mod.errorHistory().size();
                long invocations = mod.invocationCount();
                long failures = mod.invocationFailures();
                double avgMs = invocations == 0 ? 0.0 : (mod.totalInvocationNanos() / 1_000_000.0) / invocations;
                double maxMs = mod.maxInvocationNanos() / 1_000_000.0;
                if (mod.slowInvocationCount() > 0) slowMods++;
                context.sender().sendMessage(Message.raw(
                        " - " + mod.manifest().id()
                                + " state=" + mod.state()
                                + " commands=" + commandCount
                                + " errors=" + errorCount
                                + " invocations=" + invocations
                                + " failures=" + failures
                                + " slowCalls=" + mod.slowInvocationCount()
                                + " avgMs=" + String.format(java.util.Locale.ROOT, "%.2f", avgMs)
                                + " maxMs=" + String.format(java.util.Locale.ROOT, "%.2f", maxMs)
                                + " lastLoadMs=" + mod.lastLoadEpochMs()
                ));
                var breakdown = new java.util.ArrayList<>(mod.invocationBreakdown().entrySet());
                breakdown.sort((a, b) -> Long.compare(b.getValue().totalNanos(), a.getValue().totalNanos()));
                int limit = Math.min(3, breakdown.size());
                for (int i = 0; i < limit; i++) {
                    var ent = breakdown.get(i);
                    var stats = ent.getValue();
                    double bucketAvgMs = stats.count() == 0 ? 0.0 : (stats.totalNanos() / 1_000_000.0) / stats.count();
                    double bucketMaxMs = stats.maxNanos() / 1_000_000.0;
                    context.sender().sendMessage(Message.raw(
                            "   > " + ent.getKey()
                                    + " count=" + stats.count()
                                    + " fail=" + stats.failures()
                                    + " slow=" + stats.slowCount()
                                    + " avgMs=" + String.format(java.util.Locale.ROOT, "%.2f", bucketAvgMs)
                                    + " maxMs=" + String.format(java.util.Locale.ROOT, "%.2f", bucketMaxMs)
                    ));
                }
            }
            if (!any) {
                context.sender().sendMessage(Message.raw(" - no mods discovered"));
            } else {
                context.sender().sendMessage(Message.raw(" - slowMods=" + slowMods + "/" + mm.listMods().size()));
                context.sender().sendMessage(Message.raw("Use: /lua profile reset [modId] | /lua profile dump"));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class TraceSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private TraceSub(ArcaneLoaderPlugin plugin) {
            super("trace", "Per-mod trace filters: /lua trace <modId> <event:name|network:name|command:name|list|clear|off key>");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            String[] args = extractArgs(context);
            if (args.length < 2) {
                context.sender().sendMessage(Message.raw("Usage: /lua trace <modId> <event:name|network:name|command:name|list|clear|off key>"));
                return CompletableFuture.completedFuture(null);
            }

            String modId = args[0];
            String action = args[1] == null ? "" : args[1].trim().toLowerCase(java.util.Locale.ROOT);
            if (action.isEmpty()) {
                context.sender().sendMessage(Message.raw("Usage: /lua trace <modId> <event:name|network:name|command:name|list|clear|off key>"));
                return CompletableFuture.completedFuture(null);
            }

            if (action.equals("list")) {
                Set<String> keys = mm.traceKeys(modId);
                if (keys.isEmpty()) {
                    context.sender().sendMessage(Message.raw("No trace filters for mod: " + modId));
                } else {
                    context.sender().sendMessage(Message.raw("Trace filters for " + modId + ": " + String.join(", ", keys)));
                }
                return CompletableFuture.completedFuture(null);
            }

            if (action.equals("clear")) {
                if (!mm.clearTraceKeys(modId)) {
                    context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + modId));
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw("Cleared trace filters for mod: " + modId));
                return CompletableFuture.completedFuture(null);
            }

            if (action.equals("off")) {
                if (args.length < 3) {
                    context.sender().sendMessage(Message.raw("Usage: /lua trace <modId> off <event:name|network:name|command:name>"));
                    return CompletableFuture.completedFuture(null);
                }
                String key = normalizeTraceKeyArg(args[2]);
                if (key == null) {
                    context.sender().sendMessage(Message.raw("Invalid trace key. Use event:name, network:name, or command:name."));
                    return CompletableFuture.completedFuture(null);
                }
                if (!mm.removeTraceKey(modId, key)) {
                    LuaMod mod = mm.findById(modId);
                    if (mod == null) {
                        context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + modId));
                    } else {
                        context.sender().sendMessage(Message.raw("Trace filter was not set: " + key));
                    }
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw("Removed trace filter for " + modId + ": " + key));
                return CompletableFuture.completedFuture(null);
            }

            String key = normalizeTraceKeyArg(action);
            if (key == null) {
                context.sender().sendMessage(Message.raw("Invalid trace key. Use event:name, network:name, or command:name."));
                return CompletableFuture.completedFuture(null);
            }
            if (!mm.addTraceKey(modId, key)) {
                LuaMod mod = mm.findById(modId);
                if (mod == null) {
                    context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + modId));
                } else {
                    context.sender().sendMessage(Message.raw("Trace filter already set or invalid: " + key));
                }
                return CompletableFuture.completedFuture(null);
            }
            context.sender().sendMessage(Message.raw("Added trace filter for " + modId + ": " + key));
            return CompletableFuture.completedFuture(null);
        }

        private static String normalizeTraceKeyArg(String raw) {
            if (raw == null) return null;
            String key = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (key.isEmpty()) return null;
            int colon = key.indexOf(':');
            if (colon <= 0 || colon == key.length() - 1) return null;
            String type = key.substring(0, colon);
            String name = key.substring(colon + 1).trim();
            if (name.isEmpty()) return null;
            if (!type.equals("event") && !type.equals("network") && !type.equals("command")) return null;
            return type + ":" + name;
        }
    }

    private static final class CapsSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private CapsSub(ArcaneLoaderPlugin plugin) {
            super("caps", "Show effective sensitive capabilities: /lua caps [modId]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();

            String target = firstArgOrNull(context);
            if (target != null) {
                LuaMod mod = mm.findById(target);
                if (mod == null) {
                    context.sender().sendMessage(Message.raw("Unknown Lua mod id: " + target));
                    return CompletableFuture.completedFuture(null);
                }
                printCaps(context, mod.manifest().id(), plugin);
                return CompletableFuture.completedFuture(null);
            }

            context.sender().sendMessage(Message.raw("Sensitive capabilities (restrictSensitiveApis=" + plugin.isRestrictSensitiveApis() + "):"));
            boolean any = false;
            for (LuaMod mod : mm.listMods()) {
                any = true;
                printCaps(context, mod.manifest().id(), plugin);
            }
            if (!any) {
                context.sender().sendMessage(Message.raw(" - no mods discovered"));
            }
            return CompletableFuture.completedFuture(null);
        }

        private static void printCaps(CommandContext context, String modId, ArcaneLoaderPlugin plugin) {
            java.util.Set<String> channelPrefixes = plugin.networkPrefixesForMod(modId);
            context.sender().sendMessage(Message.raw(
                    " - " + modId
                            + " player-movement=" + plugin.canUseCapability(modId, "player-movement")
                            + " entity-control=" + plugin.canUseCapability(modId, "entity-control")
                            + " world-control=" + plugin.canUseCapability(modId, "world-control")
                            + " network-control=" + plugin.canUseCapability(modId, "network-control")
                            + " ui-control=" + plugin.canUseCapability(modId, "ui-control")
                            + " network-prefixes=" + (channelPrefixes.isEmpty() ? "*" : String.join(",", channelPrefixes))
            ));
        }
    }

    private static final class NetstatsSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private NetstatsSub(ArcaneLoaderPlugin plugin) {
            super("netstats", "Show Lua network channel stats: /lua netstats [channel|reset]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();

            String arg = firstArgOrNull(context);
            if (arg != null && arg.equalsIgnoreCase("reset")) {
                mm.resetProfileMetrics();
                context.sender().sendMessage(Message.raw("Reset Lua network stats (via profile metrics reset)."));
                return CompletableFuture.completedFuture(null);
            }

            java.util.Map<String, Agg> channels = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (LuaMod mod : mm.listMods()) {
                for (var ent : mod.invocationBreakdown().entrySet()) {
                    String key = ent.getKey();
                    if (key == null || !key.startsWith("network:")) continue;
                    String channel = key.substring("network:".length());
                    if (channel.isBlank()) channel = "(unknown)";
                    LuaMod.InvocationStats stats = ent.getValue();
                    Agg agg = channels.computeIfAbsent(channel, k -> new Agg());
                    agg.count += stats.count();
                    agg.failures += stats.failures();
                    agg.slow += stats.slowCount();
                    agg.totalNanos += stats.totalNanos();
                    if (stats.maxNanos() > agg.maxNanos) agg.maxNanos = stats.maxNanos();
                    agg.mods.add(mod.manifest().id());
                }
            }

            if (channels.isEmpty()) {
                context.sender().sendMessage(Message.raw("No network channel activity recorded yet."));
                return CompletableFuture.completedFuture(null);
            }

            if (arg != null) {
                Agg agg = channels.get(arg);
                if (agg == null) {
                    context.sender().sendMessage(Message.raw("No stats for network channel: " + arg));
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw(renderChannel(arg, agg)));
                return CompletableFuture.completedFuture(null);
            }

            context.sender().sendMessage(Message.raw("Lua network channel stats:"));
            int shown = 0;
            for (var ent : channels.entrySet()) {
                context.sender().sendMessage(Message.raw(renderChannel(ent.getKey(), ent.getValue())));
                shown++;
                if (shown >= 10) break;
            }
            if (channels.size() > shown) {
                context.sender().sendMessage(Message.raw("... " + (channels.size() - shown) + " more channel(s). Use /lua netstats <channel>."));
            }
            context.sender().sendMessage(Message.raw("Use /lua netstats reset to clear counters."));
            return CompletableFuture.completedFuture(null);
        }

        private static String renderChannel(String channel, Agg agg) {
            double avgMs = agg.count == 0 ? 0.0 : (agg.totalNanos / 1_000_000.0) / agg.count;
            double maxMs = agg.maxNanos / 1_000_000.0;
            return " - " + channel
                    + " count=" + agg.count
                    + " fail=" + agg.failures
                    + " slow=" + agg.slow
                    + " avgMs=" + String.format(java.util.Locale.ROOT, "%.2f", avgMs)
                    + " maxMs=" + String.format(java.util.Locale.ROOT, "%.2f", maxMs)
                    + " mods=" + agg.mods.size();
        }

        private static final class Agg {
            private long count;
            private long failures;
            private long slow;
            private long totalNanos;
            private long maxNanos;
            private final java.util.Set<String> mods = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        }
    }

    private static final class ConfigSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private ConfigSub(ArcaneLoaderPlugin plugin) {
            super("config", "Show/reload Arcane Loader config: /lua config [reload]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            String arg = firstArgOrNull(context);
            if (arg != null && arg.equalsIgnoreCase("reload")) {
                boolean ok = plugin.reloadArcaneConfig();
                context.sender().sendMessage(Message.raw(ok
                        ? "Reloaded arcane-loader.json."
                        : "Failed reloading arcane-loader.json. Check server logs."));
                return CompletableFuture.completedFuture(null);
            }

            context.sender().sendMessage(Message.raw("Arcane Loader config:"));
            context.sender().sendMessage(Message.raw(" - file: " + plugin.getConfigPath()));
            context.sender().sendMessage(Message.raw(" - devMode=" + plugin.isDevMode()));
            context.sender().sendMessage(Message.raw(" - autoReload=" + plugin.isAutoReload()));
            context.sender().sendMessage(Message.raw(" - autoEnable=" + plugin.isAutoEnable()));
            context.sender().sendMessage(Message.raw(" - autoStageAssets=" + plugin.isAutoStageAssets()));
            context.sender().sendMessage(Message.raw(" - slowCallWarnMs=" + plugin.getSlowCallWarnMs()));
            context.sender().sendMessage(Message.raw(" - blockEditBudgetPerTick=" + plugin.getBlockEditBudgetPerTick()));
            context.sender().sendMessage(Message.raw(" - maxQueuedBlockEditsPerMod=" + plugin.getMaxQueuedBlockEditsPerMod()));
            context.sender().sendMessage(Message.raw(" - maxTxBlockEditsPerMod=" + plugin.getMaxTxBlockEditsPerMod()));
            context.sender().sendMessage(Message.raw(" - maxBatchSetOpsPerCall=" + plugin.getMaxBatchSetOpsPerCall()));
            context.sender().sendMessage(Message.raw(" - allowlistEnabled=" + plugin.isAllowlistEnabled() + " allowlistCount=" + plugin.getAllowlist().size()));
            context.sender().sendMessage(Message.raw(" - restrictSensitiveApis=" + plugin.isRestrictSensitiveApis()));
            context.sender().sendMessage(Message.raw(" - caps: playerMovementMods=" + plugin.getPlayerMovementMods().size()
                    + " entityControlMods=" + plugin.getEntityControlMods().size()
                    + " worldControlMods=" + plugin.getWorldControlMods().size()
                    + " networkControlMods=" + plugin.getNetworkControlMods().size()
                    + " uiControlMods=" + plugin.getUiControlMods().size()));
            context.sender().sendMessage(Message.raw(" - networkChannelPolicies=" + plugin.getNetworkChannelPolicies().size()));
            context.sender().sendMessage(Message.raw("Use /lua config reload after editing arcane-loader.json."));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class PolicySub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private PolicySub(ArcaneLoaderPlugin plugin) {
            super("policy", "Inspect/mutate policy: /lua policy <modId> <channel> | allow/deny/clear/cap/list");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();

            String[] args = extractArgs(context);
            if (args.length < 1) {
                context.sender().sendMessage(Message.raw("Usage: /lua policy <modId> <channel>"));
                context.sender().sendMessage(Message.raw("Mutate: /lua policy allow|deny <modId|*> <prefix>"));
                context.sender().sendMessage(Message.raw("Mutate: /lua policy clear <modId|*>"));
                context.sender().sendMessage(Message.raw("Mutate: /lua policy cap <modId> <player-movement|entity-control|world-control|network-control|ui-control> <on|off>"));
                context.sender().sendMessage(Message.raw("Inspect: /lua policy list [modId]"));
                return CompletableFuture.completedFuture(null);
            }

            String op = args[0].trim().toLowerCase(java.util.Locale.ROOT);
            if (op.equals("allow") || op.equals("deny")) {
                if (args.length < 3) {
                    context.sender().sendMessage(Message.raw("Usage: /lua policy " + op + " <modId|*> <prefix>"));
                    return CompletableFuture.completedFuture(null);
                }
                String modId = args[1];
                String prefix = args[2];
                boolean ok = op.equals("allow")
                        ? plugin.addNetworkChannelPrefix(modId, prefix)
                        : plugin.removeNetworkChannelPrefix(modId, prefix);
                if (!ok) {
                    context.sender().sendMessage(Message.raw("Policy update failed. Validate modId/prefix and check logs."));
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw((op.equals("allow") ? "Added" : "Removed") + " network prefix policy: mod=" + modId + " prefix=" + prefix));
                return CompletableFuture.completedFuture(null);
            }

            if (op.equals("clear")) {
                if (args.length < 2) {
                    context.sender().sendMessage(Message.raw("Usage: /lua policy clear <modId|*>"));
                    return CompletableFuture.completedFuture(null);
                }
                boolean ok = plugin.clearNetworkChannelPolicy(args[1]);
                if (!ok) {
                    context.sender().sendMessage(Message.raw("Policy clear failed. Validate modId and check logs."));
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw("Cleared network policy for mod=" + args[1]));
                return CompletableFuture.completedFuture(null);
            }

            if (op.equals("cap")) {
                if (args.length < 4) {
                    context.sender().sendMessage(Message.raw("Usage: /lua policy cap <modId> <player-movement|entity-control|world-control|network-control|ui-control> <on|off>"));
                    return CompletableFuture.completedFuture(null);
                }
                String modId = args[1];
                String capability = args[2];
                String mode = args[3].trim().toLowerCase(java.util.Locale.ROOT);
                boolean enabled;
                if (mode.equals("on") || mode.equals("true") || mode.equals("1")) {
                    enabled = true;
                } else if (mode.equals("off") || mode.equals("false") || mode.equals("0")) {
                    enabled = false;
                } else {
                    context.sender().sendMessage(Message.raw("Capability mode must be on/off."));
                    return CompletableFuture.completedFuture(null);
                }
                boolean ok = plugin.setCapabilityGrant(modId, capability, enabled);
                if (!ok) {
                    context.sender().sendMessage(Message.raw("Capability update failed. Validate modId/capability and check logs."));
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw("Updated capability: mod=" + modId + " capability=" + capability + " enabled=" + enabled));
                return CompletableFuture.completedFuture(null);
            }

            if (op.equals("list")) {
                String target = args.length >= 2 ? args[1] : null;
                if (target != null && !target.isBlank()) {
                    printPolicySnapshot(context, target);
                    return CompletableFuture.completedFuture(null);
                }
                context.sender().sendMessage(Message.raw("Policy snapshot:"));
                for (LuaMod mod : mm.listMods()) {
                    printPolicySnapshot(context, mod.manifest().id());
                }
                printPolicySnapshot(context, "*");
                return CompletableFuture.completedFuture(null);
            }

            if (args.length < 2) {
                context.sender().sendMessage(Message.raw("Usage: /lua policy <modId> <channel>"));
                return CompletableFuture.completedFuture(null);
            }

            String modId = args[0];
            String channel = args[1];

            boolean hasCap = plugin.canUseCapability(modId, "network-control");
            java.util.Set<String> prefixes = plugin.networkPrefixesForMod(modId);
            ArcaneLoaderPlugin.NetworkChannelDecision decision = plugin.evaluateNetworkChannel(modId, channel);

            context.sender().sendMessage(Message.raw("Policy check for mod=" + modId + " channel=" + channel));
            context.sender().sendMessage(Message.raw(" - network-control=" + hasCap));
            context.sender().sendMessage(Message.raw(" - prefixes=" + (prefixes.isEmpty() ? "*" : String.join(",", prefixes))));
            context.sender().sendMessage(Message.raw(" - matchedPrefix=" + (decision.matchedPrefix() == null ? "(none)" : decision.matchedPrefix())));
            context.sender().sendMessage(Message.raw(" - reason=" + decision.reason()));
            context.sender().sendMessage(Message.raw(" - allowed=" + decision.allowed()));
            return CompletableFuture.completedFuture(null);
        }

        private void printPolicySnapshot(CommandContext context, String modId) {
            java.util.Set<String> prefixes = plugin.networkPrefixesForMod(modId);
            context.sender().sendMessage(Message.raw(
                    " - " + modId
                            + " player-movement=" + plugin.canUseCapability(modId, "player-movement")
                            + " entity-control=" + plugin.canUseCapability(modId, "entity-control")
                            + " world-control=" + plugin.canUseCapability(modId, "world-control")
                            + " network-control=" + plugin.canUseCapability(modId, "network-control")
                            + " ui-control=" + plugin.canUseCapability(modId, "ui-control")
                            + " prefixes=" + (prefixes.isEmpty() ? "*" : String.join(",", prefixes))
            ));
        }
    }

    private static final class DoctorSub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private DoctorSub(ArcaneLoaderPlugin plugin) {
            super("doctor", "Run Arcane Loader health checks");
            this.plugin = plugin;
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }

            int warnings = 0;
            int errors = 0;
            context.sender().sendMessage(Message.raw("Arcane Loader doctor report:"));

            // Filesystem checks
            if (java.nio.file.Files.isDirectory(plugin.getLuaModsDir())) {
                context.sender().sendMessage(Message.raw(" - OK lua_mods dir: " + plugin.getLuaModsDir()));
            } else {
                errors++;
                context.sender().sendMessage(Message.raw(" - ERROR missing lua_mods dir: " + plugin.getLuaModsDir()));
            }
            if (java.nio.file.Files.isDirectory(plugin.getLuaCacheDir())) {
                context.sender().sendMessage(Message.raw(" - OK lua_cache dir: " + plugin.getLuaCacheDir()));
            } else {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN missing lua_cache dir: " + plugin.getLuaCacheDir()));
            }
            if (java.nio.file.Files.isDirectory(plugin.getLuaDataDir())) {
                context.sender().sendMessage(Message.raw(" - OK lua_data dir: " + plugin.getLuaDataDir()));
            } else {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN missing lua_data dir: " + plugin.getLuaDataDir()));
            }
            if (java.nio.file.Files.exists(plugin.getConfigPath())) {
                context.sender().sendMessage(Message.raw(" - OK config file: " + plugin.getConfigPath()));
            } else {
                errors++;
                context.sender().sendMessage(Message.raw(" - ERROR missing config file: " + plugin.getConfigPath()));
            }
            if (plugin.isAutoReload() == plugin.isWatcherActive()) {
                context.sender().sendMessage(Message.raw(" - OK autoReload watcher state aligned (autoReload=" + plugin.isAutoReload() + ")."));
            } else {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN autoReload watcher mismatch (autoReload=" + plugin.isAutoReload() + ", watcherActive=" + plugin.isWatcherActive() + ")."));
            }

            // Config sanity checks
            if (plugin.isAllowlistEnabled() && plugin.getAllowlist().isEmpty()) {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN allowlistEnabled=true but allowlist is empty (no mods can load)."));
            }
            if (plugin.isRestrictSensitiveApis()) {
                if (plugin.getPlayerMovementMods().isEmpty()) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN playerMovementMods is empty while restrictions are enabled."));
                }
                if (plugin.getEntityControlMods().isEmpty()) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN entityControlMods is empty while restrictions are enabled."));
                }
                if (plugin.getWorldControlMods().isEmpty()) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN worldControlMods is empty while restrictions are enabled."));
                }
                if (plugin.getNetworkControlMods().isEmpty()) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN networkControlMods is empty while restrictions are enabled."));
                }
                if (plugin.getUiControlMods().isEmpty()) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN uiControlMods is empty while restrictions are enabled."));
                }
            }
            if (plugin.getSlowCallWarnMs() <= 0.0) {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN slowCallWarnMs <= 0 (slow-call tracking effectively disabled)."));
            }

            // Mod scan and runtime checks
            mm.scanMods();
            int totalMods = 0;
            int enabledMods = 0;
            int errorMods = 0;
            java.util.Set<String> discoveredIds = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (LuaMod mod : mm.listMods()) {
                totalMods++;
                if (mod.state() == arcane.loader.lua.LuaModState.ENABLED) enabledMods++;
                if (mod.state() == arcane.loader.lua.LuaModState.ERROR) errorMods++;
                discoveredIds.add(mod.manifest().id());
            }
            if (totalMods == 0) {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN no mods discovered in lua_mods."));
            } else {
                context.sender().sendMessage(Message.raw(" - OK mods discovered=" + totalMods + " enabled=" + enabledMods + " error=" + errorMods));
            }
            if (errorMods > 0) {
                warnings++;
                context.sender().sendMessage(Message.raw(" - WARN one or more mods are in ERROR state. Use /lua errors."));
            }

            // Config audit: stale mod IDs in capability lists
            warnings += warnStaleIds(context, "playerMovementMods", plugin.getPlayerMovementMods(), discoveredIds);
            warnings += warnStaleIds(context, "entityControlMods", plugin.getEntityControlMods(), discoveredIds);
            warnings += warnStaleIds(context, "worldControlMods", plugin.getWorldControlMods(), discoveredIds);
            warnings += warnStaleIds(context, "networkControlMods", plugin.getNetworkControlMods(), discoveredIds);
            warnings += warnStaleIds(context, "uiControlMods", plugin.getUiControlMods(), discoveredIds);

            // Network policy sanity
            if (!plugin.getNetworkChannelPolicies().isEmpty()) {
                java.util.Set<String> wildcard = plugin.getNetworkChannelPolicies().get("*");
                if (wildcard == null || wildcard.isEmpty()) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN networkChannelPolicies has no '*' fallback; unspecified mods may be blocked."));
                } else {
                    context.sender().sendMessage(Message.raw(" - OK networkChannelPolicies wildcard prefixes=" + String.join(",", wildcard)));
                }
                for (String policyModId : plugin.getNetworkChannelPolicies().keySet()) {
                    if (policyModId.equals("*")) continue;
                    if (!discoveredIds.contains(policyModId)) {
                        warnings++;
                        context.sender().sendMessage(Message.raw(" - WARN networkChannelPolicies contains unknown mod id: " + policyModId));
                    }
                }
            } else {
                context.sender().sendMessage(Message.raw(" - INFO networkChannelPolicies not configured (channels unrestricted after capability check)."));
            }

            context.sender().sendMessage(Message.raw("Doctor summary: errors=" + errors + " warnings=" + warnings));
            if (errors == 0 && warnings == 0) {
                context.sender().sendMessage(Message.raw("Doctor status: HEALTHY"));
            } else if (errors == 0) {
                context.sender().sendMessage(Message.raw("Doctor status: OK_WITH_WARNINGS"));
            } else {
                context.sender().sendMessage(Message.raw("Doctor status: NEEDS_ATTENTION"));
            }
            return CompletableFuture.completedFuture(null);
        }

        private static int warnStaleIds(CommandContext context, String label, java.util.Set<String> configuredIds, java.util.Set<String> discoveredIds) {
            if (configuredIds == null || configuredIds.isEmpty()) return 0;
            int warnings = 0;
            for (String id : configuredIds) {
                if (id == null || id.isBlank()) continue;
                if (!discoveredIds.contains(id)) {
                    warnings++;
                    context.sender().sendMessage(Message.raw(" - WARN " + label + " contains unknown mod id: " + id));
                }
            }
            return warnings;
        }
    }

    private static final class VerifySub extends com.hypixel.hytale.server.core.command.system.AbstractCommand {
        private final ArcaneLoaderPlugin plugin;

        private VerifySub(ArcaneLoaderPlugin plugin) {
            super("verify", "Runtime integration assertions: /lua verify [benchPrefix]");
            this.plugin = plugin;
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            LuaModManager mm = plugin.getModManager();
            if (mm == null) {
                context.sender().sendMessage(Message.raw("Mod manager not ready."));
                return CompletableFuture.completedFuture(null);
            }
            mm.scanMods();

            String prefix = firstArgOrNull(context);
            if (prefix == null) prefix = "bench_";
            final String prefixNorm = prefix.toLowerCase(java.util.Locale.ROOT);

            int failures = 0;
            int checked = 0;
            java.util.List<LuaMod> matched = new java.util.ArrayList<>();
            for (LuaMod mod : mm.listMods()) {
                if (mod.manifest().id().toLowerCase(java.util.Locale.ROOT).startsWith(prefixNorm)) {
                    matched.add(mod);
                }
            }
            matched.sort(java.util.Comparator.comparing(m -> m.manifest().id(), String.CASE_INSENSITIVE_ORDER));
            if (matched.isEmpty()) {
                context.sender().sendMessage(Message.raw("No mods found for prefix: " + prefix));
                return CompletableFuture.completedFuture(null);
            }

            context.sender().sendMessage(Message.raw("Runtime verify for " + matched.size() + " mod(s), prefix=" + prefix));
            for (LuaMod mod : matched) {
                checked++;
                String id = mod.manifest().id();
                boolean enabled = mod.state() == arcane.loader.lua.LuaModState.ENABLED;
                if (!enabled) {
                    failures++;
                    context.sender().sendMessage(Message.raw(" - FAIL " + id + " state=" + mod.state()));
                    continue;
                }
                if (mod.ctx() == null || mod.globals() == null || mod.module() == null) {
                    failures++;
                    context.sender().sendMessage(Message.raw(" - FAIL " + id + " missing active runtime references"));
                    continue;
                }
                long tickEvents = mod.invocationBreakdown().getOrDefault("event:tick", new LuaMod.InvocationStats(0, 0, 0, 0, 0)).count();
                if (tickEvents == 0) {
                    failures++;
                    context.sender().sendMessage(Message.raw(" - FAIL " + id + " no event:tick invocations recorded"));
                    continue;
                }
                context.sender().sendMessage(Message.raw(" - OK " + id + " tickEvents=" + tickEvents + " failures=" + mod.invocationFailures()));
            }

            // Chain order assertion for bench_* numeric sequence.
            int chainChecked = 0;
            for (int i = 1; i < matched.size(); i++) {
                LuaMod curr = matched.get(i);
                LuaMod prev = matched.get(i - 1);
                if (!curr.manifest().id().matches(".*\\d+$")) continue;
                chainChecked++;
                boolean hasExpectedAfter = curr.manifest().loadAfter().contains(prev.manifest().id());
                if (!hasExpectedAfter) {
                    failures++;
                    context.sender().sendMessage(Message.raw(" - FAIL chain " + curr.manifest().id() + " missing loadAfter " + prev.manifest().id()));
                }
            }

            context.sender().sendMessage(Message.raw("Verify summary: checked=" + checked + " chainChecked=" + chainChecked + " failures=" + failures));
            context.sender().sendMessage(Message.raw(failures == 0 ? "Verify status: PASS" : "Verify status: FAIL"));
            return CompletableFuture.completedFuture(null);
        }
    }
}
