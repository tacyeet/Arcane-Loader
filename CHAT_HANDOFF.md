# Chat Handoff

This file is for resuming work in a new chat without rebuilding context.

## Repo State

- Working repo: `Arcane-Loader-main/`
- Parent folder also contains:
  - `HytaleServer.jar`
  - `arcane_loader_example_mods.zip`
  - some source zips / notes not part of repo
- Build currently works against `../HytaleServer.jar`

## Important Recent Changes

### 0. First-phase block behavior runtime was added

New Lua API:

- `blocks.register(blockTypeOrId, handlers)`
- `blocks.unregister(blockTypeOrId)`
- `blocks.clear()`
- `blocks.list()`
- `blocks.recomputeAt(...)`
- `blocks.notifyNeighbors(...)`

Handlers supported:

- `onPlaced`
- `onBroken`
- `onNeighborChanged`
- `computeState`

Coverage:

- direct `world.setBlock`
- queued edits when applied
- tx edits when committed
- native `block_place` / `block_break`

Neighbor propagation:

- orthogonal-only
- bounded by `maxBlockBehaviorNeighborUpdatesPerCause`

Related files:

- `src/main/java/arcane/loader/lua/LuaBlockBehaviorRuntime.java`
- `src/main/java/arcane/loader/lua/LuaEngine.java`
- `src/main/java/arcane/loader/lua/LuaModContext.java`
- `src/main/java/arcane/loader/lua/LuaModManager.java`
- `src/main/java/arcane/loader/ArcaneLoaderPlugin.java`
- `examples/lua_mods/07_block_behavior_probe/`
- `EXTENSIBILITY_ROADMAP.md`

### 0.1 First-phase actor runtime was added

New Lua API:

- `actors.spawn(...)`
- `actors.bind(entity, options)`
- `actors.exists(actorId)`
- `actors.find(actorId)`
- `actors.list()`
- `actors.entity(actorId)`
- `actors.state(actorId)`
- `actors.setState(actorId, stateTable)`
- `actors.remove(actorId[, despawnOwnedEntity])`

Capabilities:

- stable logical actor ids per mod
- actor `kind` + `tags`
- actor-owned Lua state tables
- `onTick(payload)` update loop
- `updateEveryTicks` throttle
- `onRemove(payload)` cleanup hook
- wraps existing / spawned native entities rather than inventing true custom engine entity classes

Related files:

- `src/main/java/arcane/loader/lua/LuaActorRuntime.java`
- `src/main/java/arcane/loader/lua/LuaEngine.java`
- `src/main/java/arcane/loader/lua/LuaModManager.java`
- `src/main/java/arcane/loader/lua/LuaModContext.java`
- `examples/lua_mods/08_actor_probe/`

### 0.2 First-phase volume runtime was added

New Lua API:

- `volumes.box(...)`
- `volumes.sphere(...)`
- `volumes.find(volumeId)`
- `volumes.list()`
- `volumes.state(volumeId)`
- `volumes.setState(volumeId, stateTable)`
- `volumes.remove(volumeId)`
- `volumes.clear()`

Capabilities:

- box and sphere overlap volumes
- volume `kind` + `tags`
- per-volume Lua state
- `onEnter`, `onTick`, `onLeave`
- optional built-in `force = {x,y,z}` handling
- contains helpers for points / entities / players

Important limitation:

- player push is currently approximated via movement/teleport hooks, not true physics impulses

Related files:

- `src/main/java/arcane/loader/lua/LuaVolumeRuntime.java`
- `src/main/java/arcane/loader/lua/LuaEngine.java`
- `src/main/java/arcane/loader/lua/LuaModManager.java`
- `src/main/java/arcane/loader/lua/LuaModContext.java`
- `examples/lua_mods/09_volume_current_probe/`

### 0.3 First-phase mechanics runtime was added

New Lua API:

- `mechanics.register(...)`
- `mechanics.unregister(nodeId)`
- `mechanics.clear()`
- `mechanics.find(nodeId)`
- `mechanics.list()`
- `mechanics.state(nodeId)`
- `mechanics.setState(nodeId, stateTable)`

