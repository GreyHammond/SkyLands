# Overworld Decay — design spec (feature/decay)

Status: draft v1. Decisions below were agreed in the design brainstorm.

## Premise

Players' presence in the overworld slowly corrupts the land around them. Corruption ends in
pure bedrock. Leaving an area lets the *decay value* fall, but bedrock that has already been
placed never heals. The overworld is recycled on a weekly timer (or by an admin), so a fresh
world replaces the corrupted one. Skylands (the sky world) are never affected.

## Decisions

| Topic | Decision |
|---|---|
| Where | Only the configured decay world (the overworld players fall into). Never the sky world. |
| Scope | Only near players. Unvisited land stays pristine; only a small leak spreads outward. |
| Bedrock | Permanent until the world is recycled. Lower stages (value) can heal; placed blocks do not revert. |
| Reset | Every 7 days, or `/skylandadmin` by an admin. |
| Player harm | None. No damage and no debuffs at 100%. Visuals + bedrock only. |
| Resist | Cleansing Tonic (levels 1-3, max 3 min) and Warded gear (4 pieces at level 3 = ~80% less decay). |

## Model

- Each overworld chunk has a decay value 0-100, stored in the chunk's persistent data.
- Every second, each active player (moved recently) adds decay to their chunk, scaled by an
  accelerating curve: `gain = base * resist * (1 + ramp * value/100)`. Neighbouring chunks get
  a fraction of that, up to a cap.
- Chunks with nobody near them lose decay slowly.
- Chunks above a leak threshold push a little decay into loaded neighbours, up to a small cap.

## Stages (thresholds configurable)

| Value | Effect |
|---|---|
| 0-25 | Ash particles and an action-bar readout only |
| 25-50 | Grass becomes coarse dirt, foliage dies, crops and spreading stop |
| 50-75 | Dirt becomes coarse dirt, stone becomes blackstone, logs vanish |
| 75-100 | Surface solids convert to bedrock, top-down, to a configured depth |

Conversion runs from a per-chunk rate and a global per-tick attempt budget so it never lags.
Blocks near players are never converted.

## Weekly recycle (planned, not in the first build)

1. Warn players with a countdown ("recycling to contain the corruption").
2. Send everyone in the decay world to their island (same path as Return to Island).
3. Unload the old world, then delete its folder (retry on Windows file locks).
4. Create a new world, using a rotating folder name per cycle so a stuck lock can never block it.
5. Point `worlds.overworld` and the decay world at the new world.

The plugin only deletes worlds it created itself and never the main world.
A dedicated resource world is required because Paper cannot delete the main world at runtime.

## Resist items (planned, not in the first build)

- **Cleansing Tonic** I/II/III: no decay caused by the drinker for 60 / 120 / 180 seconds.
- **Warded gear**: helmet, chestplate, leggings, boots. Each piece at level 3 cuts 20%; four
  pieces at level 3 cut 80%. Lower levels cut less. Implemented as plugin-managed item tags.
- Obtained through the Vault economy or island-level unlocks (`levels.yml`).

## Build order

1. Decay tracker, staged conversion, feedback, admin commands (this branch's first commit set).
2. Weekly recycle with the managed resource world.
3. Cleansing Tonic.
4. Warded gear.
5. Menu and Bedrock-form integration, docs, release notes.

## Safety

- Disabled by default. It refuses to run on the server's main world unless `decay.allow-main-world` is true.
- It never touches the sky world.
- Players are never trapped by conversion near them; `/ihome` and Return to Island always work.
