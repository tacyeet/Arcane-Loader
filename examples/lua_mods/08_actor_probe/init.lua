local M = {}

local function nearestEntity(sender, radius)
  return query.nearestEntity(sender, radius or 8)
end

function M.onEnable(ctx)
  commands.register("actorbind", "Bind the nearest entity into a managed actor and spin it every tick.", function(sender, args)
    if not entities.canControl() then
      players.send(sender, "entity-control capability is required")
      return
    end

    local target = nearestEntity(sender, tonumber(args[1] or "8") or 8)
    if target == nil then
      players.send(sender, "no nearby entity found")
      return
    end

    local actor = actors.bind(target, {
      kind = "spinner",
      tags = { "demo", "rotation" },
      state = {
        yaw = 0,
        step = tonumber(args[2] or "12") or 12
      },
      updateEveryTicks = 1,
      onTick = function(payload)
        local state = payload.state
        state.yaw = (state.yaw or 0) + (state.step or 12)
        entities.setRotation(payload.entity, state.yaw, 0, 0)
        return state
      end,
      onRemove = function(payload)
        log.info("actor removed id=" .. payload.actorId .. " reason=" .. tostring(payload.reason))
      end
    })

    if actor == nil then
      players.send(sender, "failed to bind actor")
      return
    end

    players.send(sender, "bound actor " .. actor.actorId .. " to entity " .. tostring(actor.entityId))
  end)

  commands.register("actorlist", "List managed actors for this mod.", function(sender, args)
    local mode = args[1] or ""
    local value = args[2] or ""
    local list
    if mode == "kind" and value ~= "" then
      list = actors.listByKind(value)
    elseif mode == "tag" and value ~= "" then
      list = actors.listByTag(value)
    else
      list = actors.list()
    end
    players.send(sender, "actors=" .. tostring(#list))
    for i = 1, #list do
      local actor = list[i]
      players.send(sender, actor.actorId .. " kind=" .. tostring(actor.kind) .. " entity=" .. tostring(actor.entityId) .. " owned=" .. tostring(actor.ownedEntity))
    end
  end)

  commands.register("actorremove", "Remove a managed actor by id.", function(sender, args)
    local actorId = args[1]
    if actorId == nil or actorId == "" then
      players.send(sender, "usage: /actorremove <actorId>")
      return
    end
    local ok = actors.remove(actorId, false)
    players.send(sender, ok and ("removed " .. actorId) or ("missing " .. actorId))
  end)
end

function M.onDisable(ctx)
  commands.unregister("actorbind")
  commands.unregister("actorlist")
  commands.unregister("actorremove")
end

return M
