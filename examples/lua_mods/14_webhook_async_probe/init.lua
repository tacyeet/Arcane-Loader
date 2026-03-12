local pending = nil

local function describe(result)
  if not result then
    return "pending"
  end
  return "ok=" .. tostring(result.ok)
    .. " status=" .. tostring(result.status)
    .. " error=" .. tostring(result.error)
end

commands.register("webhook_async_probe", function(sender, args)
  if pending then
    local result = webhook.poll(pending)
    if result then
      log.info("async webhook finished " .. describe(result))
      pending = nil
    else
      log.info("async webhook still pending id=" .. tostring(pending))
    end
    return
  end

  pending = webhook.submitJson("https://httpbin.org/post", {
    modId = arcane.modId,
    probe = "async",
    timestampMs = 0
  }, 5000)

  log.info("submitted async webhook id=" .. tostring(pending))
end)

return {
  onEnable = function(ctx)
    log.info("webhook_async_probe enabled; use /lua call webhook_async_probe webhook_async_probe")
  end,
  onDisable = function(ctx)
    if pending then
      webhook.cancel(pending)
      pending = nil
    end
  end
}