Capabilities:

- same-world adjacency graph nodes
- node `kind` + `tags`
- per-node Lua state
- per-tick `onUpdate(payload)` with neighbor list
- neighbor payload now includes `delta`, `distance`, `axisAligned`, `dominantAxis`

Interpretation:

- this is the generic graph/runtime layer for Create-style systems
- it does not impose speed propagation rules; mods define those in Lua state/update logic

Related files:

- `src/main/java/arcane/loader/lua/LuaMechanicsRuntime.java`
- `src/main/java/arcane/loader/lua/LuaEngine.java`
- `src/main/java/arcane/loader/lua/LuaModManager.java`
- `src/main/java/arcane/loader/lua/LuaModContext.java`
- `examples/lua_mods/10_mechanics_probe/`

### 0.35 Generic graph/simulation/registry/transform helpers were added

Expanded Lua API:

- `mechanics.link(...)`, `mechanics.unlink(...)`, `mechanics.links()`, `mechanics.linksOf(...)`, `mechanics.findLink(...)`, `mechanics.componentOf(...)`, `mechanics.path(...)`, `mechanics.markDirty(...)`, `mechanics.dirtyNodes()`
- `sim.register(...)`, `sim.find(...)`, `sim.list(...)`, `sim.markDirty(...)`, `sim.clear()`
- `registry.define(...)`, `registry.put(...)`, `registry.get(...)`, `registry.entries(...)`, `registry.keys(...)`
- `transforms.create(...)`, `transforms.setParent(...)`, `transforms.bindActor(...)`, `transforms.bindEntity(...)`, `transforms.resolve(...)`
- `spatial.*` helpers for directions, axes, vector math, and neighbor positions

Purpose:

- make Create-like remakes easier without baking Create-specific rules into the loader
- provide reusable graph, scheduling, data-registry, and logical attachment primitives for many mod genres

Related files:

- `src/main/java/arcane/loader/lua/LuaMechanicsRuntime.java`
- `src/main/java/arcane/loader/lua/LuaSimulationRuntime.java`
- `src/main/java/arcane/loader/lua/LuaRegistryRuntime.java`
- `src/main/java/arcane/loader/lua/LuaTransformRuntime.java`
- `src/main/java/arcane/loader/lua/LuaEngine.java`
- `src/main/java/arcane/loader/lua/LuaModManager.java`
- `src/main/java/arcane/loader/lua/LuaModContext.java`

### 0.4 Native API probe helpers were added

New interop helpers:

- `interop.classExists(className)`
- `interop.resolveClass(className)`
- `interop.staticMethods(className[, prefix])`
- `interop.staticCall(className, method, ...)`

Purpose:

- probe native Hytale classes and static entry points from Lua
- reduce guesswork around models, attachments, transforms, and other hard API boundaries

Expanded helpers now also include:

- `interop.fields(target[, prefix])`
- `interop.staticFields(className[, prefix])`
- `interop.constructors(className)`
- `interop.newInstance(className, ...)`

Related files:

- `src/main/java/arcane/loader/lua/LuaEngine.java`
- `src/main/java/arcane/loader/lua/LuaModContext.java`
- `examples/lua_mods/11_native_api_probe/`
- `NATIVE_CAPABILITY_PROBES.md`

### 1. Build fix for updated Hytale layout

- `build.gradle` now auto-discovers `HytaleServer.jar` from common locations:
  - `server/HytaleServer.jar`
  - repo root `HytaleServer.jar`
  - parent dir `../HytaleServer.jar`
  - `-PhytaleServerJar=/absolute/path/...` override
- Added `javax.annotation:javax.annotation-api:1.3.2` for Java 25 compile compatibility

Status:

- `./gradlew build` passes in repo root

### 2. README was intentionally only partly rewritten

Quick Start now matches the actual runtime layout:

- use `HytaleServer.jar` from GitHub Releases
- put Arcane Loader in `server/plugins/`
- boot world once to generate runtime dirs
- put Lua mods into:
  - `server/lua_mods/`
