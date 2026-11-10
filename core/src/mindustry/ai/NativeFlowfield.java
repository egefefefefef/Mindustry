package mindustry.ai;

import java.lang.reflect.Method;
import java.util.Arrays;

import mindustry.ai.Pathfinder.Flowfield;

/** Optional Rust implementation of the weighted enemy flowfield kernel. The
 * pathfinder's world access, tile-cost rules and Java fallback remain unchanged. */
final class NativeFlowfield{
    private static Method computeMethod;
    private static boolean initialized, disabled;

    private NativeFlowfield(){
    }

    /** Computes a complete field. Must only be called by the pathfinding thread. */
    static boolean compute(Flowfield path, int[] tiles){
        if(disabled || path.frontier.size == 0) return false;

        try{
            Method method = method();
            if(method == null) return false;

            int[] costs = new int[path.width * path.height];
            for(int i = 0; i < costs.length; i++){
                costs[i] = path.getCost(tiles, i);
            }

            int[] targets = path.targets.toArray();
            int[] weights = (int[])method.invoke(null, costs, path.width, path.height, targets);
            if(weights == null || weights.length != path.weights.length) throw new IllegalStateException("Invalid native flowfield result");

            System.arraycopy(weights, 0, path.weights, 0, weights.length);
            System.arraycopy(weights, 0, path.completeWeights, 0, weights.length);
            Arrays.fill(path.searches, (short)path.search);
            path.frontier.clear();
            path.hasComplete = true;
            return true;
        }catch(Throwable ignored){
            // A missing binary or bridge problem must never stop the game; disable
            // native dispatch for this process and continue with the Java algorithm.
            disabled = true;
            computeMethod = null;
            return false;
        }
    }

    private static Method method() throws Exception{
        if(initialized) return computeMethod;
        initialized = true;

        String bridge;
        if(present("android.os.Build")){
            bridge = "mindustry.android.NativeFlowfieldJNI";
        }else if(present("org.robovm.apple.foundation.NSObject")){
            bridge = "mindustry.ios.NativeFlowfieldBridge";
        }else{
            bridge = "mindustry.desktop.NativeFlowfieldJNI";
        }

        try{
            Class<?> type = Class.forName(bridge);
            computeMethod = type.getMethod("compute", int[].class, int.class, int.class, int[].class);
        }catch(ClassNotFoundException ignored){
            disabled = true;
        }
        return computeMethod;
    }

    private static boolean present(String name){
        try{
            Class.forName(name, false, NativeFlowfield.class.getClassLoader());
            return true;
        }catch(Throwable ignored){
            return false;
        }
    }
}
