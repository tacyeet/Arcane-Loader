local M = {}

function M.onEnable(ctx)
  commands.register("currentbox", "Create a push volume around you: /currentbox [size] [dx] [dy] [dz]", function(sender, args)
    local pos = players.position(sender)
    if pos == nil then
      players.send(sender, "player position unavailable")
      return
    end

    local size = tonumber(args[1] or "3") or 3
    local dx = tonumber(args[2] or "0.2") or 0.2
    local dy = tonumber(args[3] or "0") or 0
    local dz = tonumber(args[4] or "0") or 0

    local volume = volumes.box(nil,
      pos.x - size, pos.y - 1, pos.z - size,
      pos.x + size, pos.y + 2, pos.z + size,
      {
        id = "current-box",
        kind = "current",
        tags = { "demo", "push" },
        force = { x = dx, y = dy, z = dz },
        onEnter = function(payload)
          log.info("entered volume " .. payload.volumeId .. " targetType=" .. tostring(payload.targetType))
        end,
        onTick = function(payload)
          local state = payload.state
          state.ticks = (state.ticks or 0) + 1
          return state
        end
      }
    )

    if volume == nil then
      players.send(sender, "failed to create current volume")
      return
    end

    players.send(sender, "created volume " .. volume.volumeId)
  end)

  commands.register("clearcurrents", "Remove all current probe volumes.", function(sender, args)
    local removed = volumes.clear()
    players.send(sender, "removed " .. tostring(removed) .. " volumes")
  end)

  commands.register("currentlist", "List current volumes, optionally filtered by tag: /currentlist [tag]", function(sender, args)
    local tag = args[1]
    local list = tag ~= nil and tag ~= "" and volumes.listByTag(tag) or volumes.listByKind("current")
    players.send(sender, "volumes=" .. tostring(#list))
    for i = 1, #list do
      local volume = list[i]
      players.send(sender, volume.volumeId .. " kind=" .. tostring(volume.kind) .. " shape=" .. tostring(volume.shape))
    end
  end)
end

function M.onDisable(ctx)
  commands.unregister("currentbox")
  commands.unregister("clearcurrents")
  commands.unregister("currentlist")
end

return M
