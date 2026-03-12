local M = {}

local state = {
  joins = 0,
  commands = 0
}

local autosaveHandle = nil

local function saveState()
  store.save("counter_state", state)
end

function M.onEnable(ctx)
  local loaded = store.loadVersioned("counter_state", 1, {
    joins = 0,
    commands = 0
  }, nil, true)

  if loaded ~= nil then
    state = loaded
  end

  events.on("player_join", {
    priority = events.priority.LOW
  }, function(payload)
    state.joins = (state.joins or 0) + 1
  end)

  autosaveHandle = ctx:setInterval(10000, "stateful_counter_autosave", function()
    saveState()
  end)

  commands.register("counterstats", "Show persisted counter stats and runtime info", function(sender, args)
    state.commands = (state.commands or 0) + 1
    saveState()

    local stores = store.list()
    local msg = "joins=" .. tostring(state.joins or 0)
      .. " commands=" .. tostring(state.commands or 0)
      .. " stores=" .. tostring(#stores)
      .. " timers=" .. tostring(ctx:timerCount())

    if sender ~= nil then
      players.send(sender, msg)
    else
      log.info(msg)
    end
  end)

  log.info("Enabled. Run /counterstats")
end

function M.onDisable(ctx)
  if autosaveHandle ~= nil then
    ctx:cancelTimer(autosaveHandle)
    autosaveHandle = nil
  end
  store.saveVersioned("counter_state", 1, state)
  commands.unregister("counterstats")
  events.clear("player_join")
end

return M
