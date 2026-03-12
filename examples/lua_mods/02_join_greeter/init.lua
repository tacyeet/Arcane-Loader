local M = {}
local cfg

local defaults = {
  greeting = "Welcome, %s!",
  actionbar = "Arcane Loader: join-greeter active",
  saveJoins = true
}

local function playerFromPayload(payload)
  if payload == nil then return nil end
  return payload.player or payload.target or payload.entity
end

function M.onEnable(ctx)
  cfg = fs.readJson("config.json", defaults, true)

  events.on("player_ready", function(payload)
    local player = playerFromPayload(payload)
    if player == nil or not players.isValid(player) then return end

    local name = players.name(player)
    local greeting = string.format(cfg.greeting, name)
    players.send(player, greeting)

    if cfg.actionbar ~= nil and cfg.actionbar ~= "" then
      ui.actionbar(player, cfg.actionbar)
    end

    if cfg.saveJoins then
      fs.mkdir("logs")
      local line = os.date("!%Y-%m-%dT%H:%M:%SZ") .. " " .. name .. "\n"
      fs.append("logs/joins.log", line)
    end
  end)

  commands.register("joinlog", "Print saved joins for this mod", function(sender, args)
    if not fs.exists("logs/joins.log") then
      if sender ~= nil then players.send(sender, "No join logs yet.") end
      return
    end

    local body = fs.read("logs/joins.log")
    if sender ~= nil then
      players.send(sender, body)
    else
      log.info(body)
    end
  end)
end

function M.onDisable(ctx)
  commands.unregister("joinlog")
  events.clear("player_ready")
end

return M
