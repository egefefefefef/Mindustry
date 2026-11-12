import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Same-process A/B of actual JNI entry points; not a full game or frame benchmark. */
public class PhysicsAllocationBench{
    private static native boolean baseline(float[] data, int[] local, float x, float y, float w, float h);
    private static native boolean cached(float[] data, int[] local, float x, float y, float w, float h);
    private static final int warmups = 60, samples = 120;

    static boolean call(boolean cache, float[] data, int[] flags){
        return cache ? cached(data, flags, 0, 0, 1024, 1024) : baseline(data, flags, 0, 0, 1024, 1024);
    }

    static float[] fixture(int count, boolean dense){
        float[] data = new float[count * 4];
        int cols = (int)Math.ceil(Math.sqrt(count));
        for(int i = 0; i < count; i++){
            data[i * 4] = 10 + (i % cols) * (dense ? 5.5f : 12f);
            data[i * 4 + 1] = 10 + (i / cols) * (dense ? 5.5f : 12f);
            data[i * 4 + 2] = 3;
            data[i * 4 + 3] = 1 + (i % 4) * 0.25f;
        }
        return data;
    }

    static void parity(int count, boolean dense){
        float[] data = fixture(count, dense), a = data.clone(), b = data.clone();
        int[] flags = new int[count];
        // Large input exercises the uncached path without a quadratic collision workload.
        Arrays.fill(flags, count > 16_384 ? 0 : 1);
        if(!call(false, a, flags) || !call(true, b, flags) || !Arrays.equals(a, b)) throw new AssertionError("parity " + count);
    }

    static void checkFallback(boolean cache){
        float[] data = {5, 5, 2, 1, 5, 5, 2, 1}, original = data.clone();
        if(call(cache, data, new int[]{1, 1}) || !Arrays.equals(data, original)) throw new AssertionError("stacked fallback");
        if(call(cache, data, new int[]{1}) || !Arrays.equals(data, original)) throw new AssertionError("shape fallback");
        data[2] = Float.NaN;
        original = data.clone();
        if(call(cache, data, new int[]{1, 1}) || !Arrays.equals(data, original)) throw new AssertionError("invalid fallback");
        parity(17, true); // a failed call must not corrupt the next reused buffer
    }

    public static void main(String[] args) throws Exception{
        System.load(args[0]);
        for(int n : new int[]{0, 1, 17, 256, 100, 1024, 16_385}) parity(n, false);
        checkFallback(false);
        checkFallback(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[4];
        for(int i = 0; i < threads.length; i++){
            threads[i] = new Thread(() -> {
                try{ for(int j = 0; j < 50; j++) parity(30 + j, true); }
                catch(Throwable t){ failure.set(t); }
            });
            threads[i].start();
        }
        for(Thread thread : threads) thread.join();
        if(failure.get() != null) throw new AssertionError("concurrent parity", failure.get());
        for(int count : new int[]{64, 256, 1024, 4096}) for(boolean dense : new boolean[]{false, true}){
            float[] initial = fixture(count, dense);
            float[][] data = {initial.clone(), initial.clone()};
            int[] flags = new int[count];
            Arrays.fill(flags, 1);
            long[] total = new long[2];
            for(int sample = -warmups; sample < samples; sample++){
                for(float[] array : data) System.arraycopy(initial, 0, array, 0, initial.length);
                for(int k = 0; k < 2; k++){
                    int mode = (sample + k) & 1;
                    long start = System.nanoTime();
                    boolean success = call(mode == 1, data[mode], flags);
                    long elapsed = System.nanoTime() - start;
                    if(!success) throw new AssertionError("unexpected fallback " + count);
                    if(sample >= 0) total[mode] += elapsed;
                }
                if(!Arrays.equals(data[0], data[1])) throw new AssertionError("output mismatch " + count);
            }
            System.out.printf(java.util.Locale.ROOT, "JNI-AB %d %s baseline_ms=%.4f cached_ms=%.4f%n", count, dense ? "dense" : "sparse", total[0] / (samples * 1e6), total[1] / (samples * 1e6));
        }
    }
}
