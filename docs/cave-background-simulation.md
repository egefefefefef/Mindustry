# Full cave background simulation: implementation notes

Status: **not implemented**. The currently enabled `CaveBackground` is an
approximation and must not be described as a full simulation. Nothing in this
note enables a second simulation or alters existing saves.

## Why isolation is required

`Vars.world`, `Vars.state`, `Groups`, Arc `Time`, events, pathfinding, physics,
content, and other subsystems currently represent a single live world. Loading
another map resets those global objects. Running the cave in another thread
against the same globals is therefore unsafe, even if the tile arrays differ.
A possible design is an isolated headless process. The desktop package now
includes the experimental worker and headless backend, but isolation has not
been proven for mods, settings, content IDs, native code or all platforms.

`CaveSimulationWorker` is a separate-process prototype that loads one unpaired,
non-modded cave snapshot and advances the real Java logic for a bounded number of
fixed ticks before writing another snapshot. An optional versioned inventory
request and response identify items by name and bind both files to the input
and output snapshots using SHA-256. These are uncommitted proposals; tests
exercise discarding a stale result and retrying from the previous snapshot.
`CavePersistentService` is a second test-only prototype that retains the real
cave world in an isolated process across sequential commands instead of starting
and loading a new JVM each step. The fixed-step loop drains Arc's posted-task
queue and increments frame IDs; before a checkpoint it runs the next queued
generation and fails rather than serialize while another generation is pending.
This does not prove equivalence for wall-clock timers or arbitrary asynchronous
callbacks. It exchanges authoritative core counts and
can return a checkpoint, but its commands are not rollbackable. The prototype
`CavePersistentClient` validates content identity and snapshot hash, bounds
messages and response times, and discards the process on a failed request. The
caller must abort the entire session if a response cannot be committed; it must
never silently merge a stale response.

`CaveLiveBridge` is a synchronous, test-only experiment for exchanging with an
actual core on its owning game thread. It detects changed core identity, item
counts, capacity and content order, then poisons and discards the worker on a
conflict. A checkpoint is only an *uncommitted* snapshot: callers still need a
durable transaction for the two worlds, a recovery strategy for all previously
applied steps, and lifecycle/thread coordination. Its blocking IPC would cause
visible pauses and is not suitable for production gameplay. Integration tests
manually disable the approximate model and publish a checkpoint before a normal
save and travel; no production path does so yet.

`CavePairedBridge` is another synchronous experiment. Its combined worker
step/checkpoint response lets it allocate the next archive before publishing
both it and the corresponding core counts on the owning thread. It refuses a
mismatched initial snapshot, a concurrent change or an active approximate
plan; a failed step leaves the last published archive available for a worker
restart. This only holds when all world, save and archive access is serialized
on the owning thread. It is not a durable disk transaction, does not deal with
unsaved progress after a game crash, and does not yet handle lifecycle, worker
launch, asynchronous scheduling, or performance. In particular, making a full
snapshot every step can be expensive and has not been measured.

`CaveAsyncBridge` is a single-flight, game-thread-publication prototype: IPC
runs on a daemon thread, and polling does not wait for a worker response. The
live core's `ItemModule` has an in-memory mutation revision so spending and
replacing the same items while a request is outstanding is still detected.
Conflicts, failures, or cancellation discard the worker without publishing the
pending state; a test saves while a step is outstanding and reloads the last
published pair. This is not yet a scheduler: the caller must account for and
retry uncommitted ticks, cancel on every pause/travel/reset transition (even one
that changes back before polling), launch workers off-thread, and serialize
all game state and save access on the game thread. Snapshot copies and response
publication still do work on that thread. Save durability and wall-clock timer
semantics remain unresolved; the item revision's performance cost is unmeasured.

`CaveTickBudget` is a separate, game-thread-only accounting prototype for
fractional elapsed ticks and bounded step reservations. Failed or cancelled
reservations keep their ticks, and a budget snapshot includes in-flight ticks
for retry. `CaveAsyncScheduler` now combines the budget and the async bridge:
conflicts or a pause cancel the outstanding worker without consuming its ticks,
and a fresh session can retry from the last published archive. It is not wired
to the game clock, worker startup, lifecycle events, or the save format.
Frequent surface changes can repeatedly invalidate work; a production scheduler
must prove eventual progress without dropping time or blocking play. Retrying an
old cave interval against a newer surface inventory orders that entire interval
after the surface changes, so it is not necessarily equivalent to concurrent
shared-resource behavior. Exact interleaving needs a defined timeline, such as
resource-operation logging/replay or another coordination scheme, before this
can be enabled as a full simulation.

The worker classes are packaged in the desktop application but neither prototype
is launched or connected to gameplay. There is no production live inventory
synchronization, atomic two-world disk commit, crash recovery, pause
coordination, or performance measurement. Passing isolated tests is not proof
of full simulation parity.

## Required transaction boundary

A full implementation must advance the underground world using the actual
simulation and synchronize its shared core with the visible world. A cave step
needs a consistent, versioned inventory at its start and must publish its result
only if the surface has not changed that inventory meanwhile. Conflicting or
failed steps must be rejected **and the entire cave step rolled back**; merging
independent inventory deltas after the fact is insufficient for capacities,
consumption, and conditional building behavior. `CaveInventoryLedger` and
`CaveSimulationCoordinator` are tested prototypes of version checking and
in-memory paired publication. They are not connected to the game and do not
provide durable two-world commits or roll back an already-published simulation.

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
