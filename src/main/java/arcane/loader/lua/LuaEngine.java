package arcane.loader.lua;

import arcane.loader.ArcaneLoaderPlugin;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.logging.Level;

/**
 * Lua runtime bootstrap (LuaJ).
 *
 * Creates per-mod Globals and installs the Arcane API tables (log/events/fs/commands/etc.).
 */
public final class LuaEngine {
    private static final String LUA_API_VERSION = "1.0.0";
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ArcaneLoaderPlugin plugin;
    private final LuaTaskScheduler scheduler;

    public LuaEngine(ArcaneLoaderPlugin plugin, LuaTaskScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    public Globals createGlobals(LuaModContext ctx) {
        Globals g = JsePlatform.standardGlobals();
        hardenSandbox(g);
        g.set("log", createLogTable(ctx));
        g.set("commands", createCommandsTable(ctx));
        g.set("events", createEventsTable(ctx));
        g.set("fs", createFsTable(ctx));
        g.set("store", createStoreTable(ctx));
        g.set("assets", createAssetsTable(ctx));
        g.set("players", createPlayersTable(ctx));
        g.set("entities", createEntitiesTable(ctx));
        g.set("actors", createActorsTable(ctx));
        g.set("volumes", createVolumesTable(ctx));
        g.set("mechanics", createMechanicsTable(ctx));
        g.set("world", createWorldTable(ctx));
        g.set("blocks", createBlocksTable(ctx));
        g.set("network", createNetworkTable(ctx));
        g.set("webhook", createWebhookTable(ctx));
        g.set("ui", createUiTable(ctx));
        g.set("arcane", createArcaneTable(ctx));
        g.set("components", createComponentsTable(ctx));
        g.set("vec3", createVec3Table());
        g.set("blockpos", createBlockPosTable());
        g.set("spatial", createSpatialTable());
        g.set("query", createQueryTable(ctx));
        g.set("sim", createSimulationTable(ctx));
        g.set("registry", createRegistryTable(ctx));
        g.set("transforms", createTransformsTable(ctx));
        g.set("standins", createStandInsTable(ctx));
        g.set("interop", createInteropTable(ctx));

        // Restrict require() to mod-local script paths.
        try {
            String root = ctx.modRoot().replace("\\", "/");
            String p = root + "/?.lua;" + root + "/?/init.lua;" + root + "/modules/?.lua;" + root + "/modules/?/init.lua";
            LuaValue pkg = g.get("package");
            if (!pkg.isnil() && pkg.istable()) {
                ((LuaTable) pkg).set("path", LuaValue.valueOf(p));
            }
        } catch (Throwable ignored) { }


        LuaValue ctxUser = LuaValue.userdataOf(ctx);

        LuaTable mt = new LuaTable();
        mt.set("__index", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue self, LuaValue key) {
                String k = key.checkjstring();
                switch (k) {
                    case "modRoot":
                        return new ZeroArgFunction() {
                            @Override public LuaValue call() {
                                return LuaValue.valueOf(ctx.modRoot());
                            }
                        };
                    
case "dataDir":
    return new ZeroArgFunction() {
        @Override public LuaValue call() {
            return LuaValue.valueOf(ctx.dataDir());
        }
    };
case "readText":
    return new VarArgFunction() {
        @Override public Varargs invoke(Varargs args) {
            try {
                String rel = args.arg(1).checkjstring();
                return LuaValue.valueOf(ctx.readText(rel));
            } catch (Exception e) {
                throw new LuaError("readText failed: " + e.getMessage());
            }
        }
    };
case "writeText":
    return new VarArgFunction() {
        @Override public Varargs invoke(Varargs args) {
            try {
                String rel = args.arg(1).checkjstring();
                String txt = args.arg(2).optjstring("");
                ctx.writeText(rel, txt);
                return LuaValue.NIL;
            } catch (Exception e) {
                throw new LuaError("writeText failed: " + e.getMessage());
            }
        }
    };
case "appendText":
    return new VarArgFunction() {
        @Override public Varargs invoke(Varargs args) {
            try {
                String rel = args.arg(1).checkjstring();
                String txt = args.arg(2).optjstring("");
                ctx.appendText(rel, txt);
                return LuaValue.NIL;
            } catch (Exception e) {
                throw new LuaError("appendText failed: " + e.getMessage());
            }
        }
    };
                    case "log":
                        return new OneArgFunction() {
                            @Override public LuaValue call(LuaValue msg) {
                                ctx.log(msg.tojstring());
                                return LuaValue.NIL;
                            }
                        };
                    case "command":
                        return new ThreeArgFunction() {
                            @Override public LuaValue call(LuaValue name, LuaValue help, LuaValue fn) {
                                if (!fn.isfunction()) return LuaValue.NIL;
                                ctx.command(name.checkjstring(), help.isnil() ? "" : help.tojstring(), (LuaFunction) fn);
                                return LuaValue.NIL;
                            }
                        };
                    case "on":
                        return new VarArgFunction() {
                            @Override public Varargs invoke(Varargs args) {
                                String event = args.arg(1).checkjstring();
                                RegistrationOptions options = parseEventRegistrationOptions(args, 2);
                                LuaValue fn = args.arg(options.functionArgIndex());
                                if (!fn.isfunction()) return LuaValue.NIL;
                                ctx.on(event, (LuaFunction) fn, options.priority(), options.ignoreCancelled(), options.once());
                                return LuaValue.userdataOf(fn);
                            }
                        };
                    case "off":
                        return new VarArgFunction() {
                            @Override public Varargs invoke(Varargs args) {
                                String event = args.arg(1).checkjstring();
                                LuaValue fn = args.arg(2);
                                int removed;
                                if (fn.isfunction()) {
                                    removed = ctx.off(event, (LuaFunction) fn);
                                } else {
                                    removed = ctx.clearEvent(event);
                                }
                                return LuaValue.valueOf(removed);
                            }
                        };
                    case "setTimeout":
                        return new VarArgFunction() {
                            @Override public Varargs invoke(Varargs args) {
                                double ms = args.arg(1).checkdouble();
                                LuaValue second = args.arg(2);
                                LuaValue third = args.arg(3);
                                String label = "timeout";
                                LuaValue fn = second;
                                if (second.isstring() && third.isfunction()) {
                                    label = second.checkjstring();
                                    fn = third;
                                }
                                if (!fn.isfunction()) return LuaValue.NIL;
                                Object handle = ctx.setTimeout(ms, label, (LuaFunction) fn, scheduler);
                                return LuaValue.userdataOf(handle);
                            }
                        };
                    case "setInterval":
                        return new VarArgFunction() {
                            @Override public Varargs invoke(Varargs args) {
                                double ms = args.arg(1).checkdouble();
                                LuaValue second = args.arg(2);
                                LuaValue third = args.arg(3);
                                String label = "interval";
                                LuaValue fn = second;
                                if (second.isstring() && third.isfunction()) {
                                    label = second.checkjstring();
                                    fn = third;
                                }
                                if (!fn.isfunction()) return LuaValue.NIL;
                                Object handle = ctx.setInterval(ms, label, (LuaFunction) fn, scheduler);
                                return LuaValue.userdataOf(handle);
                            }
                        };
                    case "cancelTimer":
                        return new OneArgFunction() {
                            @Override public LuaValue call(LuaValue handle) {
                                Object h = handle.isuserdata() ? handle.touserdata() : null;
                                return LuaValue.valueOf(ctx.cancelTask(h));
                            }
                        };
                    case "timerActive":
                        return new OneArgFunction() {
                            @Override public LuaValue call(LuaValue handle) {
                                Object h = handle.isuserdata() ? handle.touserdata() : null;
                                return LuaValue.valueOf(ctx.isTaskActive(h));
                            }
                        };
                    case "timers":
                        return new ZeroArgFunction() {
                            @Override public LuaValue call() {
                                return javaToLuaValue(ctx.taskInfo());
                            }
                        };
                    case "timerCount":
                        return new ZeroArgFunction() {
                            @Override public LuaValue call() {
                                return LuaValue.valueOf(ctx.activeTaskCount());
                            }
                        };
                    default:
                        return LuaValue.NIL;
                }
            }
        });

        ctxUser.setmetatable(mt);
        g.set("ctx", ctxUser);

