# Multiplayer caves: groundwork, not yet enabled

The requested mode allows different players to inhabit the surface and the cave
at the same time, both with a player-hosted server and a headless dedicated
server. **This is not implemented yet.** The existing paired-save hosting
restriction and all local-only cave guards must remain in place until the whole
feature is safe. `CavePlayerTransfers` is a standalone in-memory prototype; no
network packet, UI, player entity, save, or worker uses it.

## Constraints

- Mindustry uses one process-wide `Vars.world`, entity groups, logic, time and
  network state. The existing persistent worker has no multiplayer listener or
  player routing and its current synchronous snapshots cannot independently host
  connected players. Running a second world on another thread is unsafe.
- The existing cave archive is deliberately omitted from network snapshots;
  clients cannot independently restore the dormant world. The current transfer
  resets the whole active world and player, so it cannot transfer one player
  while others continue playing.
- Surface and cave cores need a single authoritative inventory per team. The
  local paired bridge handles only a stopped/checkpointed worker step for one
  team, and cannot merge a live remote player's operations or serve multiple
  teams. Save/reload and failure recovery must not duplicate or lose items.
- A graphical host is also a player, unlike a dedicated server. Transferring
  that local player must not stop the server or disconnect other clients.

## Prototype

`CavePlayerTransfers` records one authoritative world per authenticated,
connected player. One opaque server-side ticket permits at most one pending
handoff; commit or abort consumes it, and disconnect invalidates it. Unrelated
players can transfer concurrently. Tests cover independent travel, replay,
abort, reconnect, foreign tickets, invalid requests and racing commit/abort.
This registry does not authenticate identities, move or freeze entities, persist
state, communicate with clients or guarantee crash recovery. It must not be used
as a security token or be mistaken for an atomic multi-process transaction.

`CaveNetworkCheckpoint` is a separate, disconnected prototype for storing a
surface snapshot, a cave snapshot and player world/team locations in a single
bounded, SHA-256-checked file. A flushed temporary file is atomically renamed
into place; unsupported atomic moves fail without falling back to an unsafe copy.
Tests cover replacement, invalid data, corruption, concurrent reads and
serialization of real generated surface/cave saves. The latter test deliberately
restores the saves one at a time; it does not exercise multiplayer or shared
inventory. The caller must first establish a consistent, quiescent boundary across both worlds;
this class does not do so, does not persist unit state or in-flight transfers,
does not coordinate writers and does not guarantee directory-entry durability
on power loss. It is not wired to save/reload or either server runtime.

## Work required before enabling

1. Define a server-authoritative, isolated second world for both host types,
   including process lifecycle, authenticated transport and protection against
   exposing the private cave service. Route individual players and world packets
   without moving the host or other players. Establish compatible client and
   content versions, and handle reconnects and transfer timeouts.
2. Quiesce the source player and its unit, reserve the destination, then commit
   one ownership change. Make handoffs idempotent and recoverable if a client,
   server or cave process fails at every point. The in-memory prototype alone
   cannot implement this transaction or prevent duplicated entities.
3. Design a single authoritative shared inventory for every supported team and
   serialize spending, deposits, capacity changes, transfers and simulation in
   both worlds. Atomically persist a consistent pair of worlds, inventories and
   player locations; recover from partial writes and worker failure. Resolve
   legacy single-player paired saves and unsupported content safely.
4. Implement server-side cave generation and portal permissions, synchronize the
   clients' cave content and world data, support host and dedicated config, and
   specify behavior for PvP, team changes, disconnection, death, mods, and saves.
5. Test with real multiple clients and both host types: independent concurrent
   play, repeated travel, simultaneous item operations, save/reload, network loss,
   process failure and malicious/replayed requests. Measure latency and server
   cost. Do not enable multiplayer cave content or remove hosting guards until
   these cases pass.
