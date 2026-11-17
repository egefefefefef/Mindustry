package mindustry.async;

import arc.math.geom.Rect;
import mindustry.Vars;
import mindustry.async.PhysicsProcess.PhysicsBackend;
import mindustry.async.PhysicsProcess.PhysicsDiagnostics;
import mindustry.async.PhysicsProcess.PhysicsWorld;
import mindustry.async.PhysicsProcess.PhysicsWorld.PhysicsBody;
import mindustry.net.Net;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhysicsDiagnosticsTests{
    @BeforeAll
    static void initialize(){ Vars.net = new Net(null); }

    @Test
    void aggregatesNonemptyLayersAndResets(){
        PhysicsDiagnostics d = new PhysicsDiagnostics();
        d.beginStep();
        d.record(PhysicsBackend.none);
        d.record(PhysicsBackend.rust);
        d.record(PhysicsBackend.rust);
        d.record(PhysicsBackend.javaDisabled);
        d.record(PhysicsBackend.javaFallback);
        assertEquals("Physics layers: Rust 2 | Java 1 | fallback 1", d.lastLayers());
        assertEquals("Layer steps: Rust 2 | Java 1 | fallback 1", d.totals());
        d.beginStep();
        d.record(PhysicsBackend.javaFallback);
        assertEquals("Physics layers: Rust 0 | Java 0 | fallback 1", d.lastLayers());
        assertEquals("Layer steps: Rust 2 | Java 1 | fallback 2", d.totals());
        d.reset();
        assertEquals("Physics layers: Rust 0 | Java 0 | fallback 0", d.lastLayers());
        assertEquals("Layer steps: Rust 0 | Java 0 | fallback 0", d.totals());
    }

    @Test
    void emptyAndDisabledAndMissingBridgeAreDistinguished(){
        PhysicsWorld world = new PhysicsWorld(new Rect(0, 0, 100, 100));
        assertFalse(world.update(true));
        assertEquals(PhysicsBackend.none, world.lastBackend());
        PhysicsBody body = new PhysicsBody();
        body.x = body.y = 10;
        body.radius = body.mass = 1;
        world.add(body);
        assertFalse(world.update(false));
        assertEquals(PhysicsBackend.javaDisabled, world.lastBackend());
        // The test classpath has no desktop JNI bridge.
        assertFalse(world.update(true));
        assertEquals(PhysicsBackend.javaFallback, world.lastBackend());
        world.remove(body);
        assertFalse(world.update(true));
        assertEquals(PhysicsBackend.none, world.lastBackend());
    }
}
