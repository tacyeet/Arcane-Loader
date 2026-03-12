local M = {}
local ticks = 0

function M.onEnable(ctx)
  ticks = 0

  events.on("tick", function(payload)
    ticks = ticks + 1
  end)

  commands.register("clock", "Show uptime tracked by this mod", function(sender, args)
    local seconds = math.floor(ticks / 20)
    local msg = "tick-clock: ticks=" .. tostring(ticks) .. " (~" .. tostring(seconds) .. "s)"
    if sender ~= nil then
      players.send(sender, msg)
    else
      log.info(msg)
    end
  end)

  log.info("Enabled. Run /clock")
end

function M.onDisable(ctx)
  commands.unregister("clock")
  events.clear("tick")
end

return M
