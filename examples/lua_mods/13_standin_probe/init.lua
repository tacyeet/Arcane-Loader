local M = {}

local function senderPosition(sender)
  local pos = players.position(sender)
  if pos ~= nil then
    return pos
  end
  return vec3.new(0, 64, 0)
end

function M.onEnable(ctx)
  commands.register("standinspawn", "Spawn a managed stand-in near the sender.", function(sender, args)
    if not entities.canControl() then
      players.send(sender, "entity-control capability is required")
      return
    end

    local pos = senderPosition(sender)
    local standin = standins.spawn("hytale:skeleton", {
      kind = "spinner",
      tags = { "demo", "standin" },
      position = vec3.new(pos.x + 2, pos.y, pos.z),
      actor = {
        kind = "spinner_actor"
      },
      transform = true,
      node = {
        kind = "spinner_node",
        radius = 2.5
      },
      sync = {
        transformToNode = true
      },
      state = {
        yaw = 0,
        step = tonumber(args[1] or "10") or 10
      },
      onTick = function(payload)
        local state = payload.state
        state.yaw = (state.yaw or 0) + (state.step or 10)
        standins.rotate(payload.standinId, vec3.new(state.yaw, 0, 0))
        return state
      end
    })

    if standin == nil then
      players.send(sender, "failed to create stand-in")
      return
    end

    players.send(sender, "spawned stand-in " .. standin.standinId)
  end)

  commands.register("standinlist", "List managed stand-ins for this mod.", function(sender, args)
    local list = standins.list()
    players.send(sender, "standins=" .. tostring(#list))
    for i = 1, #list do
      local entry = list[i]
      players.send(sender, entry.standinId .. " kind=" .. tostring(entry.kind) .. " actor=" .. tostring(entry.actorId) .. " node=" .. tostring(entry.nodeId))
    end
  end)

  commands.register("standinmove", "Move a stand-in by id to the sender position.", function(sender, args)
    local standinId = args[1]
    if standinId == nil or standinId == "" then
      players.send(sender, "usage: /standinmove <standinId>")
      return
    end
    local pos = senderPosition(sender)
    local ok = standins.move(standinId, pos)
    players.send(sender, ok and ("moved " .. standinId) or ("failed to move " .. standinId))
  end)

  commands.register("standinremove", "Remove a stand-in by id.", function(sender, args)
    local standinId = args[1]
    if standinId == nil or standinId == "" then
      players.send(sender, "usage: /standinremove <standinId>")
      return
    end
    local ok = standins.remove(standinId)
    players.send(sender, ok and ("removed " .. standinId) or ("missing " .. standinId))
  end)
end

function M.onDisable(ctx)
  commands.unregister("standinspawn")
  commands.unregister("standinlist")
  commands.unregister("standinmove")
  commands.unregister("standinremove")
end

return M
