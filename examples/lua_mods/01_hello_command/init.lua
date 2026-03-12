local M = {}

function M.onEnable(ctx)
  commands.register("hello", "Print a hello message from Arcane Loader", function(sender, args)
    local msg = "Hello from " .. arcane.modId .. " (loader " .. tostring(arcane.loaderVersion) .. ")"
    log.info(msg)
    if sender ~= nil then
      players.send(sender, msg)
    end
  end)

  log.info("Enabled. Run /hello")
end

function M.onDisable(ctx)
  commands.unregister("hello")
end

return M
