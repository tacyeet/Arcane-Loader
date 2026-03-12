# Arcane Loader

Arcane Loader is a hot-reload Lua mod loader for Hytale servers.

It is built for fast iteration, deterministic mod behavior, and enough safety controls to keep a live server usable while you experiment.

Current loader version: `1.1.0`

## At A Glance

- Drop Lua mods into `lua_mods/` and reload them in-game.
- Deterministic enable / disable / reload ordering.
- Per-mod filesystem sandboxing under `lua_data/<modId>/`.
- A broad Lua API for commands, events, world access, actors, volumes, mechanics, transforms, networking, UI, and interop probes.
- Policy controls for sensitive capabilities like movement, world edits, networking, and UI.
- Async webhook support for non-blocking outbound HTTP.
- Profiling, tracing, error history, and a `/lua doctor` workflow for debugging.

## Who This Is For

- Server developers who want to prototype gameplay logic without rebuilding a Java plugin every edit.
- Mod authors who want a stable Lua runtime with real reload, tracing, persistence, and policy controls.

## What You Will Use Most

If you are writing mods, the main things you will touch are:

- `manifest.json` and `init.lua`
- `/lua new`, `/lua reload`, `/lua mods`, `/lua doctor`
- `commands`, `events`, `fs`, `store`, `world`, `players`, `entities`, `network`, `ui`
- the example mods in `examples/lua_mods/`

If you are evaluating the loader, the main sections to read are:

- Quick Start
- Mod Format
- Server Commands
- Lua API Reference

## 1) Quick Start

### Requirements

- Java 25
- `HytaleServer.jar` from Latest Hytale Release.
- A writable server folder

