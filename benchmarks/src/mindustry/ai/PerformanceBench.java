package mindustry.ai;

import arc.math.geom.Rect;
import arc.struct.IntSeq;
import mindustry.Vars;
import mindustry.async.PhysicsProcess.PhysicsWorld;
import mindustry.async.PhysicsProcess.PhysicsBackend;
import mindustry.async.PhysicsProcess.PhysicsWorld.PhysicsBody;
import mindustry.net.Net;
import mindustry.desktop.NativeFlowfieldJNI;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Opt-in synthetic benchmark of the actual production flowfield and physics kernels. */
public class PerformanceBench{
    private static final int warmups = 20, samples = 60;
    private static final long seed = 781259L;
    private static final StringBuilder csv = new StringBuilder("scenario,implementation,size,warmups,samples,mean_ms,p50_ms,p95_ms,max_ms\n");
    private static final StringBuilder table = new StringBuilder("| Scenario | Implementation | Size | Mean ms | p50 ms | p95 ms | Max ms |\n|---|---|---:|---:|---:|---:|---:|\n");
    private static volatile long sink;

    public static void main(String[] args) throws Exception{
        Locale.setDefault(Locale.ROOT);
        if(args.length != 1) throw new IllegalArgumentException("Expected output directory");
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Vars.net = new Net(null);

        benchmarkFlowfield();
        benchmarkPhysics();

        String notes = "# Pathfinding and physics benchmark\n\n" +
            "Synthetic, single-process JVM measurements; shared GitHub runners are noisy. " +
            "Times are milliseconds per complete operation, not game FPS or full-frame time. " +
            "Each case has " + warmups + " warmup and " + samples + " measured iterations. " +
            "The fixed random seed is " + seed + ".\n\n" +
            "Flowfield: actual Pathfinder.updateFrontier with one goal and weighted, partly blocked grids; " +
            "Java runs to completion with the incremental time limit disabled; Rust includes the production JNI bridge, " +
            "cost-array preparation and result copies. Fixture resets are outside the timer. " +
            "Outputs are checked for equality; this does not test every game pathfinding scenario or ControlPathfinder.\n\n" +
            "Physics: actual PhysicsWorld.update, one collision layer with local bodies in a dense grid. " +
            "Body-position reset is outside the timer. This does not include game state, other layers, rendering, " +
            "thread scheduling or the main-thread synchronization costs. Rust includes packing, JNI and copying. " +
            "Java/Rust body positions are checked within 0.001 world units; unsupported stacked bodies fall back to Java. " +
            "Rust physics is experimental and disabled by default.\n\n" + table;
        Files.writeString(output.resolve("results.csv"), csv, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("results.md"), notes, StandardCharsets.UTF_8);
        System.out.println(notes);
    }

    private static void benchmarkFlowfield() throws Exception{
        Method update = Pathfinder.class.getDeclaredMethod("updateFrontier", Pathfinder.Flowfield.class, long.class);
        update.setAccessible(true);
        Field disabled = NativeFlowfield.class.getDeclaredField("disabled");
        disabled.setAccessible(true);
        Pathfinder finder = new Pathfinder();
        for(int size : new int[]{64, 128, 256}){
            Pathfinder.wwidth = Pathfinder.wheight = size;
            int[] costs = new int[size * size];
            Random rng = new Random(seed + size);
            for(int i = 0; i < costs.length; i++) costs[i] = rng.nextInt(100) < 12 ? -1 : 1 + rng.nextInt(4);
            int target = (size / 2) * size + size / 2;
            costs[target] = 1;
            finder.tiles = costs;
            BenchField field = new BenchField();
            field.setup();
            field.targets.add(target);
            long[][] timings = {new long[samples], new long[samples]};
            int[][] reference = new int[2][];
            // Alternate ordering to reduce systematic bias; fixture reset is outside timing.
            for(int i = -warmups; i < samples; i++){
                for(int k = 0; k < 2; k++){
                    int mode = (i + k) & 1; // 0 Java, 1 Rust + JNI
                    disabled.setBoolean(null, mode == 0);
                    Arrays.fill(field.weights, -1);
                    Arrays.fill(field.searches, (short)0);
                    field.search = 1;
                    field.frontier.clear();
                    field.weights[target] = 0;
                    field.searches[target] = 1;
                    field.frontier.addFirst(target);
                    field.hasComplete = false;
                    long start = System.nanoTime();
                    update.invoke(finder, field, -1L);
                    long elapsed = System.nanoTime() - start;
                    if(!field.hasComplete || field.frontier.size != 0) throw new IllegalStateException("Flowfield failed to finish");
                    if(mode == 1 && disabled.getBoolean(null)) throw new IllegalStateException("Native flowfield unavailable; refusing to benchmark fallback as Rust");
                    if(i == -warmups) reference[mode] = field.completeWeights.clone();
                    if(!Arrays.equals(reference[mode], field.completeWeights)) throw new IllegalStateException("Flowfield result changed between iterations");
                    sink += field.completeWeights[target];
                    if(i >= 0) timings[mode][i] = elapsed;
                }
            }
            if(!Arrays.equals(reference[0], reference[1])) throw new IllegalStateException("Java and Rust flowfield results differ for " + size);
            record("weighted flowfield", "Java", size, timings[0]);
            record("weighted flowfield", "Rust + JNI", size, timings[1]);
        }
    }