- mention optional advanced Java hello-world plugin example

The rest of the README was mostly left alone on purpose.

### 3. Example mods were replaced with a cleaner curated pack

In-repo examples:

- `examples/lua_mods/01_hello_command`
- `examples/lua_mods/02_join_greeter`
- `examples/lua_mods/03_tick_clock`
- `examples/lua_mods/04_network_ping_producer`
- `examples/lua_mods/05_network_ping_consumer`
- `examples/lua_mods/06_world_floor_tool`

Also added:

- `examples/java-hello-plugin/`

The downloadable zip in the parent folder was rebuilt to match the curated Lua examples:

- `../arcane_loader_example_mods.zip`

### 4. Command UX cleanup

Explicit `all` support was added for bulk commands in `LuaRootCommand.java`:

- `/lua reload all`
- `/lua enable all`
- `/lua disable all`
- `/lua errors all`
- `/lua caps all`
- `/lua profile reset all`

Note:

- these mostly already treated no-arg as "all"; the change was to support the explicit argument users expect

### 5. Authoring diagnostics were expanded

New commands:

- `/lua new <modId> [blank|command|event]`
- `/lua check [modId|all]`
- `/lua inspect <modId>`
- `/lua errors <modId> detail`

Purpose:

- make mod debugging less dependent on raw server logs
- make first-time mod creation possible directly from the server command surface
- provide preflight syntax/manifest/dependency checks from inside the loader
- expose per-mod runtime details such as commands, event listeners, trace keys, and hot invocation buckets
- `/lua check ... dump` now writes a JSON report in `logs/`
- `check` also scans unresolved mod-local `require(...)` targets and likely capability mismatches
- `reload` / `enable` now print state-aware follow-up hints instead of only success text

### 6. Paper-style runtime polish

The Lua runtime now has a more structured event and timer surface:

- `events.on(name, options, fn)` / `events.once(name, options, fn)`
- event priorities: `LOWEST`, `LOW`, `NORMAL`, `HIGH`, `HIGHEST`, `MONITOR`
- `ignoreCancelled=true` support for cancellable payloads
- `events.listeners(name)` for listener inspection
- `ctx:setTimeout(ms, label, fn)` / `ctx:setInterval(ms, label, fn)`
- `ctx:timers()` and `ctx:timerCount()` for timer inspection

This keeps the old signatures working while making large mods easier to structure and debug.

### 7. Standard per-mod data helpers

Added a simple JSON `store` surface for the common case where mods need config or saved state without reimplementing file conventions:

- `store.path(name)`
- `store.exists(name)`
- `store.list()`
- `store.load(name[, defaults[, writeBack]])`
- `store.loadVersioned(name, schemaVersion[, defaults[, migrators[, writeBack]]])`
- `store.save(name, value[, pretty])`
- `store.saveVersioned(name, schemaVersion, value[, pretty])`
- `store.delete(name)`

Store files live under `lua_data/<modId>/stores/`.
There is now a small persisted-state example mod in `examples/lua_mods/12_stateful_counter`.

Registry ergonomics were also expanded:

- `registry.kinds()`
- `registry.has(id, key)`
- `registry.size(id)`
- redefining an existing registry now preserves existing entries

### 8. Query ergonomics

Added a small neutral `query` surface for common nearest/radius lookups:

- `query.withinDistance(a, b, radius)`
- `query.nearestPlayer(origin[, radius[, worldUuid]])`
- `query.nearestEntity(origin[, radius[, worldUuid]])`
- `query.nearestActor(origin[, radius[, kind]])`
- `query.nearestVolume(origin[, radius[, kind]])`
- `query.nearestNode(origin[, radius[, kind]])`

This is meant to remove repeated scan boilerplate across gameplay mods without locking the API to any one mod style.

### 9. Stand-in runtime

Added a composed `standins` runtime for loader-managed substitute objects:

