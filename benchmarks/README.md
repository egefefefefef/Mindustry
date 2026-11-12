# Synthetic performance benchmarks

Run `Tests` manually in GitHub Actions on the desired branch with
`run_benchmarks` enabled. The `pathfinding-physics-benchmark` artifact contains
`results.csv`, `results.md`, environment details, and the build log. Normal
pushes and pull requests do not run the benchmarks or create releases.

The benchmark calls the actual production `Pathfinder.updateFrontier` through
reflection, alternating the Java fallback with the Rust JNI path. It resets the
same deterministic weighted grid outside the timer, uses one goal, verifies
identical results, and fails if Rust silently falls back to Java. The Java
incremental time budget is disabled so both cases measure complete fields;
therefore these numbers are not frame-time or full-game measurements. Rust
measurements include Java cost preparation, JNI, and result copying.

It also times the actual `PhysicsWorld.update` for one synthetic, densely
packed layer. The fixture resets body positions outside the timer. This does
not include snapshot construction, main-thread synchronization, other physics
layers, rendering or multiplayer. There is no Rust physics implementation to
compare yet.

Every case reports 20 warmups and 60 measured samples, with mean, p50, p95,
and maximum in milliseconds. The seed is fixed. Shared runners can vary in
CPU, JVM warmup, caching, and load, so repeat runs before drawing conclusions;
there are deliberately no pass/fail speed thresholds. Use representative
in-game profiles and larger samples for production decisions.
