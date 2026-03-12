# Extensibility Roadmap

This file captures the broader direction for Arcane Loader beyond the first block-behavior runtime.

## Goal

Expose systems that let mod authors build new gameplay patterns instead of only scripting existing engine actions.

The target is not "add one feature like stairs". The target is to expose reusable primitives that can support:

- neighbor-aware blocks such as stairs, pipes, wires, and facades
- animated mechanical networks such as cogs, shafts, and belts
- push / pull / stop volumes such as currents, fans, conveyors, and soft walls
- custom entities or model-driven actors with animation state
- eventually, moving structures if the Hytale API exposes enough hooks

## What Was Added First

Phase 1 is now in place:

- `blocks.register(...)`
- `onPlaced`
- `onBroken`
- `onNeighborChanged`
- `computeState`

This is the smallest useful runtime for neighbor-aware content and state recomputation.

## Recommended Next Phases

### Phase 2: Logical Custom Entity Runtime

Add a new high-level `actors` API built on top of whatever Hytale already exposes for entities.

Target capabilities:

- spawn tracked logical actors with stable ids
- bind actor state to one or more backing engine entities
- store per-actor animation state, pose state, and arbitrary data
- tick/update hooks with rate limits
- optional model / asset binding if native hooks exist

This is the right foundation for mobs, projectiles, visual helpers, animated props, and moving interaction points.

Hard wall:

- true custom render models or animation graphs may be impossible unless Hytale exposes model registration or a usable proxy entity path

### Phase 3: Force / Constraint / Volume Runtime

Add a `physics` or `volumes` API for reusable world-space effects.

Target capabilities:

- push / pull / dampen entities or players inside shapes
- define currents, fans, launch pads, sticky regions, and soft blockers
- query overlap against players/entities
- support ticked force application and priority rules

This is the clean path for water currents, wind tunnels, conveyor behavior, and "walls" that stop or redirect movement.

Hard wall:

- real collision geometry is likely engine-owned; without native collision hooks this will be approximation, not a true custom hitbox system

### Phase 4: Mechanical Graph Runtime

Add a `mechanics` API for adjacency-driven networks.

Target capabilities:

- graph registration for neighboring nodes
- propagation of direction, speed, and other state through a network
- stable per-node compute/update lifecycle
- optional visual state output for models/actors/blocks

This is the right abstraction for Create-style systems. The important part is the graph/runtime layer, not only the rendered cog.

Hard wall:

- animation/render fidelity depends on whether actors/models can actually display custom poses or rotating proxies

### Phase 5: Moving Frame Runtime

Only attempt this after validating engine support.

Questions that must be answered first:

- can world content be attached to a movable transform?
- can collision volumes be created and moved at runtime?
- can render proxies be attached to those transforms?
- can chunk/block data be represented as something other than static world blocks?

If those answers are "no", a Valkyrien Skies style result is not actually implementable in-loader. At that point the best Arcane Loader can offer is a fake ship made of actors/entities plus custom movement logic.

## API Investigation Checklist

Before building Phase 2-5, probe Hytale for:

- spawnable entity types that can act as invisible or visual carriers
- model/animation component access
- attach/passenger/follower APIs
- runtime collision or trigger volumes
- knockback / force / velocity control over players
- native block-state mutation breadth
- world or transform abstractions beyond static chunk blocks

## Decision Rule

Prefer adding reusable runtimes over feature-specific helpers.

Good:

- `blocks`
- `actors`
- `volumes`
- `mechanics`

Bad:

- `world.makeStairs`
- `world.makeCog`
- `world.makeShip`

Feature-specific helpers can come later as examples built on top of the reusable systems.
