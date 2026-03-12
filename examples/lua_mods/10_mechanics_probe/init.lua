local M = {}

function M.onEnable(ctx)
  commands.register("gearprobe", "Register a mechanics node at your feet.", function(sender, args)
    local pos = players.position(sender)
    if pos == nil then
      players.send(sender, "player position unavailable")
      return
    end

    local node = mechanics.register(nil, math.floor(pos.x), math.floor(pos.y), math.floor(pos.z), {
      kind = args[1] or "gear",
      tags = { "demo", "mechanical" },
      radius = tonumber(args[2] or "1.5") or 1.5,
      state = { speed = tonumber(args[3] or "1") or 1 },
      onUpdate = function(payload)
        local state = payload.state
        local maxNeighborSpeed = state.speed or 1
        for i = 1, #payload.neighbors do
          local neighbor = payload.neighbors[i]
          local neighborSpeed = tonumber(neighbor.state.speed or 0) or 0
          if neighbor.axisAligned and neighborSpeed > maxNeighborSpeed then
            maxNeighborSpeed = neighborSpeed
          end
        end
        state.networkSpeed = maxNeighborSpeed
        return state
      end
    })

    if node == nil then
      players.send(sender, "failed to register mechanics node")
      return
    end

    players.send(sender, "registered node " .. node.nodeId .. " kind=" .. tostring(node.kind))
  end)

  commands.register("gearlist", "List mechanics nodes for this mod.", function(sender, args)
    local list = args[1] ~= nil and args[1] ~= "" and mechanics.listByTag(args[1]) or mechanics.listByKind("gear")
    players.send(sender, "nodes=" .. tostring(#list))
    for i = 1, #list do
      local node = list[i]
      local speed = node.state ~= nil and node.state.networkSpeed or nil
      players.send(sender, node.nodeId .. " kind=" .. tostring(node.kind) .. " networkSpeed=" .. tostring(speed))
    end
  end)

  commands.register("gearneighbors", "Inspect neighbors for a node: /gearneighbors <nodeId>", function(sender, args)
    local nodeId = args[1]
    if nodeId == nil or nodeId == "" then
      players.send(sender, "usage: /gearneighbors <nodeId>")
      return
    end
    local neighbors = mechanics.neighborsOf(nodeId)
    players.send(sender, "neighbors=" .. tostring(#neighbors))
    for i = 1, #neighbors do
      local neighbor = neighbors[i]
      players.send(sender, neighbor.nodeId .. " axis=" .. tostring(neighbor.dominantAxis) .. " distance=" .. tostring(neighbor.distance))
    end
  end)

  commands.register("gearclear", "Clear all mechanics nodes for this mod.", function(sender, args)
    local removed = mechanics.clear()
    players.send(sender, "removed " .. tostring(removed) .. " nodes")
  end)
end

function M.onDisable(ctx)
  commands.unregister("gearprobe")
  commands.unregister("gearlist")
  commands.unregister("gearneighbors")
  commands.unregister("gearclear")
end

return M
