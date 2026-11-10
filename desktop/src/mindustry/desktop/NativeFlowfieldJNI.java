package mindustry.desktop;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Desktop JNI adapter for the Rust flowfield implementation. */
public final class NativeFlowfieldJNI{
    private static final boolean loaded = load();

    private NativeFlowfieldJNI(){
    }

    public static int[] compute(int[] costs, int width, int height, int[] targets){
        if(!loaded) throw new IllegalStateException("Rust pathfinding library is not available");
        return computeNative(costs, width, height, targets);
    }

    private static native int[] computeNative(int[] costs, int width, int height, int[] targets);

    private static boolean load(){
        String osName = System.getProperty("os.name", "").toLowerCase();
        String os = osName.contains("win") ? "windows" : osName.contains("mac") ? "macos" : "linux";
        String archName = System.getProperty("os.arch", "").toLowerCase();
        String arch = archName.equals("aarch64") || archName.equals("arm64") ? "aarch64" : "x86_64";
        String file = os.equals("windows") ? "mindustry_pathfinding.dll" :
            os.equals("macos") ? "libmindustry_pathfinding.dylib" : "libmindustry_pathfinding.so";
        String resource = "/native/" + os + "-" + arch + "/" + file;

        try{
            try{
                System.loadLibrary("mindustry_pathfinding");
            }catch(UnsatisfiedLinkError missingFromPath){
                try(InputStream input = NativeFlowfieldJNI.class.getResourceAsStream(resource)){
                    if(input == null) return false;
                    Path extracted = Files.createTempFile("mindustry-pathfinding-", os.equals("windows") ? ".dll" : os.equals("macos") ? ".dylib" : ".so");
                    Files.copy(input, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    extracted.toFile().deleteOnExit();
                    System.load(extracted.toAbsolutePath().toString());
                }
            }
            return true;
        }catch(Throwable ignored){
            return false;
        }
    }
}