        return g;
    }

    private static void hardenSandbox(Globals g) {
        // Block host access primitives from mod scripts.
        g.set("os", LuaValue.NIL);
        g.set("io", LuaValue.NIL);
        g.set("luajava", LuaValue.NIL);
        g.set("debug", LuaValue.NIL);
        g.set("dofile", LuaValue.NIL);
        g.set("loadfile", LuaValue.NIL);

        LuaValue pkg = g.get("package");
        if (!pkg.isnil() && pkg.istable()) {
            LuaTable packageTable = (LuaTable) pkg;
            packageTable.set("cpath", LuaValue.valueOf(""));
            packageTable.set("loadlib", LuaValue.NIL);
        }
    }

    private static LuaTable createLogTable(LuaModContext ctx) {
        LuaTable log = new LuaTable();
        log.set("info", new OneArgFunction() {
            @Override public LuaValue call(LuaValue msg) {
                ctx.logInfo(msg.tojstring());
                return LuaValue.NIL;
            }
        });
        log.set("warn", new OneArgFunction() {
            @Override public LuaValue call(LuaValue msg) {
                ctx.logWarn(msg.tojstring());
                return LuaValue.NIL;
            }
        });
        log.set("error", new OneArgFunction() {
            @Override public LuaValue call(LuaValue msg) {
                ctx.logError(msg.tojstring());
                return LuaValue.NIL;
            }
        });
        return log;
    }

    private static LuaTable createCommandsTable(LuaModContext ctx) {
        LuaTable commands = new LuaTable();
        commands.set("register", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String name = args.arg(1).checkjstring();
                LuaValue arg2 = args.arg(2);
                LuaValue arg3 = args.arg(3);

                String help = "";
                LuaFunction fn;
                if (arg2.isfunction()) {
                    fn = (LuaFunction) arg2;
                } else {
                    help = arg2.isnil() ? "" : arg2.tojstring();
                    if (!arg3.isfunction()) return LuaValue.NIL;
                    fn = (LuaFunction) arg3;
                }

                ctx.command(name, help, fn);
                return LuaValue.NIL;
            }
        });
        commands.set("unregister", new OneArgFunction() {
            @Override public LuaValue call(LuaValue name) {
                ctx.unregisterCommand(name.checkjstring());
                return LuaValue.NIL;
            }
        });
        commands.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable out = new LuaTable();
                int i = 1;
                for (String name : ctx.commandNames()) {
                    out.set(i++, LuaValue.valueOf(name));
                }
                return out;
            }
        });
        commands.set("help", new OneArgFunction() {
            @Override public LuaValue call(LuaValue name) {
                String help = ctx.commandHelp(name.checkjstring());
                return help == null || help.isBlank() ? LuaValue.NIL : LuaValue.valueOf(help);
            }
        });
        return commands;
    }

    private static LuaTable createEventsTable(LuaModContext ctx) {
        LuaTable events = new LuaTable();
        LuaTable priorities = new LuaTable();
        priorities.set("LOWEST", LuaValue.valueOf(LuaEventBus.PRIORITY_LOWEST));
        priorities.set("LOW", LuaValue.valueOf(LuaEventBus.PRIORITY_LOW));
        priorities.set("NORMAL", LuaValue.valueOf(LuaEventBus.PRIORITY_NORMAL));
        priorities.set("HIGH", LuaValue.valueOf(LuaEventBus.PRIORITY_HIGH));
        priorities.set("HIGHEST", LuaValue.valueOf(LuaEventBus.PRIORITY_HIGHEST));
        priorities.set("MONITOR", LuaValue.valueOf(LuaEventBus.PRIORITY_MONITOR));
        events.set("priority", priorities);
        events.set("on", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String eventName = args.arg(1).checkjstring();
                RegistrationOptions options = parseEventRegistrationOptions(args, 2);
                LuaValue fn = args.arg(options.functionArgIndex());
                if (!fn.isfunction()) return LuaValue.NIL;
                ctx.on(eventName, (LuaFunction) fn, options.priority(), options.ignoreCancelled(), options.once());
                return LuaValue.userdataOf(fn);
            }
        });
        events.set("once", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String eventName = args.arg(1).checkjstring();
                RegistrationOptions options = parseEventRegistrationOptions(args, 2).withOnce(true);
                LuaValue fn = args.arg(options.functionArgIndex());
                if (!fn.isfunction()) return LuaValue.NIL;
                ctx.on(eventName, (LuaFunction) fn, options.priority(), options.ignoreCancelled(), true);
                return LuaValue.userdataOf(fn);
            }
        });
        events.set("emit", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String eventName = args.arg(1).checkjstring();
                int n = Math.max(0, args.narg() - 1);
                LuaValue[] payload = new LuaValue[n];
                for (int i = 0; i < n; i++) {
                    payload[i] = args.arg(i + 2);
                }
                ctx.emit(eventName, payload);
                return LuaValue.NIL;
            }
        });
        events.set("off", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String eventName = args.arg(1).checkjstring();
                LuaValue fn = args.arg(2);
                int removed;
                if (fn.isfunction()) {
                    removed = ctx.off(eventName, (LuaFunction) fn);
                } else {
                    removed = ctx.clearEvent(eventName);
                }
                return LuaValue.valueOf(removed);
            }
        });
        events.set("clear", new OneArgFunction() {
            @Override public LuaValue call(LuaValue eventName) {
                return LuaValue.valueOf(ctx.clearEvent(eventName.checkjstring()));
            }
        });
        events.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable out = new LuaTable();
                int i = 1;
                for (String eventName : ctx.eventNames()) {
                    out.set(i++, LuaValue.valueOf(eventName));
                }
                return out;
            }
        });
        events.set("count", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (args.narg() >= 1 && !args.arg(1).isnil()) {
                    return LuaValue.valueOf(ctx.eventListenerCount(args.arg(1).checkjstring()));
                }
                return LuaValue.valueOf(ctx.totalEventListeners());
            }
        });
        events.set("listeners", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (args.narg() < 1 || args.arg(1).isnil()) {
                    return LuaValue.NIL;
                }
                return javaToLuaValue(ctx.eventListeners(args.arg(1).checkjstring()));
            }
        });
        return events;
    }

    private static RegistrationOptions parseEventRegistrationOptions(Varargs args, int optionsIndex) {
        LuaValue maybeOptions = args.arg(optionsIndex);
        if (maybeOptions.istable() && args.arg(optionsIndex + 1).isfunction()) {
            LuaTable options = (LuaTable) maybeOptions;
            int priority = LuaEventBus.PRIORITY_NORMAL;
            LuaValue priorityValue = options.get("priority");
            if (priorityValue.isnumber()) {
                priority = priorityValue.toint();
            } else if (priorityValue.isstring()) {
                priority = LuaEventBus.priorityFromName(priorityValue.tojstring());
            }
            boolean ignoreCancelled = options.get("ignoreCancelled").optboolean(false)
                    || options.get("ignoreCanceled").optboolean(false);
            boolean once = options.get("once").optboolean(false);
            return new RegistrationOptions(priority, ignoreCancelled, once, optionsIndex + 1);
        }
        return new RegistrationOptions(LuaEventBus.PRIORITY_NORMAL, false, false, optionsIndex);
    }

    private record RegistrationOptions(int priority, boolean ignoreCancelled, boolean once, int functionArgIndex) {
        private RegistrationOptions withOnce(boolean next) {
            return new RegistrationOptions(priority, ignoreCancelled, next, functionArgIndex);
        }
    }

    private static LuaTable createFsTable(LuaModContext ctx) {
        LuaTable fs = new LuaTable();
        fs.set("read", new OneArgFunction() {
            @Override public LuaValue call(LuaValue path) {
                try {
                    return LuaValue.valueOf(ctx.readText(path.checkjstring()));
                } catch (Exception e) {
                    throw new LuaError("fs.read failed: " + e.getMessage());
                }
            }
        });
        fs.set("write", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue path, LuaValue data) {
                try {
                    ctx.writeText(path.checkjstring(), data.optjstring(""));
                    return LuaValue.NIL;
                } catch (Exception e) {
                    throw new LuaError("fs.write failed: " + e.getMessage());
                }
            }
        });
        fs.set("append", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue path, LuaValue data) {
                try {
                    ctx.appendText(path.checkjstring(), data.optjstring(""));
                    return LuaValue.NIL;
                } catch (Exception e) {
                    throw new LuaError("fs.append failed: " + e.getMessage());
                }
            }
        });
        fs.set("mkdir", new OneArgFunction() {
            @Override public LuaValue call(LuaValue path) {
                try {
                    ctx.mkdirs(path.checkjstring());
                    return LuaValue.TRUE;
                } catch (Exception e) {
                    throw new LuaError("fs.mkdir failed: " + e.getMessage());
                }
            }
        });
        fs.set("readJson", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String path = args.arg(1).checkjstring();
                LuaValue defaults = args.arg(2);
                boolean writeBack = args.arg(3).optboolean(false);
                try {
                    LuaValue loaded = LuaValue.NIL;
                    if (ctx.exists(path)) {
                        String text = ctx.readText(path);
                        if (text != null && !text.isBlank()) {
                            loaded = jsonToLua(JsonParser.parseString(text));
                        }
                    }
                    LuaValue merged = defaults.isnil() ? loaded : mergeWithDefaults(loaded, defaults);
                    if (writeBack && !merged.isnil()) {
                        String out = PRETTY_GSON.toJson(luaToJsonElement(merged));
                        ctx.writeText(path, out + System.lineSeparator());
                    }
                    return merged;
                } catch (Exception e) {
                    throw new LuaError("fs.readJson failed: " + e.getMessage());
                }
            }
        });
        fs.set("writeJson", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String path = args.arg(1).checkjstring();
                LuaValue value = args.arg(2);
                boolean pretty = args.arg(3).optboolean(true);
                try {
                    JsonElement json = luaToJsonElement(value);
                    String out = (pretty ? PRETTY_GSON : GSON).toJson(json);
                    ctx.writeText(path, out + System.lineSeparator());
                    return LuaValue.TRUE;
                } catch (Exception e) {
                    throw new LuaError("fs.writeJson failed: " + e.getMessage());
                }
            }
        });
        fs.set("delete", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String path = args.arg(1).checkjstring();
                boolean recursive = args.arg(2).optboolean(false);
                try {
                    return LuaValue.valueOf(ctx.deletePath(path, recursive));
                } catch (Exception e) {
                    throw new LuaError("fs.delete failed: " + e.getMessage());
                }
            }
        });
        fs.set("move", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String from = args.arg(1).checkjstring();
                String to = args.arg(2).checkjstring();
                boolean replace = args.arg(3).optboolean(false);
                try {
                    return LuaValue.valueOf(ctx.movePath(from, to, replace));
                } catch (Exception e) {
                    throw new LuaError("fs.move failed: " + e.getMessage());
                }
            }
        });
        fs.set("copy", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String from = args.arg(1).checkjstring();
                String to = args.arg(2).checkjstring();
                boolean replace = args.arg(3).optboolean(false);
                try {
                    return LuaValue.valueOf(ctx.copyPath(from, to, replace));
                } catch (Exception e) {
                    throw new LuaError("fs.copy failed: " + e.getMessage());
                }
            }
        });
        fs.set("exists", new OneArgFunction() {
            @Override public LuaValue call(LuaValue path) {
                try {
                    return LuaValue.valueOf(ctx.exists(path.checkjstring()));
                } catch (Exception e) {
                    throw new LuaError("fs.exists failed: " + e.getMessage());
                }
            }
        });
        fs.set("list", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String rel = args.narg() >= 1 ? args.arg(1).checkjstring() : ".";
                try {
                    LuaTable out = new LuaTable();
                    int i = 1;
                    for (String name : ctx.list(rel)) {
                        out.set(i++, LuaValue.valueOf(name));
                    }
                    return out;
                } catch (Exception e) {
                    throw new LuaError("fs.list failed: " + e.getMessage());
                }
            }
        });
        return fs;
    }

    private static LuaTable createStoreTable(LuaModContext ctx) {
        LuaTable store = new LuaTable();
        store.set("path", new OneArgFunction() {
            @Override public LuaValue call(LuaValue name) {
                return LuaValue.valueOf(storePath(name.checkjstring()));
            }
        });
        store.set("exists", new OneArgFunction() {
            @Override public LuaValue call(LuaValue name) {
                try {
                    return LuaValue.valueOf(ctx.exists(storePath(name.checkjstring())));
                } catch (Exception e) {
                    throw new LuaError("store.exists failed: " + e.getMessage());
                }
            }
        });
        store.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try {
                    return javaToLuaValue(ctx.list("stores"));
                } catch (Exception e) {
                    throw new LuaError("store.list failed: " + e.getMessage());
                }
            }
        });
        store.set("load", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String path = storePath(args.arg(1).checkjstring());
                LuaValue defaults = args.arg(2);
                boolean writeBack = args.arg(3).optboolean(false);
                try {
                    return loadJsonValue(ctx, path, defaults, writeBack);
                } catch (Exception e) {
                    throw new LuaError("store.load failed: " + e.getMessage());
                }
            }
        });
        store.set("loadVersioned", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String path = storePath(args.arg(1).checkjstring());
                int targetVersion = Math.max(1, args.arg(2).optint(1));
                LuaValue defaults = args.arg(3);
                LuaValue migrators = args.arg(4);
                boolean writeBack = args.arg(5).optboolean(false);
                try {
                    LuaValue state = loadJsonValue(ctx, path, defaults, false);
                    if (state.isnil() || !state.istable()) {
                        state = defaults.isnil() ? new LuaTable() : deepCopyLuaValue(defaults);
                    }
                    LuaTable table = state.istable() ? (LuaTable) state : new LuaTable();
                    int currentVersion = Math.max(0, table.get("schemaVersion").optint(0));
                    boolean changed = currentVersion != targetVersion;
                    if (migrators.istable() && currentVersion < targetVersion) {
                        for (int version = currentVersion + 1; version <= targetVersion; version++) {
                            LuaValue migrator = migrators.get(version);
                            if (!migrator.isfunction()) continue;
                            LuaValue next = migrator.call(table);
                            if (next.istable()) {
                                table = (LuaTable) next;
                            }
                            changed = true;
                        }
                    }
                    if (!defaults.isnil()) {
                        LuaValue merged = mergeWithDefaults(table, defaults);
                        if (merged.istable()) {
                            table = (LuaTable) merged;
                        }
                    }
                    table.set("schemaVersion", LuaValue.valueOf(targetVersion));
                    if (writeBack || changed) {
                        writeJsonValue(ctx, path, table, true);
                    }
                    return table;
                } catch (Exception e) {
                    throw new LuaError("store.loadVersioned failed: " + e.getMessage());
                }
            }
        });
        store.set("save", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String path = storePath(args.arg(1).checkjstring());
                LuaValue value = args.arg(2);
                boolean pretty = args.arg(3).optboolean(true);
                try {
                    writeJsonValue(ctx, path, value, pretty);
                    return LuaValue.TRUE;
                } catch (Exception e) {
                    throw new LuaError("store.save failed: " + e.getMessage());
                }
            }
        });
        store.set("saveVersioned", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String path = storePath(args.arg(1).checkjstring());
                int targetVersion = Math.max(1, args.arg(2).optint(1));
                LuaValue value = args.arg(3);
                boolean pretty = args.arg(4).optboolean(true);
                try {
                    LuaTable table = value.istable() ? (LuaTable) deepCopyLuaValue(value) : new LuaTable();
                    table.set("schemaVersion", LuaValue.valueOf(targetVersion));
                    writeJsonValue(ctx, path, table, pretty);
                    return LuaValue.TRUE;
                } catch (Exception e) {
                    throw new LuaError("store.saveVersioned failed: " + e.getMessage());
                }
            }
        });
        store.set("delete", new OneArgFunction() {
            @Override public LuaValue call(LuaValue name) {
                try {
                    return LuaValue.valueOf(ctx.deletePath(storePath(name.checkjstring()), false));
                } catch (Exception e) {
                    throw new LuaError("store.delete failed: " + e.getMessage());
                }
            }
        });
        return store;
    }

    private static LuaTable createAssetsTable(LuaModContext ctx) {
        LuaTable assets = new LuaTable();
        assets.set("root", new ZeroArgFunction() {
            @Override public LuaValue call() {
                String root = ctx.assetsRoot();
                return root == null ? LuaValue.NIL : LuaValue.valueOf(root);
            }
        });
        assets.set("exists", new OneArgFunction() {
            @Override public LuaValue call(LuaValue relPath) {
                return LuaValue.valueOf(ctx.assetExists(relPath.checkjstring()));
            }
        });
        assets.set("path", new OneArgFunction() {
            @Override public LuaValue call(LuaValue relPath) {
                String p = ctx.assetPath(relPath.checkjstring());
                return p == null ? LuaValue.NIL : LuaValue.valueOf(p);
            }
        });
        assets.set("list", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String prefix = args.narg() >= 1 ? args.arg(1).optjstring("") : "";
                int limit = args.narg() >= 2 ? args.arg(2).optint(2000) : 2000;
                LuaTable out = new LuaTable();
                int i = 1;
                for (String p : ctx.listAssets(prefix, limit)) out.set(i++, LuaValue.valueOf(p));
                return out;
            }
        });
        return assets;
    }

    private static Object extractPlayerRef(LuaValue value) {
        if (value == null || value.isnil()) return null;
        if (value.isuserdata()) return value.checkuserdata();
        if (value.istable()) {
            LuaValue fromTable = value.get("playerRef");
            if (fromTable.isuserdata()) return fromTable.checkuserdata();
        }
        return null;
    }

    private static Object extractEntityRef(LuaValue value) {
        if (value == null || value.isnil()) return null;
        if (value.isuserdata()) return value.checkuserdata();
        if (value.istable()) {
            LuaValue fromEntityRef = value.get("entityRef");
            if (fromEntityRef.isuserdata()) return fromEntityRef.checkuserdata();
            LuaValue fromEntity = value.get("entity");
            if (fromEntity.isuserdata()) return fromEntity.checkuserdata();
        }
        return null;
    }

    private static String extractWorldUuid(LuaValue value, LuaModContext ctx) {
        if (value == null || value.isnil()) return null;
        if (value.isstring()) return value.tojstring();
        if (value.isuserdata()) return ctx.worldUuid(value.checkuserdata());
        if (value.istable()) {
            LuaValue uuid = value.get("uuid");
            if (uuid.isstring()) return uuid.tojstring();
            LuaValue worldRef = value.get("worldRef");
            if (worldRef.isuserdata()) return ctx.worldUuid(worldRef.checkuserdata());
        }
        return null;
    }

    private static LuaTable vec3Value(double x, double y, double z) {
        LuaTable out = new LuaTable();
        out.set("x", LuaValue.valueOf(x));
        out.set("y", LuaValue.valueOf(y));
        out.set("z", LuaValue.valueOf(z));
        out.set("__type", LuaValue.valueOf("vec3"));
        return out;
    }

    private static LuaTable blockPosValue(int x, int y, int z) {
        LuaTable out = new LuaTable();
        out.set("x", LuaValue.valueOf(x));
        out.set("y", LuaValue.valueOf(y));
        out.set("z", LuaValue.valueOf(z));
        out.set("__type", LuaValue.valueOf("blockpos"));
        return out;
    }

    private static double[] extractVec3(LuaValue value) {
        if (value == null || value.isnil() || !value.istable()) return null;
        LuaValue x = value.get("x");
        LuaValue y = value.get("y");
        LuaValue z = value.get("z");
        if (x.isnumber() && y.isnumber() && z.isnumber()) {
            return new double[]{x.todouble(), y.todouble(), z.todouble()};
        }
        LuaValue one = value.get(1);
        LuaValue two = value.get(2);
        LuaValue three = value.get(3);
        if (one.isnumber() && two.isnumber() && three.isnumber()) {
            return new double[]{one.todouble(), two.todouble(), three.todouble()};
        }
        return null;
    }

    private static double[] extractTeleportArgs(Varargs args, int worldIndex, LuaModContext ctx) {
        LuaValue worldArg = args.arg(worldIndex);
        String worldUuid = extractWorldUuid(worldArg, ctx);
        if (worldUuid != null) {
            double[] vec = extractVec3(args.arg(worldIndex + 1));
            if (vec != null) return new double[]{vec[0], vec[1], vec[2], Double.NaN};
            return new double[]{
                    args.arg(worldIndex + 1).checkdouble(),
                    args.arg(worldIndex + 2).checkdouble(),
                    args.arg(worldIndex + 3).checkdouble(),
                    Double.NaN
            };
        }

        double[] vec = extractVec3(worldArg);
        if (vec != null) return new double[]{vec[0], vec[1], vec[2], 1.0};
        if (worldArg.isnil()) {
            double[] vecFromNext = extractVec3(args.arg(worldIndex + 1));
            if (vecFromNext != null) return new double[]{vecFromNext[0], vecFromNext[1], vecFromNext[2], 1.0};
        }
        return new double[]{
                args.arg(worldIndex).checkdouble(),
                args.arg(worldIndex + 1).checkdouble(),
                args.arg(worldIndex + 2).checkdouble(),
                1.0
        };
    }

    private record BlockOp(String worldUuid, int x, int y, int z, Object block) {}

    private static int[] extractBlockPos(LuaValue value) {
        if (value == null || value.isnil() || !value.istable()) return null;
        LuaValue x = value.get("x");
        LuaValue y = value.get("y");
        LuaValue z = value.get("z");
        if (x.isnumber() && y.isnumber() && z.isnumber()) {
            return new int[]{
                    (int) Math.floor(x.todouble()),
                    (int) Math.floor(y.todouble()),
                    (int) Math.floor(z.todouble())
            };
        }
        LuaValue one = value.get(1);
        LuaValue two = value.get(2);
        LuaValue three = value.get(3);
        if (one.isnumber() && two.isnumber() && three.isnumber()) {
            return new int[]{
                    (int) Math.floor(one.todouble()),
                    (int) Math.floor(two.todouble()),
                    (int) Math.floor(three.todouble())
            };
        }
        return null;
    }

    private static String readWorldFromTable(LuaValue table, LuaModContext ctx) {
        if (table == null || !table.istable()) return null;
        String worldUuid = extractWorldUuid(table.get("world"), ctx);
        if (worldUuid == null || worldUuid.isBlank()) worldUuid = extractWorldUuid(table.get("worldRef"), ctx);
        if (worldUuid == null || worldUuid.isBlank()) {
            LuaValue raw = table.get("worldUuid");
            if (raw.isstring()) worldUuid = raw.tojstring();
        }
        return worldUuid;
    }

    private static Object readBlockType(LuaValue table) {
        if (table == null || !table.istable()) return null;
        LuaValue block = table.get("block");
        if (block.isnil()) block = table.get("type");
        if (block.isnil()) block = table.get("blockType");
        if (block.isnil()) block = table.get("id");
        if (block.isnil()) block = table.get("blockId");
        if (block.isnil()) block = table.get(4);
        if (block.isnil()) return null;
        return block.isuserdata() ? block.touserdata() : (block.isnumber() ? block.toint() : block.tojstring());
    }

    private static boolean looksAirLike(LuaModContext ctx, Object block) {
        if (block == null) return true;
        Object name = ctx.reflectiveGet(block, "name");
        if (name == null) name = ctx.reflectiveGet(block, "type");
        if (name == null) name = ctx.reflectiveCall(block, "getName");
        if (name == null) name = ctx.reflectiveCall(block, "getType");
        if (name == null) return false;
        String s = String.valueOf(name).trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("air") || s.endsWith(":air") || s.endsWith(".air") || s.contains("_air");
    }

    private static LuaTable createVec3Table() {
        LuaTable vec3 = new LuaTable();
        vec3.set("new", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return vec3Value(
                        args.arg(1).checkdouble(),
                        args.arg(2).checkdouble(),
                        args.arg(3).checkdouble()
                );
            }
        });
        vec3.set("is", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                return LuaValue.valueOf(extractVec3(value) != null);
            }
        });
        vec3.set("unpack", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                double[] v = extractVec3(value);
                if (v == null) return LuaValue.NIL;
                LuaTable out = new LuaTable();
                out.set(1, LuaValue.valueOf(v[0]));
                out.set(2, LuaValue.valueOf(v[1]));
                out.set(3, LuaValue.valueOf(v[2]));
                return out;
            }
        });
        return vec3;
    }

    private static LuaTable createBlockPosTable() {
        LuaTable blockpos = new LuaTable();
        blockpos.set("new", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return blockPosValue(
                        (int) Math.floor(args.arg(1).checkdouble()),
                        (int) Math.floor(args.arg(2).checkdouble()),
                        (int) Math.floor(args.arg(3).checkdouble())
                );
            }
        });
        blockpos.set("is", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                return LuaValue.valueOf(extractVec3(value) != null);
            }
        });
        blockpos.set("unpack", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                double[] v = extractVec3(value);
                if (v == null) return LuaValue.NIL;
                LuaTable out = new LuaTable();
                out.set(1, LuaValue.valueOf((int) Math.floor(v[0])));
                out.set(2, LuaValue.valueOf((int) Math.floor(v[1])));
                out.set(3, LuaValue.valueOf((int) Math.floor(v[2])));
                return out;
            }
        });
        return blockpos;
    }

    private static LuaTable createSpatialTable() {
        LuaTable spatial = new LuaTable();
        spatial.set("directions", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable out = new LuaTable();
                String[] dirs = new String[] { "north", "south", "east", "west", "up", "down" };
                for (int i = 0; i < dirs.length; i++) out.set(i + 1, LuaValue.valueOf(dirs[i]));
                return out;
            }
        });
        spatial.set("opposite", new OneArgFunction() {
            @Override public LuaValue call(LuaValue face) {
                return LuaValue.valueOf(oppositeFace(face.optjstring("")));
            }
        });
        spatial.set("axis", new OneArgFunction() {
            @Override public LuaValue call(LuaValue face) {
                return LuaValue.valueOf(axisOfFace(face.optjstring("")));
            }
        });
        spatial.set("rotateY", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String face = args.arg(1).optjstring("");
                int turns = args.arg(2).optint(1);
                return LuaValue.valueOf(rotateY(face, turns));
            }
        });
        spatial.set("step", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String face = args.arg(1).optjstring("");
                double amount = args.arg(2).optdouble(1.0);
                double[] vec = directionStep(face, amount);
                return vec3Value(vec[0], vec[1], vec[2]);
            }
        });
        spatial.set("add", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue a, LuaValue b) {
                double[] left = extractVec3(a);
                double[] right = extractVec3(b);
                if (left == null || right == null) return LuaValue.NIL;
                return vec3Value(left[0] + right[0], left[1] + right[1], left[2] + right[2]);
            }
        });
        spatial.set("sub", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue a, LuaValue b) {
                double[] left = extractVec3(a);
                double[] right = extractVec3(b);
                if (left == null || right == null) return LuaValue.NIL;
                return vec3Value(left[0] - right[0], left[1] - right[1], left[2] - right[2]);
            }
        });
        spatial.set("scale", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue vec, LuaValue scalar) {
                double[] value = extractVec3(vec);
                if (value == null || !scalar.isnumber()) return LuaValue.NIL;
                double s = scalar.todouble();
                return vec3Value(value[0] * s, value[1] * s, value[2] * s);
            }
        });
        spatial.set("distance", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue a, LuaValue b) {
                double[] left = extractVec3(a);
                double[] right = extractVec3(b);
                if (left == null || right == null) return LuaValue.NIL;
                double dx = left[0] - right[0];
                double dy = left[1] - right[1];
                double dz = left[2] - right[2];
                return LuaValue.valueOf(Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        });
        spatial.set("dominantAxis", new OneArgFunction() {
            @Override public LuaValue call(LuaValue vec) {
                double[] value = extractVec3(vec);
                if (value == null) return LuaValue.NIL;
                double ax = Math.abs(value[0]);
                double ay = Math.abs(value[1]);
                double az = Math.abs(value[2]);
                if (ax >= ay && ax >= az) return LuaValue.valueOf("x");
                if (ay >= ax && ay >= az) return LuaValue.valueOf("y");
                return LuaValue.valueOf("z");
            }
        });
        spatial.set("roundBlock", new OneArgFunction() {
            @Override public LuaValue call(LuaValue vec) {
                double[] value = extractVec3(vec);
                if (value == null) return LuaValue.NIL;
                return blockPosValue((int) Math.floor(value[0]), (int) Math.floor(value[1]), (int) Math.floor(value[2]));
            }
        });
        spatial.set("neighbors", new OneArgFunction() {
            @Override public LuaValue call(LuaValue vec) {
                double[] value = extractVec3(vec);
                if (value == null) return LuaValue.NIL;
                LuaTable out = new LuaTable();
                String[] dirs = new String[] { "north", "south", "east", "west", "up", "down" };
                for (int i = 0; i < dirs.length; i++) {
                    double[] step = directionStep(dirs[i], 1.0);
                    out.set(i + 1, vec3Value(value[0] + step[0], value[1] + step[1], value[2] + step[2]));
                }
                return out;
            }
        });
        return spatial;
    }

    private static LuaTable createQueryTable(LuaModContext ctx) {
        LuaTable query = new LuaTable();
        query.set("withinDistance", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                double[] left = queryPosition(ctx, args.arg(1));
                double[] right = queryPosition(ctx, args.arg(2));
                double radius = args.arg(3).optdouble(-1.0);
                if (left == null || right == null || radius < 0.0) return LuaValue.FALSE;
                return LuaValue.valueOf(distanceSquared(left, right) <= (radius * radius));
            }
        });
        query.set("nearestPlayer", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                double[] origin = queryPosition(ctx, args.arg(1));
                double radius = args.arg(2).optdouble(-1.0);
                String worldUuid = args.narg() >= 3 ? extractWorldUuid(args.arg(3), ctx) : "";
                if (origin == null) return LuaValue.NIL;
                Object best = nearestPlayerRef(ctx, origin, radius, worldUuid);
                return best == null ? LuaValue.NIL : playerPayload(ctx, best);
            }
        });
        query.set("nearestEntity", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                double[] origin = queryPosition(ctx, args.arg(1));
                double radius = args.arg(2).optdouble(-1.0);
                String worldUuid = args.narg() >= 3 ? extractWorldUuid(args.arg(3), ctx) : "";
                if (origin == null) return LuaValue.NIL;
                Object best = nearestEntityRef(ctx, origin, radius, worldUuid);
                return best == null ? LuaValue.NIL : entityPayload(ctx, best);
            }
        });
        query.set("nearestActor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                double[] origin = queryPosition(ctx, args.arg(1));
                double radius = args.arg(2).optdouble(-1.0);
                String kind = args.narg() >= 3 ? args.arg(3).optjstring("") : "";
                if (origin == null) return LuaValue.NIL;
                return nearestMapped(origin, radius, ctx.manager().actors().list(ctx), kind);
            }
        });
        query.set("nearestVolume", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                double[] origin = queryPosition(ctx, args.arg(1));
                double radius = args.arg(2).optdouble(-1.0);
                String kind = args.narg() >= 3 ? args.arg(3).optjstring("") : "";
                if (origin == null) return LuaValue.NIL;
                return nearestMapped(origin, radius, ctx.manager().volumes().list(ctx), kind);
            }
        });
        query.set("nearestNode", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                double[] origin = queryPosition(ctx, args.arg(1));
                double radius = args.arg(2).optdouble(-1.0);
                String kind = args.narg() >= 3 ? args.arg(3).optjstring("") : "";
                if (origin == null) return LuaValue.NIL;
                return nearestMapped(origin, radius, ctx.manager().mechanics().list(ctx), kind);
            }
        });
        return query;
    }

    private static String oppositeFace(String face) {
        String normalized = face == null ? "" : face.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            case "up" -> "down";
            case "down" -> "up";
            default -> normalized;
        };
    }

    private static LuaValue nearestMapped(double[] origin, double radius, java.util.List<java.util.Map<String, Object>> items, String requiredKind) {
        if (origin == null || items == null || items.isEmpty()) return LuaValue.NIL;
        String normalizedKind = requiredKind == null ? "" : requiredKind.trim();
        java.util.Map<String, Object> best = null;
        double bestDistance = Double.MAX_VALUE;
        for (java.util.Map<String, Object> item : items) {
            if (item == null) continue;
            if (!normalizedKind.isEmpty()) {
                Object kind = item.get("kind");
                if (kind == null || !normalizedKind.equalsIgnoreCase(String.valueOf(kind))) continue;
            }
            double[] position = extractMapPosition(item);
            if (position == null) continue;
            double distance = distanceSquared(origin, position);
            if (radius >= 0.0 && distance > (radius * radius)) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = item;
            }
        }
        return best == null ? LuaValue.NIL : javaToLuaValue(best);
    }

    private static String axisOfFace(String face) {
        String normalized = face == null ? "" : face.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "east", "west" -> "x";
            case "up", "down" -> "y";
            default -> "z";
        };
    }

    private static String rotateY(String face, int turns) {
        String normalized = face == null ? "" : face.trim().toLowerCase(java.util.Locale.ROOT);
        String[] order = new String[] { "north", "east", "south", "west" };
        int idx = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(normalized)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return normalized;
        int next = Math.floorMod(idx + turns, order.length);
        return order[next];
    }

    private static double[] directionStep(String face, double amount) {
        String normalized = face == null ? "" : face.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "north" -> new double[] { 0.0, 0.0, -amount };
            case "south" -> new double[] { 0.0, 0.0, amount };
            case "east" -> new double[] { amount, 0.0, 0.0 };
            case "west" -> new double[] { -amount, 0.0, 0.0 };
            case "up" -> new double[] { 0.0, amount, 0.0 };
            case "down" -> new double[] { 0.0, -amount, 0.0 };
            default -> new double[] { 0.0, 0.0, 0.0 };
        };
    }

    static Object luaToJava(LuaValue value) {
        if (value == null || value.isnil()) return null;
        if (value.isboolean()) return value.toboolean();
        if (value.isnumber()) return value.todouble();
        if (value.isstring()) return value.tojstring();
        if (value.isuserdata()) return value.touserdata();
        if (value.istable()) {
            double[] vec = extractVec3(value);
            if (vec != null) {
                java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("x", vec[0]);
                m.put("y", vec[1]);
                m.put("z", vec[2]);
                return m;
            }
            LuaTable t = (LuaTable) value;
            if (isLuaArray(t)) {
                java.util.ArrayList<Object> arr = new java.util.ArrayList<>();
                int len = t.length();
                for (int i = 1; i <= len; i++) {
                    arr.add(luaToJava(t.get(i)));
                }
                return arr;
            }
            java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
            LuaValue k = LuaValue.NIL;
            while (true) {
                Varargs n = t.next(k);
                k = n.arg1();
                if (k.isnil()) break;
                map.put(k.tojstring(), luaToJava(n.arg(2)));
            }
            return map;
        }
        return value.tojstring();
    }

    static LuaValue javaToLuaValue(Object value) {
        if (value == null) return LuaValue.NIL;
        if (value instanceof LuaValue lv) return lv;
        if (value instanceof Boolean b) return LuaValue.valueOf(b);
        if (value instanceof Number n) return LuaValue.valueOf(n.doubleValue());
        if (value instanceof String s) return LuaValue.valueOf(s);
        if (value instanceof Character c) return LuaValue.valueOf(String.valueOf(c));
        if (value instanceof java.util.Map<?, ?> m) {
            LuaTable out = new LuaTable();
            for (var ent : m.entrySet()) {
                out.set(LuaValue.valueOf(String.valueOf(ent.getKey())), javaToLuaValue(ent.getValue()));
            }
            return out;
        }
        if (value instanceof Iterable<?> it) {
            LuaTable out = new LuaTable();
            int i = 1;
            for (Object v : it) out.set(i++, javaToLuaValue(v));
            return out;
        }
        return LuaValue.userdataOf(value);
    }

    private static LuaValue javaToLua(Object value) {
        return javaToLuaValue(value);
    }

    private static boolean isLuaArray(LuaTable table) {
        int len = table.length();
        if (len == 0) return false;
        int count = 0;
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs n = table.next(k);
            k = n.arg1();
            if (k.isnil()) break;
            if (!k.isnumber()) return false;
            double d = k.todouble();
            int idx = (int) d;
            if (idx < 1 || idx > len || idx != d) return false;
            count++;
        }
        return count == len;
    }

    private static JsonElement luaToJsonElement(LuaValue value) {
        if (value == null || value.isnil()) return JsonNull.INSTANCE;
        if (value.isboolean()) return new JsonPrimitive(value.toboolean());
        if (value.isnumber()) return new JsonPrimitive(value.todouble());
        if (value.isstring()) return new JsonPrimitive(value.tojstring());
        if (value.isuserdata()) return new JsonPrimitive(String.valueOf(value.touserdata()));
        if (!value.istable()) return new JsonPrimitive(value.tojstring());

        LuaTable table = (LuaTable) value;
        if (isLuaArray(table)) {
            JsonArray arr = new JsonArray();
            int len = table.length();
            for (int i = 1; i <= len; i++) {
                arr.add(luaToJsonElement(table.get(i)));
            }
            return arr;
        }

        JsonObject obj = new JsonObject();
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs n = table.next(k);
            k = n.arg1();
            if (k.isnil()) break;
            obj.add(k.tojstring(), luaToJsonElement(n.arg(2)));
        }
        return obj;
    }

    private static LuaValue jsonToLua(JsonElement element) {
        if (element == null || element.isJsonNull()) return LuaValue.NIL;
        if (element.isJsonPrimitive()) {
            JsonPrimitive p = element.getAsJsonPrimitive();
            if (p.isBoolean()) return LuaValue.valueOf(p.getAsBoolean());
            if (p.isNumber()) return LuaValue.valueOf(p.getAsDouble());
            return LuaValue.valueOf(p.getAsString());
        }
        if (element.isJsonArray()) {
            LuaTable out = new LuaTable();
            JsonArray arr = element.getAsJsonArray();
            int i = 1;
            for (JsonElement v : arr) out.set(i++, jsonToLua(v));
            return out;
        }
        LuaTable out = new LuaTable();
        JsonObject obj = element.getAsJsonObject();
        for (String key : obj.keySet()) {
            out.set(key, jsonToLua(obj.get(key)));
        }
        return out;
    }

    private static LuaValue deepCopyLuaValue(LuaValue value) {
        if (value == null || value.isnil()) return LuaValue.NIL;
        if (!value.istable()) return value;
        LuaTable src = (LuaTable) value;
        LuaTable copy = new LuaTable();
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs n = src.next(k);
            k = n.arg1();
            if (k.isnil()) break;
            copy.set(k, deepCopyLuaValue(n.arg(2)));
        }
        return copy;
    }

    private static LuaValue mergeWithDefaults(LuaValue base, LuaValue defaults) {
        if (defaults == null || defaults.isnil()) return deepCopyLuaValue(base);
        if (base == null || base.isnil()) return deepCopyLuaValue(defaults);
        if (!base.istable() || !defaults.istable()) return deepCopyLuaValue(base);

        LuaTable merged = (LuaTable) deepCopyLuaValue(base);
        LuaTable defaultsTable = (LuaTable) defaults;
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs n = defaultsTable.next(k);
            k = n.arg1();
            if (k.isnil()) break;
            LuaValue defVal = n.arg(2);
            LuaValue curVal = merged.get(k);
            if (curVal.isnil()) {
                merged.set(k, deepCopyLuaValue(defVal));
            } else {
                merged.set(k, mergeWithDefaults(curVal, defVal));
            }
        }
        return merged;
    }

    private static java.util.Map<String, String> toStringMap(Object value) {
        if (!(value instanceof java.util.Map<?, ?> map)) return java.util.Collections.emptyMap();
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (var ent : map.entrySet()) {
            if (ent.getKey() == null || ent.getValue() == null) continue;
            String k = String.valueOf(ent.getKey()).trim();
            if (k.isEmpty()) continue;
            out.put(k, String.valueOf(ent.getValue()));
        }
        return out;
    }

    private static LuaTable createInteropTable(LuaModContext ctx) {
        LuaTable interop = new LuaTable();
        interop.set("server", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLua(ctx.serverHandle());
            }
        });
        interop.set("universe", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLua(ctx.universeHandle());
            }
        });
        interop.set("defaultWorld", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLua(ctx.defaultWorldHandle());
            }
        });
        interop.set("classExists", new OneArgFunction() {
            @Override public LuaValue call(LuaValue className) {
                return LuaValue.valueOf(ctx.classExists(className.optjstring("")));
            }
        });
        interop.set("resolveClass", new OneArgFunction() {
            @Override public LuaValue call(LuaValue className) {
                Object cls = ctx.resolveClass(className.optjstring(""));
                return cls == null ? LuaValue.NIL : javaToLuaValue(cls);
            }
        });
        interop.set("get", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue target, LuaValue key) {
                if (!target.isuserdata()) return LuaValue.NIL;
                Object value = ctx.reflectiveGet(target.touserdata(), key.checkjstring());
                return javaToLua(value);
            }
        });
        interop.set("set", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaValue target = args.arg(1);
                LuaValue key = args.arg(2);
                LuaValue value = args.arg(3);
                if (!target.isuserdata()) return LuaValue.FALSE;
                boolean ok = ctx.reflectiveSet(target.touserdata(), key.checkjstring(), luaToJava(value));
                return LuaValue.valueOf(ok);
            }
        });
        interop.set("call", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaValue target = args.arg(1);
                if (!target.isuserdata()) return LuaValue.NIL;
                String method = args.arg(2).checkjstring();
                int argc = Math.max(0, args.narg() - 2);
                Object[] jArgs = new Object[argc];
                for (int i = 0; i < argc; i++) {
                    jArgs[i] = luaToJava(args.arg(i + 3));
                }
                Object out = ctx.reflectiveCall(target.touserdata(), method, jArgs);
                return javaToLua(out);
            }
        });
        interop.set("methods", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaValue target = args.arg(1);
                if (!target.isuserdata()) return LuaValue.NIL;
                String prefix = args.narg() >= 2 ? args.arg(2).optjstring("") : "";
                java.util.List<String> methods = ctx.reflectiveMethods(target.touserdata(), prefix);
                LuaTable out = new LuaTable();
                int i = 1;
                for (String m : methods) out.set(i++, LuaValue.valueOf(m));
                return out;
            }
        });
        interop.set("fields", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaValue target = args.arg(1);
                if (!target.isuserdata()) return LuaValue.NIL;
                String prefix = args.narg() >= 2 ? args.arg(2).optjstring("") : "";
                java.util.List<String> fields = ctx.reflectiveFields(target.touserdata(), prefix);
                LuaTable out = new LuaTable();
                int i = 1;
                for (String f : fields) out.set(i++, LuaValue.valueOf(f));
                return out;
            }
        });
        interop.set("describe", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaValue target = args.arg(1);
                if (!target.isuserdata()) return LuaValue.NIL;
                String prefix = args.narg() >= 2 ? args.arg(2).optjstring("") : "";
                return javaToLuaValue(ctx.describeObject(target.touserdata(), prefix));
            }
        });
        interop.set("staticMethods", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String className = args.arg(1).optjstring("");
                String prefix = args.narg() >= 2 ? args.arg(2).optjstring("") : "";
                java.util.List<String> methods = ctx.reflectiveStaticMethods(className, prefix);
                LuaTable out = new LuaTable();
                int i = 1;
                for (String m : methods) out.set(i++, LuaValue.valueOf(m));
                return out;
            }
        });
        interop.set("staticFields", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String className = args.arg(1).optjstring("");
                String prefix = args.narg() >= 2 ? args.arg(2).optjstring("") : "";
                java.util.List<String> fields = ctx.reflectiveStaticFields(className, prefix);
                LuaTable out = new LuaTable();
                int i = 1;
                for (String f : fields) out.set(i++, LuaValue.valueOf(f));
                return out;
            }
        });
        interop.set("describeClass", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String className = args.arg(1).optjstring("");
                String prefix = args.narg() >= 2 ? args.arg(2).optjstring("") : "";
                return javaToLuaValue(ctx.describeClass(className, prefix));
            }
        });
        interop.set("constructors", new OneArgFunction() {
            @Override public LuaValue call(LuaValue className) {
                java.util.List<String> ctors = ctx.reflectiveConstructors(className.optjstring(""));
                LuaTable out = new LuaTable();
                int i = 1;
                for (String c : ctors) out.set(i++, LuaValue.valueOf(c));
                return out;
            }
        });
        interop.set("staticCall", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String className = args.arg(1).optjstring("");
                String method = args.arg(2).checkjstring();
                int argc = Math.max(0, args.narg() - 2);
                Object[] jArgs = new Object[argc];
                for (int i = 0; i < argc; i++) {
                    jArgs[i] = luaToJava(args.arg(i + 3));
                }
                Object out = ctx.reflectiveStaticCall(className, method, jArgs);
                return javaToLua(out);
            }
        });
        interop.set("newInstance", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String className = args.arg(1).optjstring("");
                int argc = Math.max(0, args.narg() - 1);
                Object[] jArgs = new Object[argc];
                for (int i = 0; i < argc; i++) {
                    jArgs[i] = luaToJava(args.arg(i + 2));
                }
                Object out = ctx.reflectiveNewInstance(className, jArgs);
                return javaToLua(out);
            }
        });
        interop.set("typeOf", new OneArgFunction() {
            @Override public LuaValue call(LuaValue target) {
                if (!target.isuserdata()) return LuaValue.NIL;
                Object t = target.touserdata();
                if (t == null) return LuaValue.NIL;
                return LuaValue.valueOf(t.getClass().getName());
            }
        });
        return interop;
    }

    private static Double readNumeric(LuaModContext ctx, Object target, String... keys) {
        for (String key : keys) {
            Object v = ctx.reflectiveGet(target, key);
            if (v instanceof Number n) return n.doubleValue();
        }
        return null;
    }

    private static boolean setNumeric(LuaModContext ctx, Object target, double value, String... keys) {
        for (String key : keys) {
            if (ctx.reflectiveSet(target, key, value) || ctx.reflectiveSet(target, key, (float) value) || ctx.reflectiveSet(target, key, (int) value)) {
                return true;
            }
        }
        return false;
    }

    private static Object callFirst(LuaModContext ctx, Object target, Object arg, String... methods) {
        for (String m : methods) {
            Object out = ctx.reflectiveCall(target, m, arg);
            if (out != null) return out;
        }
        return null;
    }

    private static LuaTable createComponentsTable(LuaModContext ctx) {
        LuaTable components = new LuaTable();
        components.set("health", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.NIL;
                Double v = readNumeric(ctx, ref, "health", "currentHealth", "hp");
                return v == null ? LuaValue.NIL : LuaValue.valueOf(v);
            }
        });
        components.set("setHealth", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue value) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.FALSE;
                double health = value.checkdouble();
                boolean ok = setNumeric(ctx, ref, health, "health", "currentHealth", "hp");
                if (!ok) {
                    Object out = callFirst(ctx, ref, health, "setHealth", "setCurrentHealth", "setHp");
                    ok = out != null;
                }
                return LuaValue.valueOf(ok);
            }
        });
        components.set("maxHealth", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.NIL;
                Double v = readNumeric(ctx, ref, "maxHealth", "maximumHealth", "maxHp");
                return v == null ? LuaValue.NIL : LuaValue.valueOf(v);
            }
        });
        components.set("setMaxHealth", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue value) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.FALSE;
                double max = value.checkdouble();
                boolean ok = setNumeric(ctx, ref, max, "maxHealth", "maximumHealth", "maxHp");
                if (!ok) {
                    Object out = callFirst(ctx, ref, max, "setMaxHealth", "setMaximumHealth", "setMaxHp");
                    ok = out != null;
                }
                return LuaValue.valueOf(ok);
            }
        });
        components.set("alive", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.FALSE;
                Object alive = ctx.reflectiveCall(ref, "isAlive");
                if (alive instanceof Boolean b) return LuaValue.valueOf(b);
                Object removed = ctx.reflectiveCall(ref, "isRemoved");
                if (removed instanceof Boolean b) return LuaValue.valueOf(!b);
                return LuaValue.TRUE;
            }
        });
        components.set("damage", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue amount) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.FALSE;
                double dmg = amount.checkdouble();
                Object out = callFirst(ctx, ref, dmg, "damage", "applyDamage", "hurt");
                if (out != null) return LuaValue.TRUE;
                Double h = readNumeric(ctx, ref, "health", "currentHealth", "hp");
                if (h != null) {
                    boolean ok = setNumeric(ctx, ref, h - dmg, "health", "currentHealth", "hp");
                    return LuaValue.valueOf(ok);
                }
                return LuaValue.FALSE;
            }
        });
        components.set("heal", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue amount) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.FALSE;
                double heal = amount.checkdouble();
                Object out = callFirst(ctx, ref, heal, "heal", "applyHeal");
                if (out != null) return LuaValue.TRUE;
                Double h = readNumeric(ctx, ref, "health", "currentHealth", "hp");
                if (h != null) {
                    boolean ok = setNumeric(ctx, ref, h + heal, "health", "currentHealth", "hp");
                    return LuaValue.valueOf(ok);
                }
                return LuaValue.FALSE;
            }
        });
        components.set("stamina", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.NIL;
                Double v = readNumeric(ctx, ref, "stamina", "energy");
                return v == null ? LuaValue.NIL : LuaValue.valueOf(v);
            }
        });
        components.set("setStamina", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue value) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.FALSE;
                double n = value.checkdouble();
                boolean ok = setNumeric(ctx, ref, n, "stamina", "energy");
                if (!ok) ok = callFirst(ctx, ref, n, "setStamina", "setEnergy") != null;
                return LuaValue.valueOf(ok);
            }
        });
        components.set("hunger", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.NIL;
                Double v = readNumeric(ctx, ref, "hunger", "food", "foodLevel");
                return v == null ? LuaValue.NIL : LuaValue.valueOf(v);
            }
        });
        components.set("setHunger", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue value) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.FALSE;
                double n = value.checkdouble();
                boolean ok = setNumeric(ctx, ref, n, "hunger", "food", "foodLevel");
                if (!ok) ok = callFirst(ctx, ref, n, "setHunger", "setFood", "setFoodLevel") != null;
                return LuaValue.valueOf(ok);
            }
        });
        components.set("mana", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.NIL;
                Double v = readNumeric(ctx, ref, "mana");
                return v == null ? LuaValue.NIL : LuaValue.valueOf(v);
            }
        });
        components.set("setMana", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue value) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.FALSE;
                double n = value.checkdouble();
                boolean ok = setNumeric(ctx, ref, n, "mana");
                if (!ok) ok = callFirst(ctx, ref, n, "setMana") != null;
                return LuaValue.valueOf(ok);
            }
        });
        components.set("tags", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.NIL;
                Object tags = ctx.reflectiveCall(ref, "getTags");
                if (tags == null) tags = ctx.reflectiveGet(ref, "tags");
                return javaToLua(tags);
            }
        });
        components.set("hasTag", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue tag) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.FALSE;
                Object out = callFirst(ctx, ref, tag.optjstring(""), "hasTag", "containsTag");
                if (out instanceof Boolean b) return LuaValue.valueOf(b);
                return LuaValue.FALSE;
            }
        });
        components.set("addTag", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue tag) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.FALSE;
                Object out = callFirst(ctx, ref, tag.optjstring(""), "addTag", "setTag");
                return LuaValue.valueOf(out != null || ctx.reflectiveSet(ref, "tag", tag.optjstring("")));
            }
        });
        components.set("removeTag", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue tag) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.FALSE;
                Object out = callFirst(ctx, ref, tag.optjstring(""), "removeTag", "clearTag");
                return LuaValue.valueOf(out != null);
            }
        });
        components.set("get", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue key) {
                Object ref = extractEntityRef(entity);
                if (ref == null) return LuaValue.NIL;
                Object value = ctx.reflectiveGet(ref, key.checkjstring());
                return javaToLua(value);
            }
        });
        components.set("set", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                if (ref == null) return LuaValue.FALSE;
                String key = args.arg(2).checkjstring();
                Object value = luaToJava(args.arg(3));
                return LuaValue.valueOf(ctx.reflectiveSet(ref, key, value));
            }
        });
        components.set("call", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                if (ref == null) return LuaValue.NIL;
                String method = args.arg(2).checkjstring();
                int argc = Math.max(0, args.narg() - 2);
                Object[] jArgs = new Object[argc];
                for (int i = 0; i < argc; i++) jArgs[i] = luaToJava(args.arg(i + 3));
                return javaToLua(ctx.reflectiveCall(ref, method, jArgs));
            }
        });
        components.set("methods", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                if (ref == null) return LuaValue.NIL;
                String prefix = args.narg() >= 2 ? args.arg(2).optjstring("") : "";
                java.util.List<String> methods = ctx.reflectiveMethods(ref, prefix);
                LuaTable out = new LuaTable();
                int i = 1;
                for (String m : methods) out.set(i++, LuaValue.valueOf(m));
                return out;
            }
        });
        return components;
    }

    private static LuaTable createPlayersTable(LuaModContext ctx) {
        LuaTable players = new LuaTable();
        players.set("send", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue player, LuaValue message) {
                Object ref = extractPlayerRef(player);
                boolean ok = ctx.sendToPlayer(ref, message.optjstring(""));
                return LuaValue.valueOf(ok);
            }
        });
        players.set("name", new OneArgFunction() {
            @Override public LuaValue call(LuaValue player) {
                Object ref = extractPlayerRef(player);
                String name = ctx.playerName(ref);
                return name == null ? LuaValue.NIL : LuaValue.valueOf(name);
            }
        });
        players.set("worldUuid", new OneArgFunction() {
            @Override public LuaValue call(LuaValue player) {
                Object ref = extractPlayerRef(player);
                String worldUuid = ctx.playerWorldUuid(ref);
                return worldUuid == null ? LuaValue.NIL : LuaValue.valueOf(worldUuid);
            }
        });
        players.set("uuid", new OneArgFunction() {
            @Override public LuaValue call(LuaValue player) {
                Object ref = extractPlayerRef(player);
                String uuid = ctx.playerUuid(ref);
                return uuid == null ? LuaValue.NIL : LuaValue.valueOf(uuid);
            }
        });
        players.set("kick", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue player, LuaValue reason) {
                Object ref = extractPlayerRef(player);
                boolean ok = ctx.kickPlayer(ref, reason.optjstring("Disconnected."));
                return LuaValue.valueOf(ok);
            }
        });
        players.set("refer", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractPlayerRef(args.arg(1));
                String host = args.arg(2).checkjstring();
                int port = args.arg(3).checkint();
                boolean ok = ctx.referPlayer(ref, host, port);
                return LuaValue.valueOf(ok);
            }
        });
        players.set("isValid", new OneArgFunction() {
            @Override public LuaValue call(LuaValue player) {
                Object ref = extractPlayerRef(player);
                return LuaValue.valueOf(ctx.isValidPlayer(ref));
            }
        });
        players.set("hasPermission", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue player, LuaValue node) {
                Object ref = extractPlayerRef(player);
                boolean ok = ctx.hasPermission(ref, node.optjstring(""));
                return LuaValue.valueOf(ok);
            }
        });
        players.set("broadcast", new OneArgFunction() {
            @Override public LuaValue call(LuaValue message) {
                int sent = ctx.broadcast(message.optjstring(""));
                return LuaValue.valueOf(sent);
            }
        });
        players.set("broadcastTo", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue targets, LuaValue message) {
                String msg = message.optjstring("");
                int sent = 0;
                if (targets.istable()) {
                    LuaValue key = LuaValue.NIL;
                    LuaTable table = (LuaTable) targets;
                    while (true) {
                        Varargs next = table.next(key);
                        key = next.arg1();
                        if (key.isnil()) break;
                        Object ref = extractPlayerRef(next.arg(2));
                        if (ctx.sendToPlayer(ref, msg)) sent++;
                    }
                } else {
                    Object ref = extractPlayerRef(targets);
                    if (ctx.sendToPlayer(ref, msg)) sent++;
                }
                return LuaValue.valueOf(sent);
            }
        });
        players.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable out = new LuaTable();
                int i = 1;
                for (Object ref : ctx.onlinePlayers()) {
                    out.set(i++, playerPayload(ctx, ref));
                }
                return out;
            }
        });
        players.set("count", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.onlinePlayers().size());
            }
        });
        players.set("findByName", new OneArgFunction() {
            @Override public LuaValue call(LuaValue username) {
                Object ref = ctx.findOnlinePlayerByName(username.optjstring(""));
                if (ref == null) return LuaValue.NIL;
                return playerPayload(ctx, ref);
            }
        });
        players.set("findByUuid", new OneArgFunction() {
            @Override public LuaValue call(LuaValue uuid) {
                Object ref = ctx.findOnlinePlayerByUuid(uuid.optjstring(""));
                if (ref == null) return LuaValue.NIL;
                return playerPayload(ctx, ref);
            }
        });
        players.set("position", new OneArgFunction() {
            @Override public LuaValue call(LuaValue player) {
                Object ref = extractPlayerRef(player);
                double[] pos = ctx.playerPosition(ref);
                if (pos == null || pos.length < 3) return LuaValue.NIL;
                return vec3Value(pos[0], pos[1], pos[2]);
            }
        });
        players.set("teleport", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractPlayerRef(args.arg(1));
                LuaValue worldArg = args.arg(2);
                String worldUuid = extractWorldUuid(worldArg, ctx);
                double[] xyzFlag = extractTeleportArgs(args, 2, ctx);
                double x = xyzFlag[0];
                double y = xyzFlag[1];
                double z = xyzFlag[2];
                boolean ok = ctx.teleportPlayer(ref, worldUuid, x, y, z);
                return LuaValue.valueOf(ok);
            }
        });
        players.set("canMove", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.hasCapability("player-movement"));
            }
        });
        return players;
    }

    private static LuaTable createEntitiesTable(LuaModContext ctx) {
        LuaTable entities = new LuaTable();
        entities.set("list", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = args.narg() >= 1 ? args.arg(1).optjstring("") : "";
                java.util.List<Object> refs = (worldUuid == null || worldUuid.isBlank())
                        ? ctx.entities()
                        : ctx.entitiesInWorld(worldUuid);
                LuaTable out = new LuaTable();
                int i = 1;
                for (Object ref : refs) {
                    out.set(i++, entityPayload(ctx, ref));
                }
                return out;
            }
        });
        entities.set("count", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = args.narg() >= 1 ? args.arg(1).optjstring("") : "";
                int count = (worldUuid == null || worldUuid.isBlank())
                        ? ctx.entities().size()
                        : ctx.entitiesInWorld(worldUuid).size();
                return LuaValue.valueOf(count);
            }
        });
        entities.set("find", new OneArgFunction() {
            @Override public LuaValue call(LuaValue id) {
                Object ref = ctx.findEntityById(id.optjstring(""));
                return ref == null ? LuaValue.NIL : entityPayload(ctx, ref);
            }
        });
        entities.set("id", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                String id = ctx.entityId(ref);
                return id == null ? LuaValue.NIL : LuaValue.valueOf(id);
            }
        });
        entities.set("type", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                String type = ctx.entityType(ref);
                return type == null ? LuaValue.NIL : LuaValue.valueOf(type);
            }
        });
        entities.set("worldUuid", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                String worldUuid = ctx.entityWorldUuid(ref);
                return worldUuid == null ? LuaValue.NIL : LuaValue.valueOf(worldUuid);
            }
        });
        entities.set("isValid", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                return LuaValue.valueOf(ctx.isValidEntity(ref));
            }
        });
        entities.set("remove", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                return LuaValue.valueOf(ctx.removeEntity(ref));
            }
        });
        entities.set("position", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                double[] pos = ctx.entityPosition(ref);
                if (pos == null || pos.length < 3) return LuaValue.NIL;
                return vec3Value(pos[0], pos[1], pos[2]);
            }
        });
        entities.set("teleport", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                LuaValue worldArg = args.arg(2);
                String worldUuid = extractWorldUuid(worldArg, ctx);
                double[] xyzFlag = extractTeleportArgs(args, 2, ctx);
                double x = xyzFlag[0];
                double y = xyzFlag[1];
                double z = xyzFlag[2];
                boolean ok = ctx.teleportEntity(ref, worldUuid, x, y, z);
                return LuaValue.valueOf(ok);
            }
        });
        entities.set("rotation", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                double[] rot = ctx.entityRotation(ref);
                if (rot == null || rot.length < 2) return LuaValue.NIL;
                LuaTable out = new LuaTable();
                out.set("yaw", LuaValue.valueOf(rot[0]));
                out.set("pitch", LuaValue.valueOf(rot[1]));
                out.set("roll", LuaValue.valueOf(rot.length >= 3 ? rot[2] : 0.0));
                return out;
            }
        });
        entities.set("setRotation", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                LuaValue rotArg = args.arg(2);
                double yaw, pitch, roll;
                if (rotArg.istable()) {
                    yaw = rotArg.get("yaw").optdouble(rotArg.get("x").optdouble(0.0));
                    pitch = rotArg.get("pitch").optdouble(rotArg.get("y").optdouble(0.0));
                    roll = rotArg.get("roll").optdouble(rotArg.get("z").optdouble(0.0));
                } else {
                    yaw = args.arg(2).checkdouble();
                    pitch = args.arg(3).checkdouble();
                    roll = args.arg(4).optdouble(0.0);
                }
                return LuaValue.valueOf(ctx.setEntityRotation(ref, yaw, pitch, roll));
            }
        });
        entities.set("velocity", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                double[] vel = ctx.entityVelocity(ref);
                if (vel == null || vel.length < 3) return LuaValue.NIL;
                return vec3Value(vel[0], vel[1], vel[2]);
            }
        });
        entities.set("setVelocity", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                double[] vec = extractVec3(args.arg(2));
                double x, y, z;
                if (vec != null) {
                    x = vec[0]; y = vec[1]; z = vec[2];
                } else {
                    x = args.arg(2).checkdouble();
                    y = args.arg(3).checkdouble();
                    z = args.arg(4).checkdouble();
                }
                return LuaValue.valueOf(ctx.setEntityVelocity(ref, x, y, z));
            }
        });
        entities.set("near", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                double x = args.arg(2).checkdouble();
                double y = args.arg(3).checkdouble();
                double z = args.arg(4).checkdouble();
                double radius = args.arg(5).checkdouble();
                java.util.List<Object> refs = ctx.entitiesNear(worldUuid, x, y, z, radius);
                LuaTable out = new LuaTable();
                int i = 1;
                for (Object ref : refs) out.set(i++, entityPayload(ctx, ref));
                return out;
            }
        });
        entities.set("inventory", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                LuaTable out = new LuaTable();
                int i = 1;
                for (Object item : ctx.entityInventory(ref)) out.set(i++, javaToLua(item));
                return out;
            }
        });
        entities.set("equipment", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                Object slot = args.narg() >= 2 && !args.arg(2).isnil() ? luaToJava(args.arg(2)) : null;
                return javaToLua(ctx.entityEquipment(ref, slot));
            }
        });
        entities.set("setEquipment", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                Object slot = luaToJava(args.arg(2));
                Object item = luaToJava(args.arg(3));
                return LuaValue.valueOf(ctx.entitySetEquipment(ref, slot, item));
            }
        });
        entities.set("giveItem", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                LuaValue item = args.arg(2);
                int count = args.arg(3).optint(1);
                Object it = item.isuserdata() ? item.touserdata() : item.optjstring("");
                return LuaValue.valueOf(ctx.entityGiveItem(ref, it, count));
            }
        });
        entities.set("takeItem", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                LuaValue item = args.arg(2);
                int count = args.arg(3).optint(1);
                Object it = item.isuserdata() ? item.touserdata() : item.optjstring("");
                return LuaValue.valueOf(ctx.entityTakeItem(ref, it, count));
            }
        });
        entities.set("effects", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                LuaTable out = new LuaTable();
                int i = 1;
                for (Object effect : ctx.entityEffects(ref)) out.set(i++, javaToLua(effect));
                return out;
            }
        });
        entities.set("addEffect", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                LuaValue effect = args.arg(2);
                int duration = args.arg(3).optint(200);
                int amplifier = args.arg(4).optint(0);
                Object ef = effect.isuserdata() ? effect.touserdata() : effect.optjstring("");
                return LuaValue.valueOf(ctx.entityAddEffect(ref, ef, duration, amplifier));
            }
        });
        entities.set("removeEffect", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue effect) {
                Object ref = extractEntityRef(entity);
                Object ef = effect.isuserdata() ? effect.touserdata() : effect.optjstring("");
                return LuaValue.valueOf(ctx.entityRemoveEffect(ref, ef));
            }
        });
        entities.set("attribute", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue entity, LuaValue name) {
                Object ref = extractEntityRef(entity);
                return javaToLua(ctx.entityAttribute(ref, name.optjstring("")));
            }
        });
        entities.set("setAttribute", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                String name = args.arg(2).optjstring("");
                double value = args.arg(3).checkdouble();
                return LuaValue.valueOf(ctx.entitySetAttribute(ref, name, value));
            }
        });
        entities.set("pathTo", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractEntityRef(args.arg(1));
                LuaValue worldArg = args.arg(2);
                String worldUuid = extractWorldUuid(worldArg, ctx);
                double[] xyzFlag = extractTeleportArgs(args, 2, ctx);
                boolean ok = ctx.entityPathTo(ref, worldUuid, xyzFlag[0], xyzFlag[1], xyzFlag[2]);
                return LuaValue.valueOf(ok);
            }
        });
        entities.set("stopPath", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                Object ref = extractEntityRef(entity);
                return LuaValue.valueOf(ctx.entityStopPath(ref));
            }
        });
        entities.set("spawn", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaValue typeArg = args.arg(1);
                LuaValue worldArg = args.arg(2);
                String worldUuid = extractWorldUuid(worldArg, ctx);
                int posStart = (worldUuid == null && (worldArg.isnumber() || worldArg.istable() || worldArg.isnil())) ? 2 : 3;

                double[] vec = extractVec3(args.arg(posStart));
                double x, y, z;
                int rotStart;
                if (vec != null) {
                    x = vec[0]; y = vec[1]; z = vec[2];
                    rotStart = posStart + 1;
                } else {
                    x = args.arg(posStart).checkdouble();
                    y = args.arg(posStart + 1).checkdouble();
                    z = args.arg(posStart + 2).checkdouble();
                    rotStart = posStart + 3;
                }
                double yaw = args.arg(rotStart).optdouble(0.0);
                double pitch = args.arg(rotStart + 1).optdouble(0.0);
                double roll = args.arg(rotStart + 2).optdouble(0.0);

                Object world = (worldUuid == null || worldUuid.isBlank()) ? ctx.defaultWorld() : ctx.findWorldByUuid(worldUuid);
                Object typeOrPrototype = typeArg.isuserdata() ? typeArg.touserdata() : typeArg.optjstring("");
                Object spawned = ctx.spawnEntity(world, typeOrPrototype, x, y, z, yaw, pitch, roll);
                if (spawned == null) return LuaValue.NIL;
                return entityPayload(ctx, spawned);
            }
        });
        entities.set("canControl", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.hasCapability("entity-control"));
            }
        });
        return entities;
    }

    private static LuaTable createActorsTable(LuaModContext ctx) {
        LuaTable actors = new LuaTable();
        actors.set("spawn", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaValue typeArg = args.arg(1);
                LuaValue worldArg = args.arg(2);
                String worldUuid = extractWorldUuid(worldArg, ctx);
                int posStart = (worldUuid == null && (worldArg.isnumber() || worldArg.istable() || worldArg.isnil())) ? 2 : 3;

                double[] vec = extractVec3(args.arg(posStart));
                double x, y, z;
                int rotStart;
                if (vec != null) {
                    x = vec[0]; y = vec[1]; z = vec[2];
                    rotStart = posStart + 1;
                } else {
                    x = args.arg(posStart).checkdouble();
                    y = args.arg(posStart + 1).checkdouble();
                    z = args.arg(posStart + 2).checkdouble();
                    rotStart = posStart + 3;
                }
                double yaw = args.arg(rotStart).optdouble(0.0);
                double pitch = args.arg(rotStart + 1).optdouble(0.0);
                double roll = args.arg(rotStart + 2).optdouble(0.0);
                LuaTable options = args.arg(rotStart + 3).istable() ? (LuaTable) args.arg(rotStart + 3) : new LuaTable();

                Object typeOrPrototype = typeArg.isuserdata() ? typeArg.touserdata() : typeArg.optjstring("");
                Object actor = ctx.manager().actors().spawn(ctx, typeOrPrototype, worldUuid, x, y, z, yaw, pitch, roll, options);
                return javaToLuaValue(actor);
            }
        });
        actors.set("bind", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object entity = extractEntityRef(args.arg(1));
                LuaTable options = args.arg(2).istable() ? (LuaTable) args.arg(2) : new LuaTable();
                return javaToLuaValue(ctx.manager().actors().bind(ctx, entity, options));
            }
        });
        actors.set("exists", new OneArgFunction() {
            @Override public LuaValue call(LuaValue actorId) {
                return LuaValue.valueOf(ctx.manager().actors().exists(ctx.modId(), actorId.optjstring("")));
            }
        });
        actors.set("find", new OneArgFunction() {
            @Override public LuaValue call(LuaValue actorId) {
                return javaToLuaValue(ctx.manager().actors().find(ctx, actorId.optjstring("")));
            }
        });
        actors.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLuaValue(ctx.manager().actors().list(ctx));
            }
        });
        actors.set("listByKind", new OneArgFunction() {
            @Override public LuaValue call(LuaValue kind) {
                return javaToLuaValue(ctx.manager().actors().listByKind(ctx, kind.optjstring("")));
            }
        });
        actors.set("listByTag", new OneArgFunction() {
            @Override public LuaValue call(LuaValue tag) {
                return javaToLuaValue(ctx.manager().actors().listByTag(ctx, tag.optjstring("")));
            }
        });
        actors.set("entity", new OneArgFunction() {
            @Override public LuaValue call(LuaValue actorId) {
                return javaToLuaValue(ctx.manager().actors().entity(ctx.modId(), actorId.optjstring("")));
            }
        });
        actors.set("findByEntity", new OneArgFunction() {
            @Override public LuaValue call(LuaValue entity) {
                return javaToLuaValue(ctx.manager().actors().findByEntity(ctx, extractEntityRef(entity)));
            }
        });
        actors.set("state", new OneArgFunction() {
            @Override public LuaValue call(LuaValue actorId) {
                LuaTable state = ctx.manager().actors().state(ctx.modId(), actorId.optjstring(""));
                return state == null ? LuaValue.NIL : state;
            }
        });
        actors.set("setState", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue actorId, LuaValue state) {
                if (!state.istable()) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().actors().setState(ctx.modId(), actorId.optjstring(""), (LuaTable) state));
            }
        });
        actors.set("setKind", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue actorId, LuaValue kind) {
                return LuaValue.valueOf(ctx.manager().actors().setKind(ctx.modId(), actorId.optjstring(""), kind.optjstring("")));
            }
        });
        actors.set("setTags", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue actorId, LuaValue tags) {
                if (!tags.istable()) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().actors().setTags(ctx.modId(), actorId.optjstring(""), (LuaTable) tags));
            }
        });
        actors.set("remove", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String actorId = args.arg(1).optjstring("");
                boolean despawn = args.arg(2).optboolean(false);
                return LuaValue.valueOf(ctx.manager().actors().remove(ctx, actorId, despawn, "manual"));
            }
        });
        return actors;
    }

    private static LuaTable createVolumesTable(LuaModContext ctx) {
        LuaTable volumes = new LuaTable();
        volumes.set("box", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int start = worldUuid == null ? 1 : 2;
                double minX = args.arg(start).checkdouble();
                double minY = args.arg(start + 1).checkdouble();
                double minZ = args.arg(start + 2).checkdouble();
                double maxX = args.arg(start + 3).checkdouble();
                double maxY = args.arg(start + 4).checkdouble();
                double maxZ = args.arg(start + 5).checkdouble();
                LuaTable options = args.arg(start + 6).istable() ? (LuaTable) args.arg(start + 6) : new LuaTable();
                return javaToLuaValue(ctx.manager().volumes().addBox(ctx, worldUuid, minX, minY, minZ, maxX, maxY, maxZ, options));
            }
        });
        volumes.set("sphere", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int start = worldUuid == null ? 1 : 2;
                double x = args.arg(start).checkdouble();
                double y = args.arg(start + 1).checkdouble();
                double z = args.arg(start + 2).checkdouble();
                double radius = args.arg(start + 3).checkdouble();
                LuaTable options = args.arg(start + 4).istable() ? (LuaTable) args.arg(start + 4) : new LuaTable();
                return javaToLuaValue(ctx.manager().volumes().addSphere(ctx, worldUuid, x, y, z, radius, options));
            }
        });
        volumes.set("find", new OneArgFunction() {
            @Override public LuaValue call(LuaValue volumeId) {
                return javaToLuaValue(ctx.manager().volumes().find(ctx, volumeId.optjstring("")));
            }
        });
        volumes.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLuaValue(ctx.manager().volumes().list(ctx));
            }
        });
        volumes.set("listByKind", new OneArgFunction() {
            @Override public LuaValue call(LuaValue kind) {
                return javaToLuaValue(ctx.manager().volumes().listByKind(ctx, kind.optjstring("")));
            }
        });
        volumes.set("listByTag", new OneArgFunction() {
            @Override public LuaValue call(LuaValue tag) {
                return javaToLuaValue(ctx.manager().volumes().listByTag(ctx, tag.optjstring("")));
            }
        });
        volumes.set("state", new OneArgFunction() {
            @Override public LuaValue call(LuaValue volumeId) {
                LuaTable state = ctx.manager().volumes().state(ctx.modId(), volumeId.optjstring(""));
                return state == null ? LuaValue.NIL : state;
            }
        });
        volumes.set("containsPoint", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String volumeId = args.arg(1).optjstring("");
                double x = args.arg(2).checkdouble();
                double y = args.arg(3).checkdouble();
                double z = args.arg(4).checkdouble();
                return LuaValue.valueOf(ctx.manager().volumes().containsPoint(ctx.modId(), volumeId, x, y, z));
            }
        });
        volumes.set("containsEntity", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String volumeId = args.arg(1).optjstring("");
                Object entity = extractEntityRef(args.arg(2));
                return LuaValue.valueOf(ctx.manager().volumes().containsEntity(ctx, volumeId, entity));
            }
        });
        volumes.set("containsPlayer", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String volumeId = args.arg(1).optjstring("");
                Object player = extractPlayerRef(args.arg(2));
                return LuaValue.valueOf(ctx.manager().volumes().containsPlayer(ctx, volumeId, player));
            }
        });
        volumes.set("setState", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue volumeId, LuaValue state) {
                if (!state.istable()) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().volumes().setState(ctx.modId(), volumeId.optjstring(""), (LuaTable) state));
            }
        });
        volumes.set("move", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String volumeId = args.arg(1).optjstring("");
                double[] vec = extractVec3(args.arg(2));
                if (vec == null) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().volumes().moveCenter(ctx.modId(), volumeId, vec[0], vec[1], vec[2]));
            }
        });
        volumes.set("remove", new OneArgFunction() {
            @Override public LuaValue call(LuaValue volumeId) {
                return LuaValue.valueOf(ctx.manager().volumes().remove(ctx, volumeId.optjstring(""), "manual"));
            }
        });
        volumes.set("clear", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.manager().volumes().clear(ctx));
            }
        });
        return volumes;
    }

    private static LuaTable createMechanicsTable(LuaModContext ctx) {
        LuaTable mechanics = new LuaTable();
        mechanics.set("register", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int start = worldUuid == null ? 1 : 2;
                double x = args.arg(start).checkdouble();
                double y = args.arg(start + 1).checkdouble();
                double z = args.arg(start + 2).checkdouble();
                LuaTable options = args.arg(start + 3).istable() ? (LuaTable) args.arg(start + 3) : new LuaTable();
                return javaToLuaValue(ctx.manager().mechanics().register(ctx, worldUuid, x, y, z, options));
            }
        });
        mechanics.set("unregister", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nodeId) {
                return LuaValue.valueOf(ctx.manager().mechanics().unregister(ctx.modId(), nodeId.optjstring("")));
            }
        });
        mechanics.set("clear", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.manager().mechanics().clear(ctx.modId()));
            }
        });
        mechanics.set("find", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nodeId) {
                return javaToLuaValue(ctx.manager().mechanics().find(ctx, nodeId.optjstring("")));
            }
        });
        mechanics.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLuaValue(ctx.manager().mechanics().list(ctx));
            }
        });
        mechanics.set("listByKind", new OneArgFunction() {
            @Override public LuaValue call(LuaValue kind) {
                return javaToLuaValue(ctx.manager().mechanics().listByKind(ctx, kind.optjstring("")));
            }
        });
        mechanics.set("listByTag", new OneArgFunction() {
            @Override public LuaValue call(LuaValue tag) {
                return javaToLuaValue(ctx.manager().mechanics().listByTag(ctx, tag.optjstring("")));
            }
        });
        mechanics.set("neighborsOf", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nodeId) {
                return javaToLuaValue(ctx.manager().mechanics().neighborsOf(ctx, nodeId.optjstring("")));
            }
        });
        mechanics.set("link", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String fromNodeId = args.arg(1).optjstring("");
                String toNodeId = args.arg(2).optjstring("");
                LuaTable options = args.arg(3).istable() ? (LuaTable) args.arg(3) : new LuaTable();
                return javaToLuaValue(ctx.manager().mechanics().connect(ctx, fromNodeId, toNodeId, options));
            }
        });
        mechanics.set("unlink", new OneArgFunction() {
            @Override public LuaValue call(LuaValue linkId) {
                return LuaValue.valueOf(ctx.manager().mechanics().disconnect(ctx.modId(), linkId.optjstring("")));
            }
        });
        mechanics.set("links", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLuaValue(ctx.manager().mechanics().links(ctx));
            }
        });
        mechanics.set("linksOf", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nodeId) {
                return javaToLuaValue(ctx.manager().mechanics().linksOf(ctx, nodeId.optjstring("")));
            }
        });
        mechanics.set("findLink", new OneArgFunction() {
            @Override public LuaValue call(LuaValue linkId) {
                return javaToLuaValue(ctx.manager().mechanics().findLink(ctx, linkId.optjstring("")));
            }
        });
        mechanics.set("componentOf", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nodeId) {
                return javaToLuaValue(ctx.manager().mechanics().componentOf(ctx, nodeId.optjstring("")));
            }
        });
        mechanics.set("path", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue fromNodeId, LuaValue toNodeId) {
                return javaToLuaValue(ctx.manager().mechanics().path(ctx, fromNodeId.optjstring(""), toNodeId.optjstring("")));
            }
        });
        mechanics.set("markDirty", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nodeId) {
                return LuaValue.valueOf(ctx.manager().mechanics().markDirty(ctx.modId(), nodeId.optjstring("")));
            }
        });
        mechanics.set("dirtyNodes", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLuaValue(ctx.manager().mechanics().dirtyNodes(ctx.modId()));
            }
        });
        mechanics.set("state", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nodeId) {
                LuaTable state = ctx.manager().mechanics().state(ctx.modId(), nodeId.optjstring(""));
                return state == null ? LuaValue.NIL : state;
            }
        });
        mechanics.set("setState", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue nodeId, LuaValue state) {
                if (!state.istable()) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().mechanics().setState(ctx.modId(), nodeId.optjstring(""), (LuaTable) state));
            }
        });
        mechanics.set("move", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String nodeId = args.arg(1).optjstring("");
                double[] vec = extractVec3(args.arg(2));
                if (vec == null) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().mechanics().move(ctx.modId(), nodeId, vec[0], vec[1], vec[2]));
            }
        });
        return mechanics;
    }

    private static LuaTable createSimulationTable(LuaModContext ctx) {
        LuaTable sim = new LuaTable();
        sim.set("register", new OneArgFunction() {
            @Override public LuaValue call(LuaValue options) {
                if (!options.istable()) return LuaValue.NIL;
                return javaToLuaValue(ctx.manager().sim().register(ctx, (LuaTable) options));
            }
        });
        sim.set("find", new OneArgFunction() {
            @Override public LuaValue call(LuaValue systemId) {
                return javaToLuaValue(ctx.manager().sim().find(ctx, systemId.optjstring("")));
            }
        });
        sim.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLuaValue(ctx.manager().sim().list(ctx));
            }
        });
        sim.set("listByKind", new OneArgFunction() {
            @Override public LuaValue call(LuaValue kind) {
                return javaToLuaValue(ctx.manager().sim().listByKind(ctx, kind.optjstring("")));
            }
        });
        sim.set("listByTag", new OneArgFunction() {
            @Override public LuaValue call(LuaValue tag) {
                return javaToLuaValue(ctx.manager().sim().listByTag(ctx, tag.optjstring("")));
            }
        });
        sim.set("state", new OneArgFunction() {
            @Override public LuaValue call(LuaValue systemId) {
                LuaTable state = ctx.manager().sim().state(ctx.modId(), systemId.optjstring(""));
                return state == null ? LuaValue.NIL : state;
            }
        });
        sim.set("setState", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue systemId, LuaValue state) {
                if (!state.istable()) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().sim().setState(ctx.modId(), systemId.optjstring(""), (LuaTable) state));
            }
        });
        sim.set("markDirty", new OneArgFunction() {
            @Override public LuaValue call(LuaValue systemId) {
                return LuaValue.valueOf(ctx.manager().sim().markDirty(ctx.modId(), systemId.optjstring("")));
            }
        });
        sim.set("unregister", new OneArgFunction() {
            @Override public LuaValue call(LuaValue systemId) {
                return LuaValue.valueOf(ctx.manager().sim().unregister(ctx.modId(), systemId.optjstring("")));
            }
        });
        sim.set("clear", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.manager().sim().clear(ctx.modId()));
            }
        });
        return sim;
    }

    private static LuaTable createRegistryTable(LuaModContext ctx) {
        LuaTable registry = new LuaTable();
        registry.set("define", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String registryId = args.arg(1).optjstring("");
                Object options = luaToJava(args.arg(2));
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = options instanceof java.util.Map<?, ?> ? (java.util.Map<String, Object>) options : java.util.Collections.emptyMap();
                return javaToLuaValue(ctx.manager().registry().define(ctx, registryId, map));
            }
        });
        registry.set("find", new OneArgFunction() {
            @Override public LuaValue call(LuaValue registryId) {
                return javaToLuaValue(ctx.manager().registry().find(ctx, registryId.optjstring("")));
            }
        });
        registry.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLuaValue(ctx.manager().registry().list(ctx));
            }
        });
        registry.set("listByKind", new OneArgFunction() {
            @Override public LuaValue call(LuaValue kind) {
                return javaToLuaValue(ctx.manager().registry().listByKind(ctx, kind.optjstring("")));
            }
        });
        registry.set("kinds", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLuaValue(ctx.manager().registry().kinds(ctx));
            }
        });
        registry.set("put", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String registryId = args.arg(1).optjstring("");
                String key = args.arg(2).optjstring("");
                return LuaValue.valueOf(ctx.manager().registry().put(ctx.modId(), registryId, key, luaToJava(args.arg(3))));
            }
        });
        registry.set("get", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue registryId, LuaValue key) {
                return javaToLuaValue(ctx.manager().registry().get(ctx.modId(), registryId.optjstring(""), key.optjstring("")));
            }
        });
        registry.set("has", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue registryId, LuaValue key) {
                return LuaValue.valueOf(ctx.manager().registry().has(ctx.modId(), registryId.optjstring(""), key.optjstring("")));
            }
        });
        registry.set("size", new OneArgFunction() {
            @Override public LuaValue call(LuaValue registryId) {
                return LuaValue.valueOf(ctx.manager().registry().size(ctx.modId(), registryId.optjstring("")));
            }
        });
        registry.set("entries", new OneArgFunction() {
            @Override public LuaValue call(LuaValue registryId) {
                return javaToLuaValue(ctx.manager().registry().entries(ctx.modId(), registryId.optjstring("")));
            }
        });
        registry.set("keys", new OneArgFunction() {
            @Override public LuaValue call(LuaValue registryId) {
                return javaToLuaValue(ctx.manager().registry().keys(ctx.modId(), registryId.optjstring("")));
            }
        });
        registry.set("removeEntry", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue registryId, LuaValue key) {
                return LuaValue.valueOf(ctx.manager().registry().removeEntry(ctx.modId(), registryId.optjstring(""), key.optjstring("")));
            }
        });
        registry.set("remove", new OneArgFunction() {
            @Override public LuaValue call(LuaValue registryId) {
                return LuaValue.valueOf(ctx.manager().registry().remove(ctx.modId(), registryId.optjstring("")));
            }
        });
        registry.set("clear", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.manager().registry().clear(ctx.modId()));
            }
        });
        return registry;
    }

    private static String storePath(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new LuaError("store name is required");
        }
        String normalized = trimmed.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw new LuaError("invalid store name");
        }
        if (!normalized.endsWith(".json")) {
            normalized = normalized + ".json";
        }
        return "stores/" + normalized;
    }

    private static LuaValue loadJsonValue(LuaModContext ctx, String path, LuaValue defaults, boolean writeBack) throws Exception {
        LuaValue loaded = LuaValue.NIL;
        if (ctx.exists(path)) {
            String text = ctx.readText(path);
            if (text != null && !text.isBlank()) {
                loaded = jsonToLua(JsonParser.parseString(text));
            }
        }
        LuaValue merged = defaults.isnil() ? loaded : mergeWithDefaults(loaded, defaults);
        if (writeBack && !merged.isnil()) {
            writeJsonValue(ctx, path, merged, true);
        }
        return merged;
    }

    private static void writeJsonValue(LuaModContext ctx, String path, LuaValue value, boolean pretty) throws Exception {
        JsonElement json = luaToJsonElement(value);
        String out = (pretty ? PRETTY_GSON : GSON).toJson(json);
        ctx.writeText(path, out + System.lineSeparator());
    }

    private static LuaTable createTransformsTable(LuaModContext ctx) {
        LuaTable transforms = new LuaTable();
        transforms.set("create", new OneArgFunction() {
            @Override public LuaValue call(LuaValue options) {
                if (!options.istable()) return LuaValue.NIL;
                return javaToLuaValue(ctx.manager().transforms().create(ctx, (LuaTable) options));
            }
        });
        transforms.set("find", new OneArgFunction() {
            @Override public LuaValue call(LuaValue transformId) {
                return javaToLuaValue(ctx.manager().transforms().find(ctx, transformId.optjstring("")));
            }
        });
        transforms.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLuaValue(ctx.manager().transforms().list(ctx));
            }
        });
        transforms.set("state", new OneArgFunction() {
            @Override public LuaValue call(LuaValue transformId) {
                LuaTable state = ctx.manager().transforms().state(ctx.modId(), transformId.optjstring(""));
                return state == null ? LuaValue.NIL : state;
            }
        });
        transforms.set("setState", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue transformId, LuaValue state) {
                if (!state.istable()) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().transforms().setState(ctx.modId(), transformId.optjstring(""), (LuaTable) state));
            }
        });
        transforms.set("setParent", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue transformId, LuaValue parentId) {
                return LuaValue.valueOf(ctx.manager().transforms().setParent(ctx.modId(), transformId.optjstring(""), parentId.optjstring("")));
            }
        });
        transforms.set("setPosition", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String transformId = args.arg(1).optjstring("");
                double[] vec = extractVec3(args.arg(2));
                if (vec == null) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().transforms().setPosition(ctx.modId(), transformId, vec[0], vec[1], vec[2]));
            }
        });
        transforms.set("setRotation", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String transformId = args.arg(1).optjstring("");
                double[] vec = extractVec3(args.arg(2));
                if (vec == null) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().transforms().setRotation(ctx.modId(), transformId, vec[0], vec[1], vec[2]));
            }
        });
        transforms.set("bindActor", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue transformId, LuaValue actorId) {
                return LuaValue.valueOf(ctx.manager().transforms().bindActor(ctx.modId(), transformId.optjstring(""), actorId.optjstring("")));
            }
        });
        transforms.set("bindEntity", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue transformId, LuaValue entity) {
                return LuaValue.valueOf(ctx.manager().transforms().bindEntity(ctx.modId(), transformId.optjstring(""), extractEntityRef(entity)));
            }
        });
        transforms.set("resolve", new OneArgFunction() {
            @Override public LuaValue call(LuaValue transformId) {
                return javaToLuaValue(ctx.manager().transforms().resolve(ctx, transformId.optjstring("")));
            }
        });
        transforms.set("remove", new OneArgFunction() {
            @Override public LuaValue call(LuaValue transformId) {
                return LuaValue.valueOf(ctx.manager().transforms().remove(ctx.modId(), transformId.optjstring("")));
            }
        });
        transforms.set("clear", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.manager().transforms().clear(ctx.modId()));
            }
        });
        return transforms;
    }

    private static LuaTable createStandInsTable(LuaModContext ctx) {
        LuaTable standins = new LuaTable();
        standins.set("create", new OneArgFunction() {
            @Override public LuaValue call(LuaValue options) {
                if (!options.istable()) return LuaValue.NIL;
                return javaToLuaValue(ctx.manager().standins().create(ctx, (LuaTable) options));
            }
        });
        standins.set("spawn", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object typeOrPrototype = luaToJava(args.arg(1));
                LuaTable options = args.arg(2).istable() ? (LuaTable) args.arg(2) : new LuaTable();
                return javaToLuaValue(ctx.manager().standins().spawn(ctx, typeOrPrototype, options));
            }
        });
        standins.set("find", new OneArgFunction() {
            @Override public LuaValue call(LuaValue standinId) {
                return javaToLuaValue(ctx.manager().standins().find(ctx, standinId.optjstring("")));
            }
        });
        standins.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLuaValue(ctx.manager().standins().list(ctx));
            }
        });
        standins.set("count", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.manager().standins().count(ctx));
            }
        });
        standins.set("listByKind", new OneArgFunction() {
            @Override public LuaValue call(LuaValue kind) {
                return javaToLuaValue(ctx.manager().standins().listByKind(ctx, kind.optjstring("")));
            }
        });
        standins.set("listByTag", new OneArgFunction() {
            @Override public LuaValue call(LuaValue tag) {
                return javaToLuaValue(ctx.manager().standins().listByTag(ctx, tag.optjstring("")));
            }
        });
        standins.set("state", new OneArgFunction() {
            @Override public LuaValue call(LuaValue standinId) {
                LuaTable state = ctx.manager().standins().state(ctx.modId(), standinId.optjstring(""));
                return state == null ? LuaValue.NIL : state;
            }
        });
        standins.set("setState", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue standinId, LuaValue state) {
                if (!state.istable()) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().standins().setState(ctx.modId(), standinId.optjstring(""), (LuaTable) state));
            }
        });
        standins.set("resolve", new OneArgFunction() {
            @Override public LuaValue call(LuaValue standinId) {
                return javaToLuaValue(ctx.manager().standins().resolve(ctx, standinId.optjstring("")));
            }
        });
        standins.set("actor", new OneArgFunction() {
            @Override public LuaValue call(LuaValue standinId) {
                return javaToLuaValue(ctx.manager().standins().actor(ctx, standinId.optjstring("")));
            }
        });
        standins.set("transform", new OneArgFunction() {
            @Override public LuaValue call(LuaValue standinId) {
                return javaToLuaValue(ctx.manager().standins().transform(ctx, standinId.optjstring("")));
            }
        });
        standins.set("volume", new OneArgFunction() {
            @Override public LuaValue call(LuaValue standinId) {
                return javaToLuaValue(ctx.manager().standins().volume(ctx, standinId.optjstring("")));
            }
        });
        standins.set("node", new OneArgFunction() {
            @Override public LuaValue call(LuaValue standinId) {
                return javaToLuaValue(ctx.manager().standins().node(ctx, standinId.optjstring("")));
            }
        });
        standins.set("has", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue standinId, LuaValue component) {
                return LuaValue.valueOf(ctx.manager().standins().hasComponent(ctx.modId(), standinId.optjstring(""), component.optjstring("")));
            }
        });
        standins.set("move", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String standinId = args.arg(1).optjstring("");
                double[] vec = extractVec3(args.arg(2));
                if (vec == null) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().standins().move(ctx, standinId, vec[0], vec[1], vec[2]));
            }
        });
        standins.set("rotate", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String standinId = args.arg(1).optjstring("");
                double[] vec = extractVec3(args.arg(2));
                if (vec == null) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().standins().rotate(ctx, standinId, vec[0], vec[1], vec[2]));
            }
        });
        standins.set("attach", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue childId, LuaValue parentId) {
                return LuaValue.valueOf(ctx.manager().standins().attach(ctx.modId(), childId.optjstring(""), parentId.optjstring("")));
            }
        });
        standins.set("remove", new OneArgFunction() {
            @Override public LuaValue call(LuaValue standinId) {
                return LuaValue.valueOf(ctx.manager().standins().remove(ctx, standinId.optjstring(""), "manual"));
            }
        });
        standins.set("clear", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.manager().standins().clear(ctx.modId()));
            }
        });
        return standins;
    }

    private static LuaTable createWorldTable(LuaModContext ctx) {
        LuaTable world = new LuaTable();
        world.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable out = new LuaTable();
                int i = 1;
                for (Object w : ctx.worlds()) {
                    out.set(i++, worldPayload(ctx, w));
                }
                return out;
            }
        });
        world.set("find", new OneArgFunction() {
            @Override public LuaValue call(LuaValue worldUuid) {
                Object w = ctx.findWorldByUuid(worldUuid.optjstring(""));
                return w == null ? LuaValue.NIL : worldPayload(ctx, w);
            }
        });
        world.set("findByName", new OneArgFunction() {
            @Override public LuaValue call(LuaValue worldName) {
                Object w = ctx.findWorldByName(worldName.optjstring(""));
                return w == null ? LuaValue.NIL : worldPayload(ctx, w);
            }
        });
        world.set("default", new ZeroArgFunction() {
            @Override public LuaValue call() {
                Object w = ctx.defaultWorld();
                return w == null ? LuaValue.NIL : worldPayload(ctx, w);
            }
        });
        world.set("ofPlayer", new OneArgFunction() {
            @Override public LuaValue call(LuaValue player) {
                Object ref = extractPlayerRef(player);
                Object w = ctx.worldOfPlayer(ref);
                return w == null ? LuaValue.NIL : worldPayload(ctx, w);
            }
        });
        world.set("players", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = args.narg() >= 1 ? extractWorldUuid(args.arg(1), ctx) : "";
                java.util.List<Object> refs = (worldUuid == null || worldUuid.isBlank())
                        ? ctx.onlinePlayers()
                        : ctx.onlinePlayersInWorld(worldUuid);
                LuaTable out = new LuaTable();
                int i = 1;
                for (Object ref : refs) {
                    out.set(i++, playerPayload(ctx, ref));
                }
                return out;
            }
        });
        world.set("entities", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = args.narg() >= 1 ? extractWorldUuid(args.arg(1), ctx) : "";
                java.util.List<Object> refs = (worldUuid == null || worldUuid.isBlank())
                        ? ctx.entities()
                        : ctx.entitiesInWorld(worldUuid);
                LuaTable out = new LuaTable();
                int i = 1;
                for (Object ref : refs) {
                    out.set(i++, entityPayload(ctx, ref));
                }
                return out;
            }
        });
        world.set("playerCount", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = args.narg() >= 1 ? extractWorldUuid(args.arg(1), ctx) : "";
                int count = (worldUuid == null || worldUuid.isBlank())
                        ? ctx.onlinePlayers().size()
                        : ctx.onlinePlayersInWorld(worldUuid).size();
                return LuaValue.valueOf(count);
            }
        });
        world.set("entityCount", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = args.narg() >= 1 ? extractWorldUuid(args.arg(1), ctx) : "";
                int count = (worldUuid == null || worldUuid.isBlank())
                        ? ctx.entities().size()
                        : ctx.entitiesInWorld(worldUuid).size();
                return LuaValue.valueOf(count);
            }
        });
        world.set("time", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object target;
                if (args.narg() >= 1 && !args.arg(1).isnil()) {
                    String worldUuid = extractWorldUuid(args.arg(1), ctx);
                    target = (worldUuid == null || worldUuid.isBlank()) ? null : ctx.findWorldByUuid(worldUuid);
                } else {
                    target = ctx.defaultWorld();
                }
                long time = ctx.worldTime(target);
                return LuaValue.valueOf(time);
            }
        });
        world.set("setTime", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                long time = args.arg(2).checklong();
                Object target = (worldUuid == null || worldUuid.isBlank()) ? null : ctx.findWorldByUuid(worldUuid);
                boolean ok = ctx.setWorldTime(target, time);
                return LuaValue.valueOf(ok);
            }
        });
        world.set("isPaused", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object target;
                if (args.narg() >= 1 && !args.arg(1).isnil()) {
                    String worldUuid = extractWorldUuid(args.arg(1), ctx);
                    target = (worldUuid == null || worldUuid.isBlank()) ? null : ctx.findWorldByUuid(worldUuid);
                } else {
                    target = ctx.defaultWorld();
                }
                return LuaValue.valueOf(ctx.worldPaused(target));
            }
        });
        world.set("setPaused", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                boolean paused = args.arg(2).toboolean();
                Object target = (worldUuid == null || worldUuid.isBlank()) ? null : ctx.findWorldByUuid(worldUuid);
                boolean ok = ctx.setWorldPaused(target, paused);
                return LuaValue.valueOf(ok);
            }
        });
        world.set("canControl", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.hasCapability("world-control"));
            }
        });
        world.set("broadcast", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue worldValue, LuaValue message) {
                String worldUuid = extractWorldUuid(worldValue, ctx);
                int sent = ctx.broadcastToWorld(worldUuid == null ? "" : worldUuid, message.optjstring(""));
                return LuaValue.valueOf(sent);
            }
        });
        world.set("blockAt", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                Object block = ctx.blockAt(worldUuid, x, y, z);
                return blockPayload(ctx, block, worldUuid, x, y, z);
            }
        });
        world.set("blockNameAt", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                String name = ctx.blockNameAt(worldUuid, x, y, z);
                return name == null ? LuaValue.NIL : LuaValue.valueOf(name);
            }
        });
        world.set("blockIdAt", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                Integer id = ctx.blockIdAt(worldUuid, x, y, z);
                return id == null ? LuaValue.NIL : LuaValue.valueOf(id);
            }
        });
        world.set("blockType", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nameOrId) {
                Object in = nameOrId.isnumber() ? nameOrId.toint() : nameOrId.optjstring("");
                Object out = ctx.resolveBlockType(in);
                return javaToLua(out);
            }
        });
        world.set("setBlock", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                LuaValue blockValue = args.arg(5);
                Object block = blockValue.isuserdata() ? blockValue.touserdata()
                        : (blockValue.isnumber() ? blockValue.toint() : blockValue.optjstring(""));
                boolean ok = ctx.setBlock(worldUuid, x, y, z, block);
                return LuaValue.valueOf(ok);
            }
        });
        world.set("neighbors", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                LuaTable out = new LuaTable();
                out.set("north", blockPayload(ctx, ctx.blockAt(worldUuid, x, y, z - 1), worldUuid, x, y, z - 1));
                out.set("south", blockPayload(ctx, ctx.blockAt(worldUuid, x, y, z + 1), worldUuid, x, y, z + 1));
                out.set("west", blockPayload(ctx, ctx.blockAt(worldUuid, x - 1, y, z), worldUuid, x - 1, y, z));
                out.set("east", blockPayload(ctx, ctx.blockAt(worldUuid, x + 1, y, z), worldUuid, x + 1, y, z));
                out.set("down", blockPayload(ctx, ctx.blockAt(worldUuid, x, y - 1, z), worldUuid, x, y - 1, z));
                out.set("up", blockPayload(ctx, ctx.blockAt(worldUuid, x, y + 1, z), worldUuid, x, y + 1, z));
                return out;
            }
        });
        world.set("scanBox", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int minX = args.arg(2).checkint();
                int minY = args.arg(3).checkint();
                int minZ = args.arg(4).checkint();
                int maxX = args.arg(5).checkint();
                int maxY = args.arg(6).checkint();
                int maxZ = args.arg(7).checkint();
                int limit = args.arg(8).optint(4096);
                java.util.List<Object> blocks = ctx.blocksInBox(worldUuid, minX, minY, minZ, maxX, maxY, maxZ, limit);
                LuaTable out = new LuaTable();
                int i = 1;
                for (Object b : blocks) out.set(i++, javaToLua(b));
                return out;
            }
        });
        world.set("raycastBlock", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                double ox = args.arg(2).checkdouble();
                double oy = args.arg(3).checkdouble();
                double oz = args.arg(4).checkdouble();
                double dx = args.arg(5).checkdouble();
                double dy = args.arg(6).checkdouble();
                double dz = args.arg(7).checkdouble();
                double maxDistance = args.arg(8).optdouble(64.0);
                double step = args.arg(9).optdouble(0.5);
                Object hit = ctx.raycastBlock(worldUuid, ox, oy, oz, dx, dy, dz, maxDistance, step);
                return javaToLua(hit);
            }
        });
        world.set("blockStateAt", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                return javaToLua(ctx.blockStateAt(worldUuid, x, y, z));
            }
        });
        world.set("setBlockState", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                Object state = luaToJava(args.arg(5));
                return LuaValue.valueOf(ctx.setBlockState(worldUuid, x, y, z, state));
            }
        });
        world.set("queueSetBlock", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                LuaValue blockValue = args.arg(5);
                Object block = blockValue.isuserdata() ? blockValue.touserdata()
                        : (blockValue.isnumber() ? blockValue.toint() : blockValue.optjstring(""));
                int queued = ctx.queueBlockEdit(worldUuid, x, y, z, block);
                return LuaValue.valueOf(queued);
            }
        });
        world.set("batchGet", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaValue first = args.arg(1);
                String defaultWorldUuid = extractWorldUuid(first, ctx);
                LuaValue listArg = defaultWorldUuid == null ? first : args.arg(2);
                LuaValue includeAirArg = defaultWorldUuid == null ? args.arg(2) : args.arg(3);
                boolean includeAir = includeAirArg.optboolean(false);

                if (!listArg.istable()) return new LuaTable();
                LuaTable in = (LuaTable) listArg;
                LuaTable out = new LuaTable();
                int outIndex = 1;

                if (in.get(1).isnil()) {
                    int[] pos = extractBlockPos(in);
                    if (pos == null) return out;
                    String worldUuid = readWorldFromTable(in, ctx);
                    if (worldUuid == null || worldUuid.isBlank()) worldUuid = defaultWorldUuid;
                    Object block = ctx.blockAt(worldUuid, pos[0], pos[1], pos[2]);
                    if (block != null && (includeAir || !looksAirLike(ctx, block))) {
                        out.set(outIndex, blockPayload(ctx, block, worldUuid, pos[0], pos[1], pos[2]));
                    }
                    return out;
                }

                int max = Math.min(65535, in.length());
                for (int i = 1; i <= max; i++) {
                    LuaValue entry = in.get(i);
                    if (entry.isnil() || !entry.istable()) continue;
                    int[] pos = extractBlockPos(entry);
                    if (pos == null) continue;
                    String worldUuid = readWorldFromTable(entry, ctx);
                    if (worldUuid == null || worldUuid.isBlank()) worldUuid = defaultWorldUuid;
                    Object block = ctx.blockAt(worldUuid, pos[0], pos[1], pos[2]);
                    if (block == null) continue;
                    if (!includeAir && looksAirLike(ctx, block)) continue;
                    out.set(outIndex++, blockPayload(ctx, block, worldUuid, pos[0], pos[1], pos[2]));
                }
                return out;
            }
        });
        world.set("batchSet", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaValue first = args.arg(1);
                String defaultWorldUuid = extractWorldUuid(first, ctx);
                LuaValue editsArg = defaultWorldUuid == null ? first : args.arg(2);
                String mode = (defaultWorldUuid == null ? args.arg(2) : args.arg(3)).optjstring("direct")
                        .trim().toLowerCase(java.util.Locale.ROOT);

                LuaTable result = new LuaTable();
                if (!editsArg.istable()) {
                    result.set("processed", LuaValue.valueOf(0));
                    result.set("applied", LuaValue.valueOf(0));
                    result.set("queued", LuaValue.valueOf(0));
                    result.set("failed", LuaValue.valueOf(0));
                    result.set("mode", LuaValue.valueOf(mode));
                    return result;
                }
                if (!mode.equals("direct") && !mode.equals("queue") && !mode.equals("tx")) mode = "direct";
                if (mode.equals("tx") && !ctx.txActive()) {
                    ctx.beginBlockTransaction();
                }

                LuaTable edits = (LuaTable) editsArg;
                int applied = 0;
                int queued = 0;
                int failed = 0;
                int processed = 0;
                boolean singleEntry = edits.get(1).isnil();
                int requested = singleEntry ? 1 : edits.length();
                int max = Math.min(ctx.plugin().getMaxBatchSetOpsPerCall(), requested);
                for (int i = 1; i <= max; i++) {
                    LuaValue entry = singleEntry ? editsArg : edits.get(i);
                    if (entry.isnil() || !entry.istable()) {
                        failed++;
                        continue;
                    }
                    int[] pos = extractBlockPos(entry);
                    Object block = readBlockType(entry);
                    if (pos == null || block == null) {
                        failed++;
                        continue;
                    }
                    String worldUuid = readWorldFromTable(entry, ctx);
                    if (worldUuid == null || worldUuid.isBlank()) worldUuid = defaultWorldUuid;
                    processed++;
                    if (mode.equals("queue")) {
                        int q = ctx.queueBlockEdit(worldUuid, pos[0], pos[1], pos[2], block);
                        if (q > 0) queued++;
                        else failed++;
                    } else if (mode.equals("tx")) {
                        int q = ctx.txQueueBlockEdit(worldUuid, pos[0], pos[1], pos[2], block);
                        if (q > 0) queued++;
                        else failed++;
                    } else {
                        if (ctx.setBlock(worldUuid, pos[0], pos[1], pos[2], block)) applied++;
                        else failed++;
                    }
                }

                result.set("processed", LuaValue.valueOf(processed));
                result.set("applied", LuaValue.valueOf(applied));
                result.set("queued", LuaValue.valueOf(queued));
                result.set("failed", LuaValue.valueOf(failed));
                result.set("mode", LuaValue.valueOf(mode));
                result.set("capped", LuaValue.valueOf(requested > max));
                result.set("cap", LuaValue.valueOf(max));
                return result;
            }
        });
        world.set("txBegin", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.beginBlockTransaction());
            }
        });
        world.set("txSetBlock", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                LuaValue blockValue = args.arg(5);
                Object block = blockValue.isuserdata() ? blockValue.touserdata()
                        : (blockValue.isnumber() ? blockValue.toint() : blockValue.optjstring(""));
                return LuaValue.valueOf(ctx.txQueueBlockEdit(worldUuid, x, y, z, block));
            }
        });
        world.set("txCommit", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int limit = args.arg(1).optint(0);
                return LuaValue.valueOf(ctx.commitBlockTransaction(limit));
            }
        });
        world.set("txRollback", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.rollbackBlockTransaction());
            }
        });
        world.set("txStatus", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable out = new LuaTable();
                out.set("active", LuaValue.valueOf(ctx.txActive()));
                out.set("queued", LuaValue.valueOf(ctx.txQueuedBlockEdits()));
                return out;
            }
        });
        world.set("applyQueuedBlocks", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int limit = args.arg(1).optint(1024);
                return LuaValue.valueOf(ctx.applyBlockEdits(limit));
            }
        });
        world.set("clearQueuedBlocks", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.clearBlockEdits());
            }
        });
        world.set("queuedBlocks", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.queuedBlockEdits());
            }
        });
        return world;
    }

    private static LuaTable createBlocksTable(LuaModContext ctx) {
        LuaTable blocks = new LuaTable();
        blocks.set("register", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object key = luaToJava(args.arg(1));
                LuaValue handlers = args.arg(2);
                if (!handlers.istable()) return LuaValue.FALSE;
                return LuaValue.valueOf(ctx.manager().blockBehaviors().register(ctx.modId(), key, (LuaTable) handlers));
            }
        });
        blocks.set("unregister", new OneArgFunction() {
            @Override public LuaValue call(LuaValue key) {
                return LuaValue.valueOf(ctx.manager().blockBehaviors().unregister(ctx.modId(), luaToJava(key)));
            }
        });
        blocks.set("clear", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.manager().blockBehaviors().clear(ctx.modId()));
            }
        });
        blocks.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return javaToLuaValue(ctx.manager().blockBehaviors().list(ctx.modId()));
            }
        });
        blocks.set("recomputeAt", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                String reason = args.arg(5).optjstring("manual");
                ctx.manager().blockBehaviors().recomputeAt(ctx, worldUuid, x, y, z, reason);
                return LuaValue.TRUE;
            }
        });
        blocks.set("notifyNeighbors", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String worldUuid = extractWorldUuid(args.arg(1), ctx);
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                String reason = args.arg(5).optjstring("manual");
                ctx.manager().blockBehaviors().notifyNeighbors(ctx, worldUuid, x, y, z, reason);
                return LuaValue.TRUE;
            }
        });
        return blocks;
    }

    private static LuaTable createNetworkTable(LuaModContext ctx) {
        LuaTable network = new LuaTable();
        network.set("send", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object player = extractPlayerRef(args.arg(1));
                String channel = args.arg(2).checkjstring();
                String payload = args.arg(3).optjstring("");
                boolean ok = ctx.sendNetworkMessage(player, channel, payload);
                return LuaValue.valueOf(ok);
            }
        });
        network.set("sendAll", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String channel = args.arg(1).checkjstring();
                String payload = args.arg(2).optjstring("");
                String worldUuid = args.narg() >= 3 ? extractWorldUuid(args.arg(3), ctx) : "";
                java.util.List<Object> targets = (worldUuid == null || worldUuid.isBlank())
                        ? ctx.onlinePlayers()
                        : ctx.onlinePlayersInWorld(worldUuid);
                int sent = ctx.sendNetworkMessage(targets, channel, payload);
                return LuaValue.valueOf(sent);
            }
        });
        network.set("refer", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object player = extractPlayerRef(args.arg(1));
                String host = args.arg(2).checkjstring();
                int port = args.arg(3).checkint();
                boolean ok = ctx.referPlayer(player, host, port);
                return LuaValue.valueOf(ok);
            }
        });
        network.set("referAll", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String host = args.arg(1).checkjstring();
                int port = args.arg(2).checkint();
                String worldUuid = args.narg() >= 3 ? extractWorldUuid(args.arg(3), ctx) : "";
                java.util.List<Object> targets = (worldUuid == null || worldUuid.isBlank())
                        ? ctx.onlinePlayers()
                        : ctx.onlinePlayersInWorld(worldUuid);
                int sent = ctx.referPlayers(targets, host, port);
                return LuaValue.valueOf(sent);
            }
        });
        network.set("on", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue channel, LuaValue fn) {
                if (!fn.isfunction()) return LuaValue.FALSE;
                boolean ok = ctx.onNetwork(channel.optjstring(""), (LuaFunction) fn);
                return LuaValue.valueOf(ok);
            }
        });
        network.set("off", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String channel = args.arg(1).optjstring("");
                LuaValue fn = args.arg(2);
                int removed;
                if (fn.isfunction()) {
                    removed = ctx.offNetwork(channel, (LuaFunction) fn);
                } else {
                    removed = ctx.offNetwork(channel, null);
                }
                return LuaValue.valueOf(removed);
            }
        });
        network.set("list", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable out = new LuaTable();
                int i = 1;
                for (String channel : ctx.networkChannels()) {
                    out.set(i++, LuaValue.valueOf(channel));
                }
                return out;
            }
        });
        network.set("emit", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue channel, LuaValue payload) {
                int delivered = ctx.emitNetwork(channel.optjstring(""), payload);
                return LuaValue.valueOf(delivered);
            }
        });
        network.set("allowed", new OneArgFunction() {
            @Override public LuaValue call(LuaValue channel) {
                return LuaValue.valueOf(ctx.isNetworkChannelAllowed(channel.optjstring("")));
            }
        });
        network.set("policy", new OneArgFunction() {
            @Override public LuaValue call(LuaValue channel) {
                ArcaneLoaderPlugin.NetworkChannelDecision decision =
                        ctx.plugin().evaluateNetworkChannel(ctx.modId(), channel.optjstring(""));
                LuaTable out = new LuaTable();
                out.set("allowed", LuaValue.valueOf(decision.allowed()));
                out.set("capabilityAllowed", LuaValue.valueOf(decision.capabilityAllowed()));
                out.set("reason", LuaValue.valueOf(decision.reason()));
                if (decision.matchedPrefix() != null) {
                    out.set("matchedPrefix", LuaValue.valueOf(decision.matchedPrefix()));
                }
                return out;
            }
        });
        network.set("canControl", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.hasCapability("network-control"));
            }
        });
        return network;
    }

    private static LuaTable createWebhookTable(LuaModContext ctx) {
        LuaTable webhook = new LuaTable();
        webhook.set("request", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String method = args.arg(1).optjstring("POST");
                String url = args.arg(2).checkjstring();
                String body = args.arg(3).optjstring("");
                String contentType = args.arg(4).optjstring("application/json");
                int timeoutMs = args.arg(5).optint(5000);
                Object headersObj = args.narg() >= 6 ? luaToJava(args.arg(6)) : java.util.Collections.emptyMap();
                java.util.Map<String, String> headers = toStringMap(headersObj);
                java.util.Map<String, Object> result = ctx.webhookRequest(method, url, body, contentType, timeoutMs, headers);
                return javaToLua(result);
            }
        });
        webhook.set("postJson", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String url = args.arg(1).checkjstring();
                LuaValue payload = args.arg(2);
                int timeoutMs = args.arg(3).optint(5000);
                Object headersObj = args.narg() >= 4 ? luaToJava(args.arg(4)) : java.util.Collections.emptyMap();
                java.util.Map<String, String> headers = toStringMap(headersObj);
                String body = GSON.toJson(luaToJsonElement(payload));
                java.util.Map<String, Object> result = ctx.webhookRequest("POST", url, body, "application/json", timeoutMs, headers);
                return javaToLua(result);
            }
        });
        webhook.set("submit", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String method = args.arg(1).optjstring("POST");
                String url = args.arg(2).checkjstring();
                String body = args.arg(3).optjstring("");
                String contentType = args.arg(4).optjstring("application/json");
                int timeoutMs = args.arg(5).optint(5000);
                Object headersObj = args.narg() >= 6 ? luaToJava(args.arg(6)) : java.util.Collections.emptyMap();
                java.util.Map<String, String> headers = toStringMap(headersObj);
                long requestId = ctx.webhookRequestAsync(method, url, body, contentType, timeoutMs, headers);
                return LuaValue.valueOf(requestId);
            }
        });
        webhook.set("submitJson", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String url = args.arg(1).checkjstring();
                LuaValue payload = args.arg(2);
                int timeoutMs = args.arg(3).optint(5000);
                Object headersObj = args.narg() >= 4 ? luaToJava(args.arg(4)) : java.util.Collections.emptyMap();
                java.util.Map<String, String> headers = toStringMap(headersObj);
                String body = GSON.toJson(luaToJsonElement(payload));
                long requestId = ctx.webhookRequestAsync("POST", url, body, "application/json", timeoutMs, headers);
                return LuaValue.valueOf(requestId);
            }
        });
        webhook.set("poll", new OneArgFunction() {
            @Override public LuaValue call(LuaValue requestId) {
                java.util.Map<String, Object> result = ctx.webhookPoll(requestId.checklong());
                return result == null ? LuaValue.NIL : javaToLua(result);
            }
        });
        webhook.set("cancel", new OneArgFunction() {
            @Override public LuaValue call(LuaValue requestId) {
                return LuaValue.valueOf(ctx.cancelWebhookRequest(requestId.checklong()));
            }
        });
        webhook.set("pending", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.webhookPendingCount());
            }
        });
        webhook.set("canControl", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.hasCapability("network-control"));
            }
        });
        return webhook;
    }

    private static LuaTable createUiTable(LuaModContext ctx) {
        LuaTable ui = new LuaTable();
        ui.set("actionbar", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue player, LuaValue message) {
                Object ref = extractPlayerRef(player);
                boolean ok = ctx.sendActionBar(ref, message.optjstring(""));
                return LuaValue.valueOf(ok);
            }
        });
        ui.set("actionbarAll", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String message = args.arg(1).optjstring("");
                String worldUuid = args.narg() >= 2 ? extractWorldUuid(args.arg(2), ctx) : "";
                java.util.List<Object> targets = (worldUuid == null || worldUuid.isBlank())
                        ? ctx.onlinePlayers()
                        : ctx.onlinePlayersInWorld(worldUuid);
                int sent = ctx.sendActionBar(targets, message);
                return LuaValue.valueOf(sent);
            }
        });
        ui.set("title", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object ref = extractPlayerRef(args.arg(1));
                String title = args.arg(2).optjstring("");
                String subtitle = args.arg(3).optjstring("");
                int fadeIn = args.arg(4).optint(10);
                int stay = args.arg(5).optint(40);
                int fadeOut = args.arg(6).optint(10);
                boolean ok = ctx.sendTitle(ref, title, subtitle, fadeIn, stay, fadeOut);
                return LuaValue.valueOf(ok);
            }
        });
        ui.set("titleAll", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String title = args.arg(1).optjstring("");
                String subtitle = args.arg(2).optjstring("");
                int fadeIn = args.arg(3).optint(10);
                int stay = args.arg(4).optint(40);
                int fadeOut = args.arg(5).optint(10);
                String worldUuid = args.narg() >= 6 ? extractWorldUuid(args.arg(6), ctx) : "";
                java.util.List<Object> targets = (worldUuid == null || worldUuid.isBlank())
                        ? ctx.onlinePlayers()
                        : ctx.onlinePlayersInWorld(worldUuid);
                int sent = ctx.sendTitle(targets, title, subtitle, fadeIn, stay, fadeOut);
                return LuaValue.valueOf(sent);
            }
        });
        ui.set("canControl", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(ctx.hasCapability("ui-control"));
            }
        });
        ui.set("form", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object player = extractPlayerRef(args.arg(1));
                String formId = args.arg(2).optjstring("");
                LuaValue payload = args.arg(3);
                String json = GSON.toJson(luaToJsonElement(payload));
                return LuaValue.valueOf(ctx.sendUiForm(player, formId, json));
            }
        });
        ui.set("formAll", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String formId = args.arg(1).optjstring("");
                LuaValue payload = args.arg(2);
                String worldUuid = args.narg() >= 3 ? extractWorldUuid(args.arg(3), ctx) : "";
                java.util.List<Object> targets = (worldUuid == null || worldUuid.isBlank())
                        ? ctx.onlinePlayers()
                        : ctx.onlinePlayersInWorld(worldUuid);
                String json = GSON.toJson(luaToJsonElement(payload));
                int sent = ctx.sendUiForm(targets, formId, json);
                return LuaValue.valueOf(sent);
            }
        });
        ui.set("panel", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Object player = extractPlayerRef(args.arg(1));
                String panelId = args.arg(2).optjstring("");
                LuaValue payload = args.arg(3);
                String json = GSON.toJson(luaToJsonElement(payload));
                return LuaValue.valueOf(ctx.sendUiPanel(player, panelId, json));
            }
        });
        ui.set("panelAll", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String panelId = args.arg(1).optjstring("");
                LuaValue payload = args.arg(2);
                String worldUuid = args.narg() >= 3 ? extractWorldUuid(args.arg(3), ctx) : "";
                java.util.List<Object> targets = (worldUuid == null || worldUuid.isBlank())
                        ? ctx.onlinePlayers()
                        : ctx.onlinePlayersInWorld(worldUuid);
                String json = GSON.toJson(luaToJsonElement(payload));
                int sent = ctx.sendUiPanel(targets, panelId, json);
                return LuaValue.valueOf(sent);
            }
        });
        return ui;
    }

    private static LuaTable createArcaneTable(LuaModContext ctx) {
        LuaTable arcane = new LuaTable();
        arcane.set("apiVersion", LuaValue.valueOf(LUA_API_VERSION));
        arcane.set("loaderVersion", LuaValue.valueOf(ctx.plugin().getLoaderVersion()));
        arcane.set("modId", LuaValue.valueOf(ctx.modId()));

        LuaTable caps = new LuaTable();
        caps.set("playerMovement", LuaValue.valueOf(ctx.hasCapability("player-movement")));
        caps.set("entityControl", LuaValue.valueOf(ctx.hasCapability("entity-control")));
        caps.set("worldControl", LuaValue.valueOf(ctx.hasCapability("world-control")));
        caps.set("networkControl", LuaValue.valueOf(ctx.hasCapability("network-control")));
        caps.set("uiControl", LuaValue.valueOf(ctx.hasCapability("ui-control")));
        arcane.set("capabilities", caps);

        arcane.set("hasCapability", new OneArgFunction() {
            @Override public LuaValue call(LuaValue capabilityName) {
                return LuaValue.valueOf(ctx.hasCapability(capabilityName.optjstring("")));
            }
        });
        return arcane;
    }

    private static LuaTable playerPayload(LuaModContext ctx, Object ref) {
        LuaTable p = new LuaTable();
        p.set("playerRef", LuaValue.userdataOf(ref));
        String name = ctx.playerName(ref);
        if (name != null) p.set("username", LuaValue.valueOf(name));
        String uuid = ctx.playerUuid(ref);
        if (uuid != null) p.set("uuid", LuaValue.valueOf(uuid));
        String world = ctx.playerWorldUuid(ref);
        if (world != null) p.set("worldUuid", LuaValue.valueOf(world));
        p.set("isValid", LuaValue.valueOf(ctx.isValidPlayer(ref)));
        return p;
    }

    private static LuaTable worldPayload(LuaModContext ctx, Object worldRef) {
        LuaTable w = new LuaTable();
        w.set("worldRef", LuaValue.userdataOf(worldRef));
        String uuid = ctx.worldUuid(worldRef);
        if (uuid != null) w.set("uuid", LuaValue.valueOf(uuid));
        String name = ctx.worldName(worldRef);
        if (name != null) w.set("name", LuaValue.valueOf(name));
        return w;
    }

    private static LuaTable entityPayload(LuaModContext ctx, Object entityRef) {
        LuaTable e = new LuaTable();
        e.set("entityRef", LuaValue.userdataOf(entityRef));
        String id = ctx.entityId(entityRef);
        if (id != null) e.set("id", LuaValue.valueOf(id));
        String type = ctx.entityType(entityRef);
        if (type != null) e.set("type", LuaValue.valueOf(type));
        String worldUuid = ctx.entityWorldUuid(entityRef);
        if (worldUuid != null) e.set("worldUuid", LuaValue.valueOf(worldUuid));
        e.set("isValid", LuaValue.valueOf(ctx.isValidEntity(entityRef)));
        return e;
    }

    private static double[] queryPosition(LuaModContext ctx, LuaValue value) {
        if (value == null || value.isnil()) return null;
        double[] vec = extractVec3(value);
        if (vec != null) return vec;
        Object player = extractPlayerRef(value);
        if (player != null) {
            double[] pos = ctx.playerPosition(player);
            if (pos != null && pos.length >= 3) return pos;
        }
        Object entity = extractEntityRef(value);
        if (entity != null) {
            double[] pos = ctx.entityPosition(entity);
            if (pos != null && pos.length >= 3) return pos;
        }
        return null;
    }

    private static Object nearestPlayerRef(LuaModContext ctx, double[] origin, double radius, String worldUuid) {
        Object best = null;
        double bestDistance = Double.MAX_VALUE;
        java.util.List<Object> candidates = (worldUuid == null || worldUuid.isBlank())
                ? ctx.onlinePlayers()
                : ctx.onlinePlayersInWorld(worldUuid);
        for (Object ref : candidates) {
            double[] pos = ctx.playerPosition(ref);
            if (pos == null || pos.length < 3) continue;
            double distance = distanceSquared(origin, pos);
            if (radius >= 0.0 && distance > (radius * radius)) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = ref;
            }
        }
        return best;
    }

    private static Object nearestEntityRef(LuaModContext ctx, double[] origin, double radius, String worldUuid) {
        java.util.List<Object> candidates;
        if (worldUuid == null || worldUuid.isBlank()) {
            candidates = radius >= 0.0 ? ctx.entitiesNear("", origin[0], origin[1], origin[2], radius) : ctx.entities();
        } else {
            candidates = radius >= 0.0 ? ctx.entitiesNear(worldUuid, origin[0], origin[1], origin[2], radius) : ctx.entitiesInWorld(worldUuid);
        }
        Object best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Object ref : candidates) {
            double[] pos = ctx.entityPosition(ref);
            if (pos == null || pos.length < 3) continue;
            double distance = distanceSquared(origin, pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = ref;
            }
        }
        return best;
    }

    private static double[] extractMapPosition(java.util.Map<String, Object> item) {
        if (item == null) return null;
        Object x = item.get("x");
        Object y = item.get("y");
        Object z = item.get("z");
        if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
            return new double[] { nx.doubleValue(), ny.doubleValue(), nz.doubleValue() };
        }
        Object position = item.get("position");
        if (position instanceof java.util.Map<?, ?> pos) {
            Object px = pos.get("x");
            Object py = pos.get("y");
            Object pz = pos.get("z");
            if (px instanceof Number nx && py instanceof Number ny && pz instanceof Number nz) {
                return new double[] { nx.doubleValue(), ny.doubleValue(), nz.doubleValue() };
            }
        }
        return null;
    }

    private static double distanceSquared(double[] left, double[] right) {
        double dx = left[0] - right[0];
        double dy = left[1] - right[1];
        double dz = left[2] - right[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static LuaValue blockPayload(LuaModContext ctx, Object block, String worldUuid, int x, int y, int z) {
        if (block == null) return LuaValue.NIL;
        LuaTable out = new LuaTable();
        out.set("block", javaToLua(block));
        if (worldUuid != null) out.set("worldUuid", LuaValue.valueOf(worldUuid));
        out.set("x", LuaValue.valueOf(x));
        out.set("y", LuaValue.valueOf(y));
        out.set("z", LuaValue.valueOf(z));
        Object id = ctx.reflectiveGet(block, "id");
        if (id == null) id = ctx.reflectiveGet(block, "blockId");
        if (id != null) out.set("id", javaToLua(id));
        Object name = ctx.reflectiveGet(block, "name");
        if (name == null) name = ctx.reflectiveGet(block, "type");
        if (name != null) out.set("name", LuaValue.valueOf(String.valueOf(name)));
        return out;
    }

    public Varargs safeInvoke(LuaValue fn, Varargs args, String where, String modId) {
        try {
            return fn.invoke(args);
        } catch (LuaError e) {
            plugin.getLogger().at(Level.WARNING).log("Lua error in " + where + " for " + modId + ": " + e.getMessage());
            throw e;
        } catch (Throwable t) {
            plugin.getLogger().at(Level.WARNING).log("Error in " + where + " for " + modId + ": " + t);
            throw t;
        }
    }
}
