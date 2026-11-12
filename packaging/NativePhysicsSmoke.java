import arc.math.geom.Rect;
import mindustry.Vars;
import mindustry.async.PhysicsProcess.PhysicsWorld;
import mindustry.async.PhysicsProcess.PhysicsBackend;
import mindustry.async.PhysicsProcess.PhysicsWorld.PhysicsBody;
import mindustry.core.Version;
import mindustry.desktop.NativeFlowfieldJNI;
import mindustry.net.Net;

import java.lang.reflect.Field;

/** Runs against the packaged JAR and bundled JRE, without opening a game window. */
public class NativePhysicsSmoke{
    public static void main(String[] args) throws Exception{
        System.clearProperty("mindustry.experimentalNativePhysics");
        Version.init();
        String expectedVersion = args.length == 0 ? "1.2" : args[0];
        if(!expectedVersion.equals(Version.distributionVersion) || !Version.nativePhysicsDefault){
            throw new AssertionError("Expected " + expectedVersion + " with Rust physics enabled by default");
        }
        // Verify the developer panel is present in the actual packaged JAR.
        Class.forName("mindustry.ui.dialogs.DeveloperPanel", false, NativePhysicsSmoke.class.getClassLoader());
        Class.forName("mindustry.core.DevTestTools", false, NativePhysicsSmoke.class.getClassLoader());
        Vars.net = new Net(null);
        float[] expected = {10f, 10f, 3f, 1f, 15f, 10f, 3f, 1f};
        if(!NativeFlowfieldJNI.computePhysics(expected, new int[]{1, 1}, 0f, 0f, 100f, 100f)){
            throw new AssertionError("Packaged Rust physics library did not run");
        }
        PhysicsWorld world = new PhysicsWorld(new Rect(0f, 0f, 100f, 100f));
        PhysicsBody a = body(10f), b = body(15f);
        world.add(a);
        world.add(b);
        world.update();
        if(!nativeInitialized(world)) throw new AssertionError("Default physics did not try native backend");
        if(world.lastBackend() != PhysicsBackend.rust) throw new AssertionError("Rust backend diagnostic missing");
        if(Math.abs(a.x - expected[0]) > 0.0001f || Math.abs(b.x - expected[4]) > 0.0001f){
            throw new AssertionError("Default physics produced unexpected positions");
        }

        System.setProperty("mindustry.experimentalNativePhysics", "false");
        PhysicsWorld disabled = new PhysicsWorld(new Rect(0f, 0f, 100f, 100f));
        disabled.add(body(10f));
        disabled.add(body(15f));
        disabled.update();
        if(nativeInitialized(disabled)) throw new AssertionError("Explicit Java override was ignored");
        if(disabled.lastBackend() != PhysicsBackend.javaDisabled) throw new AssertionError("Java override diagnostic missing");
        PhysicsWorld fallback = new PhysicsWorld(new Rect(0f, 0f, 100f, 100f));
        fallback.add(body(5f));
        fallback.add(body(5f));
        if(fallback.update(true) || fallback.lastBackend() != PhysicsBackend.javaFallback){
            throw new AssertionError("Stacked-body fallback diagnostic missing");
        }
        System.out.println("Packaged " + expectedVersion + " Rust physics, Java override and diagnostics verified");
    }

    private static PhysicsBody body(float x){
        PhysicsBody body = new PhysicsBody();
        body.x = x;
        body.y = 10f;
        body.radius = 3f;
        body.mass = 1f;
        body.local = true;
        return body;
    }

    private static boolean nativeInitialized(PhysicsWorld world) throws Exception{
        Field bridgeField = PhysicsWorld.class.getDeclaredField("nativePhysics");
        bridgeField.setAccessible(true);
        Object bridge = bridgeField.get(world);
        Field initialized = bridge.getClass().getDeclaredField("initialized");
        initialized.setAccessible(true);
        return initialized.getBoolean(bridge);
    }
}
