# Arcane Loader

Arcane Loader is a Lua mod loader plugin for Hytale servers.
It is built for fast iteration, deterministic behavior, and production safety controls.

## 1) Quick Start

### Requirements

- Java 25
- Hytale server files in `server/`

### Build

```
./gradlew build
```

Output jar:

- `build/libs/arcane-loader-1.0.0.jar`

### Install

1. Build the plugin.
2. Copy jar:

```
cp build/libs/arcane-loader-1.0.0.jar server/plugins/
```

3. Start the server from `server/` so paths resolve correctly.

### First sanity checks in server console

- `/lua mods`
- `/lua api`
- `/lua doctor`

## 2) Runtime Layout

Arcane Loader expects/creates:

- `lua_mods/`
- `lua_cache/`
- `lua_data/`
- `logs/`
- `arcane-loader.json`

## 3) Configuration (`arcane-loader.json`)

Default:

```json
{
  "devMode": true,
  "autoReload": false,
  "autoEnable": false,
  "slowCallWarnMs": 10.0,
  "blockEditBudgetPerTick": 256,
  "maxQueuedBlockEditsPerMod": 20000,
  "maxTxBlockEditsPerMod": 10000,
  "maxBatchSetOpsPerCall": 5000,
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
- `allowlistEnabled=true`: only mod IDs in `allowlist` are loaded.
- `restrictSensitiveApis=true`: capability arrays are enforced.
- `networkChannelPolicies`: prefix allowlist for network channels per mod.
- `blockEditBudgetPerTick`: automatic queued block edits applied per tick.
- `maxQueuedBlockEditsPerMod`: hard cap for `world.queueSetBlock` per mod.
- `maxTxBlockEditsPerMod`: hard cap for `world.txSetBlock` queue per mod.
- `maxBatchSetOpsPerCall`: hard cap per `world.batchSet` call.

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
  "version": "1.0.0",
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

- `/lua mods`
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
- `events.once(name, fn)`
- `events.emit(name, payload...)`
- `events.off(name[, fn])`
- `events.clear(name)`
- `events.list()`
- `events.count([name])`

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

### Reflection/Interop (advanced)

- `interop.server()`
- `interop.universe()`
- `interop.defaultWorld()`
- `interop.get(target, key)`
- `interop.set(target, key, value)`
- `interop.call(target, method, ...)`
- `interop.methods(target[, prefix])`
- `interop.typeOf(target)`

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
- `webhook.canControl()`

Webhook notes:

- Requires `network-control` capability.
- Only `http://` and `https://` URLs are allowed.
- Returns `{ok,status,body,error,durationMs,url}`.

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
- `ctx:on(event, fn)`
- `ctx:off(event[, fn])`
- `ctx:setTimeout(ms, fn)`
- `ctx:setInterval(ms, fn)`
- `ctx:cancelTimer(handle)`
- `ctx:timerActive(handle)`
- `ctx:dataDir()`
- `ctx:readText(path)`
- `ctx:writeText(path, text)`
- `ctx:appendText(path, text)`

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

### D) High-volume world edits without spikes

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
2. `/lua config`
3. `/lua mods`
4. `/lua errors [modId]`
5. `/lua caps [modId]`
6. `/lua policy <modId> <channel>`
7. `/lua netstats`
8. `/lua profile`

Useful outputs:

- `/lua profile dump` -> `logs/arcane-lua-profile-<timestamp>.log`
- audit log -> `logs/arcane-audit.log`

## 13) Validation and Release Checklist

- `./gradlew build`
- `./gradlew harnessVerify`
- `./gradlew harnessSeed -PmodCount=<N>`
- `./gradlew harnessAssert -PmodCount=<N>`
- Run server and check `/lua verify`
- Generate perf snapshot via `/lua profile dump`

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
