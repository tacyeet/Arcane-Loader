local M = {}

local observed = "hytale:stone"

function M.onEnable(ctx)
  blocks.register(observed, {
    onPlaced = function(payload)
      log.info("placed " .. payload.key .. " at " .. payload.x .. "," .. payload.y .. "," .. payload.z .. " reason=" .. payload.reason)
    end,

    onBroken = function(payload)
      log.info("broken " .. payload.key .. " at " .. payload.x .. "," .. payload.y .. "," .. payload.z .. " reason=" .. payload.reason)
    end,

    onNeighborChanged = function(payload)
      if payload.neighbor ~= nil and payload.neighbor.block ~= nil then
        local nx = payload.neighbor.x or 0
        local ny = payload.neighbor.y or 0
        local nz = payload.neighbor.z or 0
        log.info("neighbor update near " .. payload.x .. "," .. payload.y .. "," .. payload.z .. " from " .. nx .. "," .. ny .. "," .. nz)
      end
    end,

    computeState = function(payload)
      return nil
    end
  })

  commands.register("probeblock", "Place a watched block at your feet and trigger the behavior runtime.", function(sender, args)
    if not world.canControl() then
      players.send(sender, "world-control capability is required")
      return
    end

    local pos = players.position(sender)
    if pos == nil then
      players.send(sender, "player position unavailable")
      return
    end

    local x = math.floor(pos.x)
    local y = math.floor(pos.y) - 1
    local z = math.floor(pos.z)
    local block = args[1] or observed

    local ok = world.setBlock(nil, x, y, z, block)
    if ok then
      players.send(sender, "placed " .. block .. " at " .. x .. "," .. y .. "," .. z)
    else
      players.send(sender, "block placement failed")
    end
  end)
end

function M.onDisable(ctx)
  blocks.clear()
  commands.unregister("probeblock")
end

return M