    private static void benchmarkPhysics(){
        validatePhysics();
        for(int count : new int[]{256, 1024, 4096}){
            int columns = (int)Math.ceil(Math.sqrt(count));
            PhysicsWorld[] worlds = {new PhysicsWorld(new Rect(0, 0, 1024, 1024)), new PhysicsWorld(new Rect(0, 0, 1024, 1024))};
            PhysicsBody[][] bodies = {new PhysicsBody[count], new PhysicsBody[count]};
            for(int mode = 0; mode < 2; mode++){
                for(int i = 0; i < count; i++){
                    PhysicsBody body = bodies[mode][i] = new PhysicsBody();
                    body.radius = 3f;
                    body.mass = 1f + (i % 4) * 0.25f;
                    body.local = true;
                    worlds[mode].add(body);
                }
            }
            long[][] timings = {new long[samples], new long[samples]};
            for(int sample = -warmups; sample < samples; sample++){
                for(int mode = 0; mode < 2; mode++){
                    for(int i = 0; i < count; i++){
                        bodies[mode][i].x = 20 + (i % columns) * 5.5f;
                        bodies[mode][i].y = 20 + (i / columns) * 5.5f;
                    }
                }
                for(int k = 0; k < 2; k++){
                    int mode = (sample + k) & 1;
                    long start = System.nanoTime();
                    boolean usedNative = worlds[mode].update(mode == 1);
                    long elapsed = System.nanoTime() - start;
                    if(usedNative != (mode == 1)) throw new IllegalStateException("Unexpected physics fallback for " + count);
                    PhysicsBackend backend = mode == 1 ? PhysicsBackend.rust : PhysicsBackend.javaDisabled;
                    if(worlds[mode].lastBackend() != backend) throw new IllegalStateException("Wrong physics diagnostic for " + count);
                    if(sample >= 0) timings[mode][sample] = elapsed;
                }
                checkPositions(bodies[0], bodies[1], "dense " + count);
                sink += Float.floatToIntBits(bodies[1][count - 1].x);
            }
            record("physics, one dense layer", "Java", count, timings[0]);
            record("physics, one dense layer", "Rust + JNI", count, timings[1]);
        }
    }

