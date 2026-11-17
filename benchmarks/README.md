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
layers, rendering or multiplayer. The experimental Rust implementation is timed for the same scenarios.

Every case reports 20 warmups and 60 measured samples, with mean, p50, p95,
and maximum in milliseconds. The seed is fixed. Shared runners can vary in
CPU, JVM warmup, caching, and load, so repeat runs before drawing conclusions;
there are deliberately no pass/fail speed thresholds. Use representative
in-game profiles and larger samples for production decisions.

Experimental Rust unit physics is available on desktop and Android when the
matching native library is bundled and the JVM property
`-Dmindustry.experimentalNativePhysics=true` is set. It is **off by default in ordinary builds**, but enabled by default in the v1.1 and v1.2 desktop distributions. Override with `-Dmindustry.experimentalNativePhysics=false`. This is a prototype and has not been validated across all game, mod or
multiplayer scenarios. iOS and missing/incompatible libraries use Java.
Each layer has private reusable buffers; Rust copies results back only after a
successful step. Invalid inputs or exactly stacked bodies fall back to the
original Java step (including its random-angle behavior).

The benchmark checks Java versus Rust positions for varied synthetic scenes,
non-local bodies, a 120-step sequence, and the stacked-body fallback. It then alternates both
implementations on identical dense scenes; Rust timings include Java packing,
JNI copies, and output application. These checks do not prove determinism over
long games or mixed-platform network sessions.

## Flowfield optimization experiments

On the same GitHub runner, release-mode Rust-only tests alternated the v1.1
kernel and experimental candidates (16 warmups and 48 samples per case).
These timings exclude JNI and Java preparation; they are not frame times.

* Queue deduplication reduced duplicate work but was slower in the tested
  grids, including 256x256 weighted grids: 3.5934 ms baseline versus 4.4212 ms
  candidate. The change was rejected. Run: https://github.com/egefefefefef/Mindustry/actions/runs/43173434247
* Preallocating the queue did not show a consistent improvement: 256x256
  weighted grids were 3.2325 ms baseline versus 3.2695 ms candidate. The
  change was rejected. Run: https://github.com/egefefefefef/Mindustry/actions/runs/43173870820

The runs used different shared runners, so compare each candidate only with its
own baseline. Results do not rule out other algorithms or improvements in
real workloads. The added randomized parity tests preserve the original v1.1
kernel as a reference for future experiments.

## Physics JNI buffer experiment

An alternating same-JVM JNI test compared fresh JNI input buffers against
bounded per-thread buffer reuse on sparse and dense scenes (60 warmups, 120
samples). The full Rust physics kernel ran in both cases, and Java fixture
resets were outside the timer. At 4096 dense bodies, fresh versus reused was
2.6820 versus 2.6492 ms in one run and 3.4264 versus 3.4246 ms in another.
There was no convincing consistent gain; buffer reuse was rejected. Neither
test includes main-thread state preparation, scheduling, or rendering.
Runs: https://github.com/egefefefefef/Mindustry/actions/runs/43175705677 and
https://github.com/egefefefefef/Mindustry/actions/runs/43176069427.

## Physics quadtree allocation experiment

A second same-JVM JNI A/B experiment reused the input index vector for
straddling quadtree objects instead of allocating a new vector, preserving
their order. At 4096 dense bodies the reference and candidate measured
2.7301 vs 2.6545 ms in one run, but 2.2360 vs 2.2829 ms in another. Because
this small difference changed direction, the candidate was rejected. This is
not a measurement of full-frame performance. The benchmark also checked
exact baseline/candidate output for the measured scenes, varying input sizes,
stacked and invalid inputs, and concurrent calls. The regular Java/Rust
physics parity and multi-step tests passed as well. Runs:
https://github.com/egefefefefef/Mindustry/actions/runs/43176505662 and
https://github.com/egefefefefef/Mindustry/actions/runs/43176782301.
The experimental benchmark code is preserved in commit `02e58de`; it is not
part of the release build.

## In-game physics diagnostics

The performance overlay reports counts, not times or FPS. `Physics layers`
shows the most recently completed batch across the four collision layers:
`Rust` means the native kernel completed, `Java` means native physics was not
requested, and `fallback` means native physics was requested but Java ran
instead (e.g. unavailable library, unsupported input or iteration count).
Empty layers are omitted. `Layer steps` counts the same categories since the
world was loaded/reset. Several active layers can be counted in a single frame;
these are not counts of individual units. After pausing, the last completed
batch remains visible. Counters are collected after the worker tasks finish.
