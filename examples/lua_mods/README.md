# Arcane Loader Example Mods

These mods are designed to be copied into the server root `lua_mods/` folder and reloaded with `/lua reload`.

Suggested order:

1. `01_hello_command`
2. `02_join_greeter`
3. `03_tick_clock`
4. `04_network_ping_producer`
5. `05_network_ping_consumer`
6. `06_world_floor_tool`
7. `07_block_behavior_probe`
8. `08_actor_probe`
9. `09_volume_current_probe`
10. `10_mechanics_probe`
11. `11_native_api_probe`
12. `12_stateful_counter`
13. `13_standin_probe`
14. `14_webhook_async_probe`

Each folder is self-contained (`manifest.json` + `init.lua`).
