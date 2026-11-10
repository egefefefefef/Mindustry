package mindustry.ios;

import org.robovm.rt.VM;
import org.robovm.rt.bro.Bro;
import org.robovm.rt.bro.Struct;
import org.robovm.rt.bro.annotation.Bridge;
import org.robovm.rt.bro.annotation.Library;
import org.robovm.rt.bro.annotation.Pointer;
import org.robovm.rt.bro.ptr.IntPtr;

/** RoboVM C-ABI bridge for the Rust flowfield core. iOS does not provide JNI, so
 * the same Rust C entry point is called through a statically linked library. */
@Library("mindustry_pathfinding")
public final class NativeFlowfieldBridge{
    static{
        Bro.bind(NativeFlowfieldBridge.class);
    }

    private NativeFlowfieldBridge(){
    }

    @Bridge(symbol = "mindustry_flowfield_compute")
    private static native int computeNative(
        @Pointer long costs,
        int count,
        int width,
        int height,
        @Pointer long targets,
        int targetCount,
        @Pointer long output
    );

    public static int[] compute(int[] costs, int width, int height, int[] targets){
        int outputCount = costs.length;
        long costMemory = VM.malloc(outputCount * 4);
        long outputMemory = VM.malloc(outputCount * 4);
        long targetMemory = targets.length == 0 ? 0 : VM.malloc(targets.length * 4);
        if(costMemory == 0 || outputMemory == 0 || (targets.length > 0 && targetMemory == 0)){
            if(costMemory != 0) VM.free(costMemory);
            if(outputMemory != 0) VM.free(outputMemory);
            if(targetMemory != 0) VM.free(targetMemory);
            throw new OutOfMemoryError("Unable to allocate native pathfinding buffers");
        }

        try{
            Struct.toStruct(IntPtr.class, costMemory).set(costs);
            if(targets.length > 0) Struct.toStruct(IntPtr.class, targetMemory).set(targets);

            int success = computeNative(costMemory, outputCount, width, height, targetMemory, targets.length, outputMemory);
            if(success == 0) throw new IllegalStateException("Rust flowfield rejected its input");
            return Struct.toStruct(IntPtr.class, outputMemory).toIntArray(outputCount);
        }finally{
            VM.free(costMemory);
            VM.free(outputMemory);
            if(targetMemory != 0) VM.free(targetMemory);
        }
    }
}
