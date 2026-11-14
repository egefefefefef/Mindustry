# Full cave background simulation: implementation notes

Status: **not implemented**. The currently enabled `CaveBackground` is an
approximation and must not be described as a full simulation. Nothing in this
note enables a second simulation or alters existing saves.

## Why isolation is required

`Vars.world`, `Vars.state`, `Groups`, Arc `Time`, events, pathfinding, physics,
content, and other subsystems currently represent a single live world. Loading
another map resets those global objects. Running the cave in another thread
against the same globals is therefore unsafe, even if the tile arrays differ.
A possible design is an isolated headless process, but the desktop package does
not currently include the headless backend and isolation has not been proven
for mods, settings, content IDs, native code or all platforms.

## Required transaction boundary

A full implementation must advance the underground world using the actual
simulation and synchronize its shared core with the visible world. A cave step
needs a consistent, versioned inventory at its start and must publish its result
only if the surface has not changed that inventory meanwhile. Conflicting or
failed steps must be rejected **and the entire cave step rolled back**; merging
independent inventory deltas after the fact is insufficient for capacities,
consumption, and conditional building behavior. `CaveInventoryLedger` is a
small tested prototype of this version check; it is not connected to the game,
does not isolate a world, and does not roll back a simulation.

Pausing, disabled features, worker termination, save/reload, map switching,
capacity changes and content mismatch all need explicit handling. Save data
must pair the committed cave state with exactly the matching core state; a
worker failure must never silently fall back to an older cave snapshot after
already crediting the core. Existing saves and the current approximation must
remain readable. A full implementation also needs a way to prevent both the old
approximation and the new worker from producing at once.

## Acceptance tests before enabling

- Mine through routers/bridges and consume real generator fuel, water and power;
  compare cave building state and shared inventory against an uninterrupted
  single-world reference run.
- Exercise simultaneous surface spending, full and reduced storage, paused time,
  switches, save/reload and repeated travel without loss or duplication.
- Kill the worker at each transaction/save boundary and test rollback/recovery.
- Cover units, logic processors, power graphs, mod/content identity and native
  fallback, and confirm campaigns, multiplayer and old saves are unaffected.
- Measure frame time, CPU and memory on representative Windows machines. No FPS
  or performance claim is justified until this has been measured.