- `standins.create(options)`
- `standins.spawn(typeOrPrototype, options)`
- `standins.find(id)`
- `standins.list()`
- `standins.count()`
- `standins.listByKind(kind)`
- `standins.listByTag(tag)`
- `standins.state(id)` / `standins.setState(id, state)`
- `standins.resolve(id)`
- `standins.actor(id)` / `standins.transform(id)` / `standins.volume(id)` / `standins.node(id)`
- `standins.has(id, component)`
- `standins.move(id, vec3)` / `standins.rotate(id, vec3)`
- `standins.attach(childId, parentId)`
- `standins.remove(id)` / `standins.clear()`

This runtime composes existing `actors`, `transforms`, `volumes`, and `mechanics` under one logical id so mods can model custom moving objects even when Hytale does not expose a matching native type.

Support hooks also added:

- `volumes.move(volumeId, vec3)`
- `mechanics.move(nodeId, vec3)`

There is now a stand-in example mod in `examples/lua_mods/13_standin_probe`.

`/lua inspect <modId>` now also reports active timer count and registry count.

## Files Most Recently Touched

- `build.gradle`
- `README.md`
- `src/main/java/arcane/loader/command/LuaRootCommand.java`
- `examples/lua_mods/...`
- `examples/java-hello-plugin/...`

## Validation Already Run

- Root project:
  - `bash ./gradlew clean compileJava`
  - `bash ./gradlew build`
- Java example:
  - `bash ../../gradlew clean build -PhytaleServerJar='/home/tacyeet/Downloads/hytale updated nooo/HytaleServer.jar'`

The Java example build was verified once, then its generated `.gradle/` and `build/` folders were removed from the example directory.

## Generated / Non-Repo Stuff To Ignore

Before committing, do not include:

- `.gradle/`
- `build/`
- `HytaleServer.jar`
- parent-folder helper zips unless intentionally tracked outside repo

If needed, add or verify `.gitignore` coverage.

## Current Capability Assessment

This was already reviewed in code before handoff.

### What Arcane Loader can do now

- world reads / writes / queued edits / tx edits
- block state get/set
- entity spawn / teleport / rotate / velocity
- event bridging for:
  - player events
  - block place/break/use/damage
  - some world and chunk events
- asset staging into `lua_assets/<modId>`
- reflection interop (`interop.get/set/call/methods`)

### Key limitations right now

- no first-class custom block registry
- no first-class block behavior system
- no direct collision / hitbox API
- no real moving-world / ship / rigid-body abstraction
- asset staging exists, but not asset registration into native game content by itself

### Practical interpretation

- Minecraft-style stairs:
  - partially possible by recomputing neighboring block states in Lua
  - not cleanly supported as a first-class feature yet
- rotating blocks:
  - only if backed by native block states / engine support
- invisible hitbox blocks:
  - not supported directly
- rotating entities:
  - supported
- Valkyrien Skies style ships:
  - not realistically supported as true moving voxel worlds with current loader surface
  - at best could be approximated with a new loader subsystem if Hytale API exposes enough hooks

## Next Likely Engineering Work

If continuing feature work, the strongest next steps are:

### Continue past the first block behavior runtime

Goal:

- keep building reusable runtimes instead of one-off helpers

Recommended order is documented in:

- `EXTENSIBILITY_ROADMAP.md`

Most likely next phases:

- `actors` runtime
- `volumes` / force runtime
- `mechanics` graph runtime
- only then investigate moving-frame / ship approximations

### For VS-like approximation

Only worth attempting if Hytale API exposes enough engine hooks.

Need to investigate:

- whether world transforms can exist independently of static chunk blocks
- whether collision volumes can be created or moved at runtime
- whether block/entity render proxies can be bound to moving transforms
- whether native physics bodies are available through API or only through unstable reflection

Without those, a loader-only solution can only fake a moving ship, not implement a real one.

## What The User Was Asking About Last

The last topic was:

- "how can we enable all these"
- specifically, what Arcane Loader would need to add to make advanced mod patterns more possible
- especially a Valkyrien Skies style example, while acknowledging Hytale API limits may be hard walls

The next useful response or work item is probably:

- propose and/or implement the first phase of a `blocks` behavior system
- document hard API boundaries clearly
- avoid promising true custom engine features that the Hytale API may not expose
