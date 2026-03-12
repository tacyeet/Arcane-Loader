local M = {}
local seq = 0

function M.onEnable(ctx)
  commands.register("netping", "Emit a cross-mod ping event", function(sender, args)
    seq = seq + 1
    local text = args[1] or "hello"
    local payload = {
      seq = seq,
      text = text,
      from = arcane.modId,
      at = os.date("!%Y-%m-%dT%H:%M:%SZ")
    }

    network.emit("arcane.examples.ping", payload)

    local msg = "sent ping seq=" .. tostring(seq) .. " text=" .. tostring(text)
    if sender ~= nil then
      players.send(sender, msg)
    else
      log.info(msg)
    end
  end)
end

function M.onDisable(ctx)
  commands.unregister("netping")
end

return M