    private static void validatePhysics(){
        // Exercise changing radii, masses, non-local bodies, boundaries and empty regions.
        Random random = new Random(seed);
        for(int trial = 0; trial < 40; trial++){
            int count = 20 + random.nextInt(180);
            PhysicsWorld javaWorld = new PhysicsWorld(new Rect(-50, -40, 200, 180));
            PhysicsWorld nativeWorld = new PhysicsWorld(new Rect(-50, -40, 200, 180));
            PhysicsBody[] javaBodies = new PhysicsBody[count], nativeBodies = new PhysicsBody[count];
            for(int i = 0; i < count; i++){
                PhysicsBody a = javaBodies[i] = new PhysicsBody();
                PhysicsBody b = nativeBodies[i] = new PhysicsBody();
                a.x = b.x = -49 + random.nextFloat() * 198;
                a.y = b.y = -39 + random.nextFloat() * 178;
                a.radius = b.radius = 1f + random.nextFloat() * 8f;
                a.mass = b.mass = 0.5f + random.nextFloat() * 4f;
                a.local = b.local = random.nextInt(4) != 0;
                javaWorld.add(a);
                nativeWorld.add(b);
            }
            javaWorld.update(false);
            if(!nativeWorld.update(true)) throw new IllegalStateException("Unexpected Rust fallback in parity case " + trial);
            checkPositions(javaBodies, nativeBodies, "random " + trial);
        }
        // Check multiple consecutive updates without resetting positions. Single-step
        // agreement alone cannot detect accumulated divergence.
        PhysicsWorld javaWorld = new PhysicsWorld(new Rect(0, 0, 1024, 1024));
        PhysicsWorld nativeWorld = new PhysicsWorld(new Rect(0, 0, 1024, 1024));
        PhysicsBody[] javaBodies = new PhysicsBody[256], nativeBodies = new PhysicsBody[256];
        for(int i = 0; i < javaBodies.length; i++){
            PhysicsBody a = javaBodies[i] = new PhysicsBody();
            PhysicsBody b = nativeBodies[i] = new PhysicsBody();
            a.x = b.x = 20 + (i % 16) * 5.5f;
            a.y = b.y = 20 + (i / 16) * 5.5f;
            a.radius = b.radius = 3f;
            a.mass = b.mass = 1f + (i % 4) * 0.25f;
            a.local = b.local = i % 4 != 0;
            javaWorld.add(a);
            nativeWorld.add(b);
        }
        for(int tick = 0; tick < 120; tick++){
            javaWorld.update(false);
            if(!nativeWorld.update(true)) throw new IllegalStateException("Unexpected Rust fallback in multi-step case " + tick);
            checkPositions(javaBodies, nativeBodies, "multi-step " + tick);
        }

        // Exact overlap uses Java's random-angle rule: native must decline without
        // altering its input, and the production wrapper must fall back safely.
        float[] stacked = {5, 5, 2, 1, 5, 5, 2, 1};
        float[] saved = stacked.clone();
        if(NativeFlowfieldJNI.computePhysics(stacked, new int[]{1, 1}, 0, 0, 100, 100) || !Arrays.equals(stacked, saved)){
            throw new IllegalStateException("Stacked-body native fallback modified input");
        }
        PhysicsWorld world = new PhysicsWorld(new Rect(0, 0, 100, 100));
        for(int i = 0; i < 2; i++){
            PhysicsBody body = new PhysicsBody();
            body.x = body.y = 5f;
            body.radius = 2f;
            body.mass = 1f;
            world.add(body);
        }
        if(world.update(true)) throw new IllegalStateException("Stacked bodies should use Java fallback");
        if(world.lastBackend() != PhysicsBackend.javaFallback) throw new IllegalStateException("Fallback diagnostic was not recorded");
    }

    private static void checkPositions(PhysicsBody[] a, PhysicsBody[] b, String context){
        for(int i = 0; i < a.length; i++){
            if(!Float.isFinite(b[i].x) || !Float.isFinite(b[i].y) || Math.abs(a[i].x - b[i].x) > 0.001f || Math.abs(a[i].y - b[i].y) > 0.001f){
                throw new IllegalStateException("Physics mismatch " + context + " body " + i + ": Java=" + a[i].x + "," + a[i].y + " Rust=" + b[i].x + "," + b[i].y);
            }
        }
    }

    private static void record(String scenario, String implementation, int size, long[] values){
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(values).average().orElseThrow() / 1_000_000.0;
        double p50 = sorted[(int)Math.ceil(samples * 0.50) - 1] / 1_000_000.0;
        double p95 = sorted[(int)Math.ceil(samples * 0.95) - 1] / 1_000_000.0;
        double max = sorted[samples - 1] / 1_000_000.0;
        csv.append(String.format("\"%s\",%s,%d,%d,%d,%.4f,%.4f,%.4f,%.4f%n", scenario, implementation, size, warmups, samples, mean, p50, p95, max));
        table.append(String.format("| %s | %s | %d | %.4f | %.4f | %.4f | %.4f |%n", scenario, implementation, size, mean, p50, p95, max));
    }

    private static class BenchField extends Pathfinder.Flowfield{
        @Override
        public int getCost(int[] tiles, int pos){ return tiles[pos]; }
        @Override
        protected void getPositions(IntSeq out){}
    }
}
