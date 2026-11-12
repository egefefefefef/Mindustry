package mindustry.async;

import arc.math.geom.Rect;
import mindustry.async.PhysicsProcess.PhysicsWorld.PhysicsBody;

import java.lang.reflect.Method;

/** Per-world bridge for experimental Rust physics. Java stays authoritative on failure. */
final class NativePhysics{
    private Method method;
    private boolean initialized, unavailable;
    private float[] data = new float[0];
    private int[] local = new int[0];

    boolean update(PhysicsBody[] bodies, int count, Rect bounds){
        if(unavailable) return false;
        try{
            if(!initialized){
                initialized = true;
                String bridge = present("android.os.Build") ? "mindustry.android.NativeFlowfieldJNI" : "mindustry.desktop.NativeFlowfieldJNI";
                method = Class.forName(bridge).getMethod("computePhysics", float[].class, int[].class, float.class, float.class, float.class, float.class);
            }
            if(local.length != count){
                local = new int[count];
                data = new float[Math.multiplyExact(count, 4)];
            }
            for(int i = 0; i < count; i++){
                PhysicsBody body = bodies[i];
                int p = i * 4;
                data[p] = body.x;
                data[p + 1] = body.y;
                data[p + 2] = body.radius;
                data[p + 3] = body.mass;
                local[i] = body.local ? 1 : 0;
            }
            if(!((boolean)method.invoke(null, data, local, bounds.x, bounds.y, bounds.width, bounds.height))) return false;
            for(int i = 0; i < count; i++){
                bodies[i].x = data[i * 4];
                bodies[i].y = data[i * 4 + 1];
            }
            return true;
        }catch(ReflectiveOperationException | LinkageError | RuntimeException e){
            unavailable = true;
            return false;
        }
    }

    private static boolean present(String name){
        try{
            Class.forName(name, false, NativePhysics.class.getClassLoader());
            return true;
        }catch(ClassNotFoundException | LinkageError ignored){
            return false;
        }
    }
}
