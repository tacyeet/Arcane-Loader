local M = {}
local lastPing = nil

function M.onEnable(ctx)
  network.on("arcane.examples.ping", function(envelope)
    lastPing = envelope
    local payload = envelope.payload or {}
    log.info("received ping from " .. tostring(envelope.fromModId) .. " seq=" .. tostring(payload.seq) .. " text=" .. tostring(payload.text))
  end)

  commands.register("netlast", "Show the last received cross-mod ping", function(sender, args)
    if lastPing == nil then
      if sender ~= nil then players.send(sender, "No ping received yet.") end
      return
    end

    local payload = lastPing.payload or {}
    local msg = "last ping from=" .. tostring(lastPing.fromModId) ..
      " seq=" .. tostring(payload.seq) ..
      " text=" .. tostring(payload.text)

    if sender ~= nil then
      players.send(sender, msg)
    else
      log.info(msg)
    end
  end)
end

function M.onDisable(ctx)
  network.off("arcane.examples.ping")
  commands.unregister("netlast")
end

return M
