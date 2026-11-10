package mindustry.android;

/** Android JNI adapter for the Rust flowfield implementation. Android's class
 * loader resolves the ABI-specific library packaged under jniLibs. */
public final class NativeFlowfieldJNI{
    static{
        System.loadLibrary("mindustry_pathfinding");
    }

    private NativeFlowfieldJNI(){
    }

    public static int[] compute(int[] costs, int width, int height, int[] targets){
        return computeNative(costs, width, height, targets);
    }

    private static native int[] computeNative(int[] costs, int width, int height, int[] targets);
}
