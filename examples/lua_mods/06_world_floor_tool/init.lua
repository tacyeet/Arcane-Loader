local M = {}

local function clamp(value, minValue, maxValue)
  if value < minValue then return minValue end
  if value > maxValue then return maxValue end
  return value
end

function M.onEnable(ctx)
  commands.register("makefloor", "Create a queued floor around you: /makefloor [radius<=16] [blockId]", function(sender, args)
    if sender == nil then
      log.warn("/makefloor is player-only")
      return
    end

    if not world.canControl() then
      players.send(sender, "world-control capability is not enabled for this mod")
      return
    end

    local radius = clamp(tonumber(args[1] or "5") or 5, 1, 16)
    local block = args[2] or "hytale:stone"

    local pos = players.position(sender)
    local y = math.floor(pos.y) - 1
    local cx = math.floor(pos.x)
    local cz = math.floor(pos.z)

    local queued = 0
    for x = cx - radius, cx + radius do
      for z = cz - radius, cz + radius do
        local result = world.queueSetBlock(nil, x, y, z, block)
        if result ~= -1 then
          queued = queued + 1
        end
      end
    end

    local applied = world.applyQueuedBlocks(5000)
    players.send(sender, "Queued " .. tostring(queued) .. " edits, applied " .. tostring(applied) .. ".")
  end)
end

function M.onDisable(ctx)
  commands.unregister("makefloor")
end

return M
