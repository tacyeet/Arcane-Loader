# Native Capability Probes

Use the `11_native_api_probe` example mod to investigate hard engine limits without changing Java code.

## Commands

- `/classprobe <fqcn>`
- `/methodprobe <fqcn> [prefix]`
- `/fieldprobe <fqcn> [prefix]`
- `/classdescribe <fqcn>`
- `/nativeprobe <baseline|models|attachments|collision|forces|transforms>`
- `/nativeprobe_dump <baseline|models|attachments|collision|forces|transforms>`

## Why These Matter

These probes are intended to answer whether Hytale exposes the hooks needed for:

- custom models or animation control
- attachment / follower / passenger style systems
- collision or trigger primitives
- real force application instead of teleport approximations
- movable transforms or moving-world abstractions

## Recommended Workflow

1. Run `/nativeprobe baseline` to confirm the environment is responding.
2. Run `/nativeprobe models`, `/nativeprobe attachments`, `/nativeprobe collision`, `/nativeprobe forces`, and `/nativeprobe transforms`.
3. For any class that exists, run `/methodprobe <fqcn>`, `/fieldprobe <fqcn>`, and `/classdescribe <fqcn>`.
4. Use `/nativeprobe_dump <group>` to write a structured report under `lua_data/<modId>/probes/`.
5. If the class looks promising, use `interop.resolveClass`, `interop.describeClass`, `interop.staticMethods`, `interop.staticFields`, `interop.constructors`, and `interop.newInstance` from Lua for deeper targeted tests.

## Interpretation

Good signs:

- classes exist with methods around models, attachments, forces, transforms, or trigger/collision systems
- static factories or constructors are discoverable
- components expose setters/getters that imply runtime control

Bad signs:

- no matching classes exist
- only read-only metadata is exposed
- constructors/factories exist but no useful mutators do

If probes come back negative, the limit is probably Hytale’s API rather than Arcane Loader’s architecture.
