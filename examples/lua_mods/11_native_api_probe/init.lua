local M = {}

local probeGroups = {
  models = {
    "com.hypixel.hytale.client.render.model.Model",
    "com.hypixel.hytale.server.core.entity.EntityModel",
    "com.hypixel.hytale.server.core.entity.ModelComponent"
  },
  attachments = {
    "com.hypixel.hytale.server.core.entity.AttachmentComponent",
    "com.hypixel.hytale.server.core.entity.PassengerComponent",
    "com.hypixel.hytale.server.core.entity.FollowerComponent"
  },
  collision = {
    "com.hypixel.hytale.server.core.physics.CollisionComponent",
    "com.hypixel.hytale.server.core.physics.TriggerVolume",
    "com.hypixel.hytale.server.core.physics.HitboxComponent"
  },
  forces = {
    "com.hypixel.hytale.server.core.physics.ForceComponent",
    "com.hypixel.hytale.server.core.entity.MovementComponent",
    "com.hypixel.hytale.server.core.entity.VelocityComponent"
  },
  transforms = {
    "com.hypixel.hytale.server.core.transform.Transform",
    "com.hypixel.hytale.server.core.universe.world.WorldTransform",
    "com.hypixel.hytale.server.core.universe.world.MovingWorld"
  },
  baseline = {
    "com.hypixel.hytale.server.core.HytaleServer",
    "com.hypixel.hytale.server.core.blocktype.BlockTypeModule",
    "com.hypixel.hytale.component.AddReason",
    "com.hypixel.hytale.server.core.universe.world.World",
    "com.hypixel.hytale.server.core.universe.Universe"
  }
}

local function summarizeClass(name)
  local desc = interop.describeClass(name)
  return {
    className = name,
    exists = desc.exists == true,
    constructors = desc.constructors or {},
    staticMethods = desc.staticMethods or {},
    staticFields = desc.staticFields or {}
  }
end

function M.onEnable(ctx)
  commands.register("classprobe", "Check if a native class exists: /classprobe <fqcn>", function(sender, args)
    local name = args[1]
    if name == nil or name == "" then
      players.send(sender, "usage: /classprobe <fully.qualified.ClassName>")
      return
    end
    local ok = interop.classExists(name)
    players.send(sender, name .. " exists=" .. tostring(ok))
  end)

  commands.register("methodprobe", "List static methods on a native class: /methodprobe <fqcn> [prefix]", function(sender, args)
    local name = args[1]
    if name == nil or name == "" then
      players.send(sender, "usage: /methodprobe <fqcn> [prefix]")
      return
    end
    local prefix = args[2] or ""
    local methods = interop.staticMethods(name, prefix)
    players.send(sender, "static methods=" .. tostring(#methods))
    for i = 1, math.min(#methods, 20) do
      players.send(sender, methods[i])
    end
  end)

  commands.register("fieldprobe", "List static fields and constructors on a native class: /fieldprobe <fqcn> [prefix]", function(sender, args)
    local name = args[1]
    if name == nil or name == "" then
      players.send(sender, "usage: /fieldprobe <fqcn> [prefix]")
      return
    end
    local prefix = args[2] or ""
    local fields = interop.staticFields(name, prefix)
    local ctors = interop.constructors(name)
    players.send(sender, "static fields=" .. tostring(#fields) .. " constructors=" .. tostring(#ctors))
    for i = 1, math.min(#fields, 15) do
      players.send(sender, fields[i])
    end
    for i = 1, math.min(#ctors, 10) do
      players.send(sender, ctors[i])
    end
  end)

  commands.register("nativeprobe", "Run a built-in native API probe set: /nativeprobe <baseline|models|attachments|collision|forces|transforms>", function(sender, args)
    local group = args[1] or "baseline"
    local probes = probeGroups[group]
    if probes == nil then
      players.send(sender, "unknown group; use baseline/models/attachments/collision/forces/transforms")
      return
    end
    players.send(sender, "probing group=" .. group)
    for i = 1, #probes do
      local name = probes[i]
      players.send(sender, name .. " exists=" .. tostring(interop.classExists(name)))
    end
  end)

  commands.register("classdescribe", "Describe a class and dump counts: /classdescribe <fqcn>", function(sender, args)
    local name = args[1]
    if name == nil or name == "" then
      players.send(sender, "usage: /classdescribe <fqcn>")
      return
    end
    local desc = interop.describeClass(name)
    players.send(sender, "exists=" .. tostring(desc.exists) .. " ctors=" .. tostring(#(desc.constructors or {})) .. " methods=" .. tostring(#(desc.staticMethods or {})) .. " fields=" .. tostring(#(desc.staticFields or {})))
  end)

  commands.register("nativeprobe_dump", "Write a probe group report to lua_data: /nativeprobe_dump <group>", function(sender, args)
    local group = args[1] or "baseline"
    local probes = probeGroups[group]
    if probes == nil then
      players.send(sender, "unknown group; use baseline/models/attachments/collision/forces/transforms")
      return
    end
    local report = {
      generatedAt = "runtime",
      group = group,
      classes = {}
    }
    for i = 1, #probes do
      report.classes[i] = summarizeClass(probes[i])
    end
    local path = "probes/" .. group .. ".json"
    fs.writeJson(path, report, true)
    players.send(sender, "wrote " .. path)
  end)
end

function M.onDisable(ctx)
  commands.unregister("classprobe")
  commands.unregister("classdescribe")
  commands.unregister("methodprobe")
  commands.unregister("fieldprobe")
  commands.unregister("nativeprobe")
  commands.unregister("nativeprobe_dump")
end

return M