### Build Arcane Loader 
(you can skip this step by downloading latest release at https://github.com/tacyeet/Arcane-Loader/releases/tag/Release)

```bash
./gradlew build -PhytaleServerJar=/absolute/path/to/HytaleServer.jar
```

If your `HytaleServer.jar` is already one directory above the repo root, this also works:

```bash
./gradlew build -PhytaleServerJar=../HytaleServer.jar
```

Output jar:

- `build/libs/arcane-loader-1.1.0.jar`

### Install and generate runtime folders

1. Build the plugin.
2. Put `HytaleServer.jar` from Releases in your server root.
3. Copy Arcane Loader jar:

```bash
cp build/libs/arcane-loader-1.1.0.jar server/plugins/
```

4. Boot the world once so Arcane Loader creates its files.

### Add Lua mods to the server root

Copy mod folders into:

```text
server/lua_mods/
```

Example path:

```text
/path/to/server/lua_mods/
```

You can start with the included pack in `examples/lua_mods/`:

```bash
cp -r examples/lua_mods/0* server/lua_mods/
```

Then run `/lua reload`.

For a persisted-state example, also copy:

```bash
cp -r examples/lua_mods/12_stateful_counter server/lua_mods/
```

For a composite stand-in example, also copy:

```bash
cp -r examples/lua_mods/13_standin_probe server/lua_mods/
```

For a fresh starter mod, use the built-in scaffold command:

```text
/lua new my_first_mod
/lua new my_first_command command
/lua new my_first_event event
```

That creates `manifest.json` and `init.lua` under `server/lua_mods/<modId>/`.

### Make your first real mod

1. Run `/lua new my_first_mod`.
2. Open `server/lua_mods/my_first_mod/init.lua`.
3. Add a simple `onEnable` hook or command.
4. Run `/lua reload my_first_mod`.
5. Use `/lua errors my_first_mod` if the mod fails to load.

### First sanity checks

- `/lua mods`
- `/lua api`
- `/lua doctor`
- `/lua profile`

If the server jar changed recently, `/lua doctor` is the fastest first pass for checking that the loader, watcher, policy state, and runtime surfaces still line up.

### Optional: Java hello-world jar (advanced)

A minimal Java plugin starter is included at `examples/java-hello-plugin/`.

Build it:

```bash
cd examples/java-hello-plugin
../../gradlew clean build -PhytaleServerJar=/absolute/path/to/HytaleServer.jar
```

Result:

- `examples/java-hello-plugin/build/libs/hello-hytale-plugin-1.0.0.jar`

Copy that jar into `server/plugins/` to test a minimal Java plugin lifecycle.

## 2) What The Loader Creates

Arcane Loader expects or creates these server-root folders and files:

- `lua_mods/`: your Lua mods
- `lua_cache/`: loader scratch/cache data
- `lua_data/`: per-mod persisted files and stores
- `lua_assets/`: staged mod assets
- `logs/`: profile dumps and server logs
- `arcane-loader.json`: loader config

Typical layout:

```text
server/
  HytaleServer.jar
  plugins/
  lua_mods/
  lua_cache/
  lua_data/
  lua_assets/
  logs/
  arcane-loader.json
```

## 3) Configuration (`arcane-loader.json`)

Default:

```json
{
  "devMode": true,
  "autoReload": false,
  "autoEnable": false,
  "autoStageAssets": true,
  "slowCallWarnMs": 10.0,
  "blockEditBudgetPerTick": 256,
  "maxQueuedBlockEditsPerMod": 20000,
  "maxTxBlockEditsPerMod": 10000,
  "maxBatchSetOpsPerCall": 5000,
  "maxBlockBehaviorNeighborUpdatesPerCause": 64,
  "restrictSensitiveApis": false,
  "allowlistEnabled": false,
  "allowlist": [],
  "playerMovementMods": [],
  "entityControlMods": [],
  "worldControlMods": [],
  "networkControlMods": [],
  "uiControlMods": [],
  "networkChannelPolicies": {
    "*": ["arcane."]
  }
}
```

Key behavior:

- `devMode`: enables `/lua eval`.
- `autoReload`: enables file watcher for `lua_mods/`.
- `autoEnable`: enables all discovered mods on server start.
- `autoStageAssets`: copies mod `assets/` folders into `lua_assets/<modId>/`.
- `allowlistEnabled=true`: only mod IDs in `allowlist` are loaded.
- `restrictSensitiveApis=true`: capability arrays are enforced.
- `networkChannelPolicies`: prefix allowlist for network channels per mod.
- `blockEditBudgetPerTick`: automatic queued block edits applied per tick.
- `maxQueuedBlockEditsPerMod`: hard cap for `world.queueSetBlock` per mod.
- `maxTxBlockEditsPerMod`: hard cap for `world.txSetBlock` queue per mod.
- `maxBatchSetOpsPerCall`: hard cap per `world.batchSet` call.
- `maxBlockBehaviorNeighborUpdatesPerCause`: cap for one block behavior propagation burst.

Example channel policy:

```json
"networkChannelPolicies": {
  "chatmod": ["chat.", "arcane."],
  "*": ["arcane."]
}
```

Use `/lua config reload` after edits.

## 4) Mod Format

Create:

```text
lua_mods/<modId>/
  manifest.json
  init.lua
```

`manifest.json`:

```json
{
  "id": "example",
  "name": "Example Mod",
  "version": "1.1.0",
  "entry": "init.lua",
  "loadBefore": ["other-mod-id"],
  "loadAfter": ["core-lib"]
}
```

- `entry` defaults to `init.lua`.
- `loadBefore` / `loadAfter` are optional ordering hints.

Minimal `init.lua`:

```lua
commands.register("ping", function(sender, args)
  log.info("pong from " .. arcane.modId)
end)

return {
  onEnable = function(ctx)
    log.info("enabled api=" .. arcane.apiVersion)
  end,
  onDisable = function(ctx)
    log.info("disabled")
  end
}
```

What matters most:

- `id` must be unique across your installed mods.
- `version` is your mod version, not the loader version.
- `entry` is usually just `init.lua`.
- `onEnable(ctx)` is the first place to register commands, listeners, timers, and state.
- `onDisable(ctx)` is where you clean up anything not already managed by Arcane Loader.

## 5) Multi-Mod Behavior (Important)

Yes, multiple mods can run together.

- Lifecycle and manager-driven event order is deterministic.
- Mods can communicate via `network.emit` + `network.on`.
- Mods share server state (world/entities/players) if capabilities allow.

Isolation rules:

- `fs.*` is sandboxed to `lua_data/<modId>/`.
- `require` is mod-local.
- `events.*` is mod-local event bus.
- Cross-mod messaging is via `network.*` channels.

## 6) Server Commands

Most-used commands:

- `/lua new <modId> [blank|command|event]`
- `/lua reload [modId]`
- `/lua mods`
- `/lua errors [modId]`
- `/lua api`
- `/lua doctor`

- `/lua mods`
- `/lua new <modId> [blank|command|event]`
- `/lua enable [modId]`
- `/lua disable [modId]`
- `/lua reload [modId]`
- `/lua errors [modId]`
- `/lua api`
- `/lua call <modId> <command> [args...]`
- `/lua eval <code>` (devMode only)
- `/lua debug [on|off|toggle]`
- `/lua profile`
- `/lua profile reset [modId]`
- `/lua profile dump`
- `/lua trace <modId> <event:name|network:name|command:name|list|clear|off key>`
- `/lua caps [modId]`
- `/lua netstats [channel|reset]`
- `/lua config [reload]`
- `/lua policy <modId> <channel>`
- `/lua policy allow|deny <modId|*> <prefix>`
- `/lua policy clear <modId|*>`
- `/lua policy cap <modId> <player-movement|entity-control|world-control|network-control|ui-control> <on|off>`
- `/lua policy list [modId]`
- `/lua verify [benchPrefix]`
- `/lua doctor`

## 7) Lua API Reference

The API below is the detailed reference. If you are new to the loader, start with:

1. `commands`
2. `events`
3. `fs` and `store`
4. `players`, `entities`, and `world`
5. `network` and `ui`

### Metadata

- `arcane.apiVersion`
- `arcane.loaderVersion`
- `arcane.modId`
- `arcane.capabilities` (`playerMovement`, `entityControl`, `worldControl`, `networkControl`, `uiControl`)
- `arcane.hasCapability(name)`

### Logging

- `log.info(msg)`
- `log.warn(msg)`
- `log.error(msg)`

### Commands

- `commands.register(name, fn)`
- `commands.register(name, help, fn)`
- `commands.unregister(name)`
- `commands.list()`
- `commands.help(name)`

### Events

- `events.on(name, fn)`
- `events.on(name, options, fn)`
- `events.once(name, fn)`
- `events.once(name, options, fn)`
- `events.emit(name, payload...)`
- `events.off(name[, fn])`
- `events.clear(name)`
- `events.list()`
- `events.count([name])`
- `events.listeners(name)`

Event options:

- `priority = events.priority.LOWEST | LOW | NORMAL | HIGH | HIGHEST | MONITOR`
- `ignoreCancelled = true`
- `once = true`

### Filesystem (sandboxed per mod)

- `fs.read(path)`
- `fs.write(path, data)`
- `fs.append(path, data)`
- `fs.mkdir(path)`
- `fs.readJson(path[, defaults[, writeBack]])`
- `fs.writeJson(path, value[, pretty])`
- `fs.delete(path[, recursive])`
- `fs.move(from, to[, replace])`
- `fs.copy(from, to[, replace])`
- `fs.exists(path)`
- `fs.list([dir])`

### JSON store helpers

- `store.path(name)`
- `store.exists(name)`
- `store.list()`
- `store.load(name[, defaults[, writeBack]])`
- `store.loadVersioned(name, schemaVersion[, defaults[, migrators[, writeBack]]])`
- `store.save(name, value[, pretty])`
- `store.saveVersioned(name, schemaVersion, value[, pretty])`
- `store.delete(name)`

Store notes:

- store files live under `lua_data/<modId>/stores/`
- names default to `.json` if you omit the extension
- this is the simplest path for per-mod config and saved state
- `loadVersioned` / `saveVersioned` use a `schemaVersion` field for lightweight save migration flows

`fs.readJson` notes:

- If `defaults` is provided, missing keys are merged recursively.
- If `writeBack=true`, merged result is saved to disk.

### Typed helpers

- `vec3.new(x, y, z)`
- `vec3.is(value)`
- `vec3.unpack(value)`
- `blockpos.new(x, y, z)`
- `blockpos.is(value)`
- `blockpos.unpack(value)`

### Query helpers

- `query.withinDistance(a, b, radius)`
- `query.nearestPlayer(origin[, radius[, worldUuid]])`
- `query.nearestEntity(origin[, radius[, worldUuid]])`
- `query.nearestActor(origin[, radius[, kind]])`
- `query.nearestVolume(origin[, radius[, kind]])`
- `query.nearestNode(origin[, radius[, kind]])`

Query notes:

- `origin` can be a `vec3`, player payload/ref, or entity payload/ref
- actor/volume/node queries return the same map shape as their `list()` APIs

### Stand-in helpers

- `standins.create(options)`
- `standins.spawn(typeOrPrototype, options)`
- `standins.find(id)`
- `standins.list()`
- `standins.count()`
- `standins.listByKind(kind)`
- `standins.listByTag(tag)`
- `standins.state(id)`
- `standins.setState(id, state)`
- `standins.resolve(id)`
- `standins.actor(id)`
- `standins.transform(id)`
- `standins.volume(id)`
- `standins.node(id)`
- `standins.has(id, component)`
- `standins.move(id, vec3)`
- `standins.rotate(id, vec3)`
- `standins.attach(childId, parentId)`
- `standins.remove(id)`
- `standins.clear()`

Stand-in notes:

- a stand-in is one managed logical object that can own or bind an actor, transform, volume, and mechanics node
- this is the main loader-side workaround when Hytale does not expose the exact native feature a mod wants
- transform-linked stand-ins can keep volumes and mechanics nodes synced through `sync.transformToVolume` and `sync.transformToNode`

### Reflection/Interop (advanced)

- `interop.server()`
- `interop.universe()`
- `interop.defaultWorld()`
- `interop.classExists(className)`
- `interop.resolveClass(className)`
- `interop.get(target, key)`
- `interop.set(target, key, value)`
- `interop.call(target, method, ...)`
- `interop.methods(target[, prefix])`
- `interop.fields(target[, prefix])`
- `interop.describe(target[, prefix])`
- `interop.staticMethods(className[, prefix])`
- `interop.staticFields(className[, prefix])`
- `interop.describeClass(className[, prefix])`
- `interop.constructors(className)`
- `interop.staticCall(className, method, ...)`
- `interop.newInstance(className, ...)`
- `interop.typeOf(target)`

Interop notes:

- `resolveClass` returns Java class userdata when the class can be found.
- `staticMethods` and `staticCall` are intended for probing native engine modules and factory helpers.
- `fields`, `staticFields`, and `constructors` are intended for mapping unknown engine surfaces quickly.
- `newInstance` is mainly for controlled probe attempts; many engine-owned types will still fail to construct usefully.
- This is the main path for investigating whether Hytale exposes hooks needed for custom models, attachments, collision helpers, or moving-world abstractions.

### Components

- `components.health(entity)` / `components.setHealth(entity, value)`
- `components.maxHealth(entity)` / `components.setMaxHealth(entity, value)`
- `components.alive(entity)`
- `components.damage(entity, amount)`
- `components.heal(entity, amount)`
- `components.get(entity, key)` / `components.set(entity, key, value)`
- `components.call(entity, method, ...)`
- `components.methods(entity[, prefix])`
- `components.stamina(entity)` / `components.setStamina(entity, value)`
- `components.hunger(entity)` / `components.setHunger(entity, value)`
- `components.mana(entity)` / `components.setMana(entity, value)`
- `components.tags(entity)` / `components.hasTag(entity, tag)` / `components.addTag(entity, tag)` / `components.removeTag(entity, tag)`

### Players

- `players.send(player, message)`
- `players.name(player)`
- `players.uuid(player)`
- `players.worldUuid(player)`
- `players.position(player)`
- `players.teleport(player, [worldUuid], x, y, z | vec3)`
- `players.kick(player, reason)`
- `players.refer(player, host, port)`
- `players.isValid(player)`
- `players.hasPermission(player, node)`
- `players.broadcast(message)`
- `players.broadcastTo(listOrTarget, message)`
- `players.list()`
- `players.count()`
- `players.findByName(username)`
- `players.findByUuid(uuid)`
- `players.canMove()`

### Entities

- `entities.list([worldUuid])`
- `entities.count([worldUuid])`
- `entities.find(entityId)`
- `entities.id(entity)`
- `entities.type(entity)`
- `entities.worldUuid(entity)`
- `entities.isValid(entity)`
- `entities.remove(entity)`
- `entities.position(entity)`
- `entities.teleport(entity, [worldUuid], x, y, z | vec3)`
- `entities.rotation(entity)`
- `entities.setRotation(entity, yaw, pitch, [roll] | table)`
- `entities.velocity(entity)`
- `entities.setVelocity(entity, x, y, z | vec3)`
- `entities.near([worldUuid], x, y, z, radius)`
- `entities.spawn(typeOrPrototype, [worldUuid], x, y, z | vec3, [yaw], [pitch], [roll])`
- `entities.inventory(entity)`
- `entities.equipment(entity[, slot])`
- `entities.setEquipment(entity, slot, item)`
- `entities.giveItem(entity, itemOrType, [count])`
- `entities.takeItem(entity, itemOrType, [count])`
- `entities.effects(entity)`
- `entities.addEffect(entity, effectType, [durationTicks], [amplifier])`
- `entities.removeEffect(entity, effectType)`
- `entities.attribute(entity, name)`
- `entities.setAttribute(entity, name, value)`
- `entities.pathTo(entity, [worldUuid], x, y, z | vec3)`
- `entities.stopPath(entity)`
- `entities.canControl()`

### Actors (managed logical actors)

- `actors.spawn(typeOrPrototype, [worldUuid], x, y, z | vec3, [yaw], [pitch], [roll], [options])`
- `actors.bind(entity, [options])`
- `actors.exists(actorId)`
- `actors.find(actorId)`
- `actors.list()`
- `actors.listByKind(kind)`
- `actors.listByTag(tag)`
- `actors.entity(actorId)`
- `actors.findByEntity(entity)`
- `actors.state(actorId)`
- `actors.setState(actorId, stateTable)`
- `actors.setKind(actorId, kind)`
- `actors.setTags(actorId, tagsTable)`
- `actors.remove(actorId[, despawnOwnedEntity])`

Actor options:

- `id`: optional stable actor id override
- `kind`: logical actor kind label
- `tags`: string list for filtering/grouping
- `state`: Lua table stored with the actor
- `onTick(payload)`: called once per loader tick while the actor is valid
- `onRemove(payload)`: called when the actor is removed or its backing entity becomes invalid
- `updateEveryTicks`: throttle `onTick` for lower-frequency actors

Actor notes:

- Actors are per-mod and automatically cleaned up on disable/reload.
- `actors.spawn(...)` still uses native Hytale entity spawning underneath; it does not create true custom engine entity classes.
- This is a logical actor runtime for stable ids, state, and update loops around existing engine entities.
- If `onTick` returns a table, that table becomes the next stored actor state.

### Volumes (overlap + simple force runtime)

- `volumes.box([worldUuid], minX, minY, minZ, maxX, maxY, maxZ[, options])`
- `volumes.sphere([worldUuid], x, y, z, radius[, options])`
- `volumes.find(volumeId)`
- `volumes.list()`
- `volumes.listByKind(kind)`
- `volumes.listByTag(tag)`
- `volumes.state(volumeId)`
- `volumes.setState(volumeId, stateTable)`
- `volumes.containsPoint(volumeId, x, y, z)`
- `volumes.containsEntity(volumeId, entity)`
- `volumes.containsPlayer(volumeId, player)`
- `volumes.remove(volumeId)`
- `volumes.clear()`

Volume options:

- `id`: optional stable volume id override
- `kind`: logical volume kind label
- `tags`: string list for filtering/grouping
- `affects`: `"players" | "entities" | "both"`
- `players`: boolean shortcut when `affects` is omitted
- `entities`: boolean shortcut when `affects` is omitted
- `force = {x,y,z}`: simple built-in push vector
- `state`: Lua table stored with the volume
- `onEnter(payload)`
- `onTick(payload)`
- `onLeave(payload)`

Volume notes:

- Volumes are per-mod and cleaned up automatically on disable/reload.
- Built-in `force` uses native entity velocity where possible.
- For players, built-in `force` is currently approximated with small teleports and therefore depends on `player-movement` capability.
- This is meant as a reusable overlap/runtime layer for currents, fans, soft blockers, and similar mechanics.

### Mechanics (adjacency graph runtime)

- `mechanics.register([worldUuid], x, y, z[, options])`
- `mechanics.unregister(nodeId)`
- `mechanics.clear()`
- `mechanics.find(nodeId)`
- `mechanics.list()`
- `mechanics.listByKind(kind)`
- `mechanics.listByTag(tag)`
- `mechanics.neighborsOf(nodeId)`
- `mechanics.link(fromNodeId, toNodeId[, options])`
- `mechanics.unlink(linkId)`
- `mechanics.links()`
- `mechanics.linksOf(nodeId)`
- `mechanics.findLink(linkId)`
- `mechanics.componentOf(nodeId)`
- `mechanics.path(fromNodeId, toNodeId)`
- `mechanics.markDirty(nodeId)`
- `mechanics.dirtyNodes()`
- `mechanics.state(nodeId)`
- `mechanics.setState(nodeId, stateTable)`

Mechanics options:

- `id`: optional stable node id override
- `kind`: logical node type label such as `gear`, `shaft`, `pipe`
- `tags`: string list for filtering/grouping
- `radius`: adjacency radius used for neighbor discovery
- `state`: Lua table stored with the node
- `onUpdate(payload)`: called every loader tick with `neighbors`

Mechanics notes:

- Nodes are per-mod and cleaned up automatically on disable/reload.
- Neighbor discovery is spatial and same-world only.
- Explicit links are also supported for graph-style systems that should not depend on proximity alone.
- Neighbor payloads include `delta`, `distance`, `axisAligned`, and `dominantAxis`.
- This is intentionally a generic graph/runtime layer so mods can implement their own speed, direction, or network rules.

### Simulation (generic system scheduling)

- `sim.register(options)`
- `sim.find(systemId)`
- `sim.list()`
- `sim.listByKind(kind)`
- `sim.listByTag(tag)`
- `sim.state(systemId)`
- `sim.setState(systemId, stateTable)`
- `sim.markDirty(systemId)`
- `sim.unregister(systemId)`
- `sim.clear()`

Simulation options:

- `id`: optional stable system id override
- `kind`: logical system kind
- `phase`: `pre_tick`, `tick`, or `post_tick`
- `tags`: string list for filtering/grouping
- `updateEveryTicks`: fixed-step cadence
- `maxCatchUpSteps`: cap on catch-up work for delayed ticks
- `state`: Lua table stored with the system
- `onStep(payload)`: fixed-step callback
- `onDirty(payload)`: callback when the system is explicitly dirtied

### Registry (typed data registries)

- `registry.define(registryId[, options])`
- `registry.find(registryId)`
- `registry.list()`
- `registry.listByKind(kind)`
- `registry.kinds()`
- `registry.put(registryId, key, value)`
- `registry.get(registryId, key)`
- `registry.has(registryId, key)`
- `registry.size(registryId)`
- `registry.entries(registryId)`
- `registry.keys(registryId)`
- `registry.removeEntry(registryId, key)`
- `registry.remove(registryId)`
- `registry.clear()`

Registry notes:

- redefining an existing registry id now preserves existing entries instead of wiping them

Standalone movement helpers:

- `volumes.move(volumeId, vec3)`
- `mechanics.move(nodeId, vec3)`

Registry options:

- `kind`: logical registry kind
- `defaults`: default fields shallow-merged into inserted map entries

### Transforms (logical attachment/anchor layer)

- `transforms.create(options)`
- `transforms.find(transformId)`
- `transforms.list()`
- `transforms.state(transformId)`
- `transforms.setState(transformId, stateTable)`
- `transforms.setParent(transformId, parentId)`
- `transforms.setPosition(transformId, vec3)`
- `transforms.setRotation(transformId, vec3)`
- `transforms.bindActor(transformId, actorId)`
- `transforms.bindEntity(transformId, entity)`
- `transforms.resolve(transformId)`
- `transforms.remove(transformId)`
- `transforms.clear()`

Transform options:

- `id`: optional stable transform id override
- `kind`: logical transform kind
- `tags`: string list for filtering/grouping
- `parentId`: optional parent transform
- `worldUuid`: optional base world
- `position`, `rotation`, `scale`
- `actorId` or `entity`: optional runtime anchor
- `state`: Lua table stored with the transform
- `onResolve(payload)`: callback after world transform resolution

### Spatial (generic geometry helpers)

- `spatial.directions()`
- `spatial.opposite(face)`
- `spatial.axis(face)`
- `spatial.rotateY(face[, turns])`
- `spatial.step(face[, amount])`
- `spatial.add(a, b)`
- `spatial.sub(a, b)`
- `spatial.scale(vec, scalar)`
- `spatial.distance(a, b)`
- `spatial.dominantAxis(vec)`
- `spatial.roundBlock(vec)`
- `spatial.neighbors(vec)`

### World

- `world.list()`
- `world.find(uuid)`
- `world.findByName(name)`
- `world.default()`
- `world.ofPlayer(player)`
- `world.players([uuid])`
- `world.playerCount([uuid])`
- `world.entities([uuid])`
- `world.entityCount([uuid])`
- `world.time([uuid])`
- `world.setTime(uuid, time)`
- `world.isPaused([uuid])`
- `world.setPaused(uuid, bool)`
- `world.blockAt([worldUuid], x, y, z)`
- `world.blockNameAt([worldUuid], x, y, z)`
- `world.blockIdAt([worldUuid], x, y, z)`
- `world.blockType(nameOrId)`
- `world.blockStateAt([worldUuid], x, y, z)`
- `world.setBlock([worldUuid], x, y, z, blockTypeOrId)`
- `world.setBlockState([worldUuid], x, y, z, state)`
- `world.queueSetBlock([worldUuid], x, y, z, blockTypeOrId)`
- `world.applyQueuedBlocks([limit])`
- `world.clearQueuedBlocks()`
- `world.queuedBlocks()`
- `world.batchGet([worldUuid], positions[, includeAir])`
- `world.batchSet([worldUuid], edits[, direct|queue|tx])` -> `{processed,applied,queued,failed,mode,capped,cap}`
- `world.txBegin()`
- `world.txSetBlock(...)`
- `world.txCommit([limit])`
- `world.txRollback()`
- `world.txStatus()`
- `world.neighbors([worldUuid], x, y, z)`
- `world.scanBox([worldUuid], minX, minY, minZ, maxX, maxY, maxZ[, limit])`
- `world.raycastBlock([worldUuid], ox, oy, oz, dx, dy, dz[, maxDist, step])`
- `world.broadcast(uuid, message)`
- `world.canControl()`

World edit cap behavior:

- `world.queueSetBlock` returns `-1` when queue cap is reached.
- `world.txSetBlock` returns `-1` when tx queue cap is reached.
- `world.batchSet` sets `capped=true` when operation count was limited.

### Blocks (behavior runtime)

- `blocks.register(blockTypeOrId, handlers)` where `handlers` may define `onPlaced`, `onBroken`, `onNeighborChanged`, `computeState`
- `blocks.unregister(blockTypeOrId)`
- `blocks.clear()`
- `blocks.list()`
- `blocks.recomputeAt([worldUuid], x, y, z[, reason])`
- `blocks.notifyNeighbors([worldUuid], x, y, z[, reason])`

Block behavior notes:

- Registrations are per mod and are cleared automatically on disable/reload.
- Keys match by block name (for example `hytale:stone`) or numeric block id (`42`).
- `computeState(payload)` may return a native block-state object; if it does, Arcane Loader applies it with `world.setBlockState(...)`.
- Behaviors currently run for:
  - direct `world.setBlock(...)`
  - queued edits when they are applied
  - transaction commits when they are applied
  - native `block_place` / `block_break` server events
- Neighbor propagation is orthogonal-only and bounded by `maxBlockBehaviorNeighborUpdatesPerCause` in `arcane-loader.json`.

Behavior payload fields:

- `key`
- `reason`
- `action`
- `sourceModId`
- `worldUuid`
- `x`
- `y`
- `z`
- `block`
- `liveBlock`
- `previousBlock`
- `state`
- `neighbor` (for neighbor-driven callbacks)

### Network (cross-mod + player network)

- `network.send(player, channel, payload)`
- `network.sendAll(channel, payload, [worldUuid])`
- `network.refer(player, host, port)`
- `network.referAll(host, port, [worldUuid])`
- `network.on(channel, fn(envelope))`
- `network.off(channel[, fn])`
- `network.list()`
- `network.emit(channel, payload)`
- `network.allowed(channel)`
- `network.policy(channel)` -> `{allowed, capabilityAllowed, reason, matchedPrefix?}`
- `network.canControl()`

Network envelope fields:

- `channel`
- `fromModId`
- `payload`
- `timestampMs`

### Webhooks (outbound HTTP)

- `webhook.request(method, url, [body], [contentType], [timeoutMs], [headers])`
- `webhook.postJson(url, payload, [timeoutMs], [headers])`
- `webhook.submit(method, url, [body], [contentType], [timeoutMs], [headers])` -> `requestId`
- `webhook.submitJson(url, payload, [timeoutMs], [headers])` -> `requestId`
- `webhook.poll(requestId)` -> `result | nil`
- `webhook.cancel(requestId)` -> `bool`
- `webhook.pending()` -> `count`
- `webhook.canControl()`

Webhook notes:

- Requires `network-control` capability.
- Only `http://` and `https://` URLs are allowed.
- Returns `{ok,status,body,error,durationMs,url}`.
- Prefer `submit` / `submitJson` from tick or event handlers so HTTP I/O does not block gameplay work.

### UI

- `ui.actionbar(player, message)`
- `ui.actionbarAll(message, [worldUuid])`
- `ui.title(player, title, subtitle, [fadeIn], [stay], [fadeOut])`
- `ui.titleAll(title, subtitle, [fadeIn], [stay], [fadeOut], [worldUuid])`
- `ui.form(player, id, payload)`
- `ui.formAll(id, payload, [worldUuid])`
- `ui.panel(player, id, payload)`
- `ui.panelAll(id, payload, [worldUuid])`
- `ui.canControl()`

### `ctx:*` compatibility surface

- `ctx:log(message)`
- `ctx:command(name, help, fn)`
- `ctx:on(event[, options], fn)`
- `ctx:off(event[, fn])`
- `ctx:setTimeout(ms[, label], fn)`
- `ctx:setInterval(ms[, label], fn)`
- `ctx:cancelTimer(handle)`
- `ctx:timerActive(handle)`
- `ctx:timers()`
- `ctx:timerCount()`
- `ctx:dataDir()`
- `ctx:readText(path)`
- `ctx:writeText(path, text)`
- `ctx:appendText(path, text)`

### Registries

- `registry.define(id[, options])`
- `registry.find(id)`
- `registry.list()`
- `registry.listByKind(kind)`
- `registry.kinds()`
- `registry.put(id, key, value)`
- `registry.get(id, key)`
- `registry.has(id, key)`
- `registry.size(id)`
- `registry.entries(id)`
- `registry.keys(id)`
- `registry.removeEntry(id, key)`
- `registry.remove(id)`
- `registry.clear()`

## 8) Built-in Server Event Bridge

Core:

- `server_start`, `server_stop`
- `pre_tick(payload)`, `tick(payload)`, `post_tick(payload)`
- `player_connect(payload)`, `player_disconnect(payload)`, `player_chat(payload)`, `player_ready(payload)`
- `player_world_join(payload)`, `player_world_leave(payload)`

Gameplay/world:

- `block_break`, `block_place`, `block_use`, `block_damage`
- `item_drop`, `item_pickup`
- `player_interact`, `player_craft`
- `entity_remove`, `entity_inventory_change`
- `world_add`, `world_remove`, `world_start`, `worlds_loaded`
- `chunk_save`, `chunk_unload`

Payload helpers (when supported by event type):

- `payload.cancel()`
- `payload.setCancelled(bool)`
- `payload.isCancelled()`
- `payload.setContent(text)` / `payload.getContent()` (chat-like events)

## 9) Practical Examples

### A) Safe mod config with defaults

```lua
local cfg = fs.readJson("config.json", {
  webhook = { enabled = false, url = "" },
  limits = { radius = 16 }
}, true) -- writeBack=true persists missing defaults
```

### B) Cross-mod communication

```lua
-- producer mod
local seq = 0
events.on("tick", function()
  seq = seq + 1
  network.emit("arcane.example.sync", { seq = seq })
end)

-- consumer mod
network.on("arcane.example.sync", function(env)
  log.info("got sync from " .. env.fromModId)
end)
```

### C) Discord webhook post

```lua
local res = webhook.postJson("https://discord.com/api/webhooks/...", {
  username = "Arcane Loader",
  content = "Server event fired"
}, 5000)

if not res.ok then
  log.warn("webhook failed status=" .. tostring(res.status) .. " err=" .. tostring(res.error))
end
```

### D) Async webhook from a hot path

```lua
local requestId = webhook.submitJson("https://discord.com/api/webhooks/...", {
  username = "Arcane Loader",
  content = "Tick-safe async event"
}, 5000)

events.on("tick", function()
  if not requestId then
    return
  end
  local result = webhook.poll(requestId)
  if result then
    requestId = nil
    log.info("async webhook finished status=" .. tostring(result.status))
  end
end)
```

See `examples/lua_mods/14_webhook_async_probe` for a runnable sample.

### E) High-volume world edits without spikes

```lua
-- queue edits in chunks
for x=0,100 do
  for z=0,100 do
    local q = world.queueSetBlock(nil, x, 80, z, "hytale:stone")
    if q == -1 then
      log.warn("queue cap reached, applying now")
      world.applyQueuedBlocks(2000)
    end
  end
end

-- apply within controlled budget
world.applyQueuedBlocks(5000)
```

### F) Probe native engine capability without Java changes

Use the `11_native_api_probe` example mod when you need to inspect what the current Hytale server build actually exposes.

Most useful commands:

- `/classprobe <fqcn>`
- `/methodprobe <fqcn> [prefix]`
- `/fieldprobe <fqcn> [prefix]`
- `/classdescribe <fqcn>`
- `/nativeprobe <baseline|models|attachments|collision|forces|transforms>`
- `/nativeprobe_dump <baseline|models|attachments|collision|forces|transforms>`

Recommended flow:

1. Run `/nativeprobe baseline`.
2. Run the focused groups you care about, such as `models`, `attachments`, `collision`, `forces`, or `transforms`.
3. If a class looks promising, inspect it with `/methodprobe`, `/fieldprobe`, and `/classdescribe`.
4. Use `/nativeprobe_dump <group>` to write a structured report under `lua_data/<modId>/probes/`.
5. For deeper Lua-side inspection, use `interop.resolveClass`, `interop.describeClass`, `interop.staticMethods`, `interop.staticFields`, `interop.constructors`, and `interop.newInstance`.

## 10) Determinism and Ordering

- Lifecycle/event fanout uses deterministic ordering.
- `loadBefore` / `loadAfter` are used when valid.
- Cycles fall back to stable case-insensitive mod ID order with warning.
- `disableAll` runs reverse lifecycle order.

## 11) Security Model

- Lua sandbox removes dangerous globals: `os`, `io`, `debug`, `luajava`, `dofile`, `loadfile`.
- Sensitive APIs are gated when `restrictSensitiveApis=true`.
- Network channels can be constrained by `networkChannelPolicies`.
- Sensitive actions are audit logged in `logs/arcane-audit.log`.

## 12) Operations and Troubleshooting

Recommended flow:

1. `/lua doctor`
2. `/lua new my_first_mod` if you do not have a mod yet
3. `/lua check`
4. `/lua config`
5. `/lua mods`
6. `/lua inspect <modId>`
7. `/lua errors [modId]`
8. `/lua caps [modId]`
9. `/lua policy <modId> <channel>`
10. `/lua netstats`
11. `/lua profile`

Useful outputs:

- `/lua profile dump` -> `logs/arcane-lua-profile-<timestamp>.log`
- audit log -> `logs/arcane-audit.log`
- `/lua errors <modId> detail` -> truncated stack trace excerpt in chat

Authoring diagnostics:

- `/lua new <modId> [blank|command|event]`
  - generates a starter mod directly in `lua_mods/`
  - keeps the beginner path to one command plus edit/check/reload
- `/lua check [modId|all]`
  - validates manifest JSON
  - checks entry file existence
  - compiles all `.lua` files for syntax errors
  - scans `require(...)` targets against mod-local module paths
  - warns on likely capability mismatches when restrictions are enabled
  - warns on duplicate mod ids and missing `loadBefore` / `loadAfter` targets
  - `dump` suffix writes JSON report under `logs/`
- `/lua inspect <modId>`
  - shows manifest/load-order/runtime/command/event/profile details for one mod

## 13) Validation and Release Checklist

- `./gradlew build`
- `./gradlew harnessVerify`
- `./gradlew harnessSeed -PmodCount=<N>`
- `./gradlew harnessAssert -PmodCount=<N>`
- Run server and check `/lua verify`
- Generate perf snapshot via `/lua profile dump`
- `./gradlew harnessPerfReport`
  - Optional alternate log path: `./gradlew harnessPerfReport -PprofileLogsDir=/path/to/logs`

## 14) Compatibility Policy

- Patch (`x.y.Z`): bug fixes only, no API removals.
- Minor (`x.Y.z`): additive changes only, existing signatures/behavior preserved.
- Major (`X.y.z`): breaking changes with migration notes.
- `interop.*` is advanced/best-effort and may vary with server internals.

## 15) License

Arcane Loader is distributed under a proprietary source-available license:

- See `LICENSE` for full terms.
- Use is allowed for running/developing mods.
- Redistribution/fork distribution and commercial use are not allowed without written permission.
