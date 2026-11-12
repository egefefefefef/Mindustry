package mindustry.async;

import arc.math.*;
import arc.math.geom.*;
import arc.math.geom.QuadTree.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.async.PhysicsProcess.PhysicsWorld.*;
import mindustry.core.*;
import mindustry.gen.*;

import java.util.concurrent.*;

public class PhysicsProcess implements AsyncProcess{
    static final int mobileIterations = 1, desktopIterations = 1;

    public static final int
    layers = 4,
    layerGround = 0,
    layerLegs = 1,
    layerFlying = 2,
    layerUnderwater = 3;

    //one independent world per layer; layers never interact, so each can run on its own thread
    private PhysicsWorld[] physics;
    private Seq<PhysicRef> refs = new Seq<>(false, 20, PhysicRef.class);
    private Seq<Future<?>> futures = new Seq<>(false, layers, Future.class);
    private Seq<PhysicRef> pendingAdds = new Seq<>(false, 20, PhysicRef.class);

    private static volatile long maxPhysicsTime = 0;
    // Written and read on the main thread after joining all physics futures.
    private final PhysicsDiagnostics diagnostics = new PhysicsDiagnostics();

    public PhysicsDiagnostics diagnostics(){
        return diagnostics;
    }

    /** Backend used by one non-empty layer in its last completed step. */
    public enum PhysicsBackend { none, rust, javaDisabled, javaFallback }

    /** Counts layer steps, not units or milliseconds. Reset on world reset/load. */
    public static class PhysicsDiagnostics{
        private int rust, javaDisabled, javaFallback;
        private long totalRust, totalJavaDisabled, totalJavaFallback;

        void beginStep(){ rust = javaDisabled = javaFallback = 0; }

        void record(PhysicsBackend backend){
            switch(backend){
                case rust -> { rust++; totalRust++; }
                case javaDisabled -> { javaDisabled++; totalJavaDisabled++; }
                case javaFallback -> { javaFallback++; totalJavaFallback++; }
                case none -> { }
            }
        }

        void reset(){
            beginStep();
            totalRust = totalJavaDisabled = totalJavaFallback = 0;
        }

        public String lastLayers(){
            return "Physics layers: Rust " + rust + " | Java " + javaDisabled + " | fallback " + javaFallback;
        }

        public String totals(){
            return "Layer steps: Rust " + totalRust + " | Java " + totalJavaDisabled + " | fallback " + totalJavaFallback;
        }
    }

    public void add(Unit unit){
        if(unit == null || unit.type == null || !unit.type.physics || unit.hasPhysicsRef) return;
        if(physics == null) init();

        unit.hasPhysicsRef = true;

        PhysicsBody body = new PhysicsBody();
        body.x = unit.x;
        body.y = unit.y;
        body.mass = unit.mass();
        body.radius = unit.hitSize * Vars.unitCollisionRadiusScale;

        PhysicRef ref = new PhysicRef(unit, body);
        refs.add(ref);

        //defer adds until physics update is done
        pendingAdds.add(ref);
    }

    @Override
    public void begin(){
        if(physics == null) return;
        boolean local = !Vars.net.client();

        PerfCounter.unitPhysicsWait.begin();

        //wait for every layer's async step to finish before touching body positions
        for(int i = 0; i < futures.size; i++){
            try{
                futures.items[i].get();
            }catch(InterruptedException | ExecutionException e){
                throw new RuntimeException(e);
            }
        }
        // A completed future establishes visibility of that layer's backend.
        if(futures.size > 0){
            diagnostics.beginStep();
            for(PhysicsWorld world : physics) diagnostics.record(world.lastBackend());
        }
        futures.clear();

        PerfCounter.unitPhysicsWait.end();
        PerfCounter.unitPhysicsAsync.add(maxPhysicsTime);
        maxPhysicsTime = 0;

        //flush newly added entities
        for(int i = 0; i < pendingAdds.size; i++){
           PhysicRef ref = pendingAdds.items[i];
           if(ref.lastLayer >= 0) physics[ref.lastLayer].add(ref.body);
        }
        pendingAdds.clear();

        //move entities
        for(PhysicRef ref : refs){
            Physicsc entity = ref.entity;

            //move by delta
            entity.move(ref.body.x - ref.startX, ref.body.y - ref.startY);
        }

        var items = refs.items;

        for(int i = 0; i < refs.size; i++){
            PhysicRef ref = items[i];
            //stale entity, remove it
            if(!ref.entity.isAdded()){
                ref.entity.hasPhysicsRef = false;
                refs.remove(i);
                i --;

                if(ref.lastLayer >= 0) physics[ref.lastLayer].remove(ref.body);
            }else{
                //check if layer changed
                int newLayer = ref.entity.collisionLayer();
                if(newLayer != ref.lastLayer){
                    if(ref.lastLayer >= 0) physics[ref.lastLayer].remove(ref.body);
                    ref.lastLayer = newLayer;
                    if(newLayer >= 0) physics[newLayer].add(ref.body);
                }

                ref.startX = ref.body.x = ref.entity.x;
                ref.startY = ref.body.y = ref.entity.y;
                ref.body.local = local || ref.entity.isLocal();
            }
        }

        //one task per world
        futures.clear();
        for(int i = 0; i < layers; i++){
            PhysicsWorld world = physics[i];
            futures.add(Vars.mainExecutor.submit(() -> { world.update(); }));
        }
    }

    @Override
    public boolean shouldProcess(){
        return false;
    }

    @Override
    public void reset(){
        diagnostics.reset();
        if(physics != null){
            refs.clear();
            futures.clear();
            physics = null;
        }
    }

    @Override
    public void init(){
        reset();

        Rect bounds = Vars.world.getQuadBounds(new Rect());
        physics = new PhysicsWorld[layers];
        for(int i = 0; i < layers; i++){
            physics[i] = new PhysicsWorld(bounds);
        }
    }

    public static class PhysicRef{
        public Unit entity;
        public PhysicsBody body;
        //position before simulation start
        public float startX, startY;
        public int lastLayer;

        public PhysicRef(Unit entity, PhysicsBody body){
            this.entity = entity;
            this.body = body;
            this.lastLayer = entity.collisionLayer();
            startX = entity.x;
            startY = entity.y;
        }
    }

    //world for simulating a single collision layer's physics, meant to run on its own thread
    public static class PhysicsWorld{
        //how much to soften movement by
        private static final float scl = 1.25f;

        private final QuadTree<PhysicsBody> tree;
        private final Seq<PhysicsBody> bodies = new Seq<>(false, 16, PhysicsBody.class);
        private final Seq<PhysicsBody> seq = new Seq<>(PhysicsBody.class);
        private final Vec2 vec = new Vec2();
        private final Rand rand = new Rand();
        private final NativePhysics nativePhysics = new NativePhysics();
        private PhysicsBackend lastBackend = PhysicsBackend.none;

        /** Read only after the layer's task has completed. */
        public PhysicsBackend lastBackend(){
            return lastBackend;
        }

        public PhysicsWorld(Rect bounds){
            tree = new QuadTree<>(new Rect(bounds));
        }

        public void add(PhysicsBody body){
            bodies.add(body);
        }

        public void remove(PhysicsBody body){
            bodies.remove(body);
        }

        public void update(){
            String override = System.getProperty("mindustry.experimentalNativePhysics");
            update(override == null ? Version.nativePhysicsDefault : Boolean.parseBoolean(override));
        }

        /** Runs one step and reports whether the experimental native kernel was used. */
        public boolean update(boolean preferNative){
            long begin = Time.nanos();

            var bodyItems = bodies.items;
            int bodySize = bodies.size;

            int iterations = Vars.net.client() || OS.isMobile ? mobileIterations : desktopIterations;
            lastBackend = bodySize == 0 ? PhysicsBackend.none :
                preferNative ? PhysicsBackend.javaFallback : PhysicsBackend.javaDisabled;

            // Each PhysicsWorld has independent buffers; a failed native call leaves
            // Java bodies untouched and falls back to the original implementation.
            if(preferNative && iterations == 1 && bodySize > 0 && nativePhysics.update(bodyItems, bodySize, tree.bounds)){
                lastBackend = PhysicsBackend.rust;
                maxPhysicsTime = Math.max(maxPhysicsTime, Time.timeSinceNanos(begin));
                return true;
            }

            for(int iter = 0; iter < iterations; iter++){
                tree.fill(bodies);

                for(int i = 0; i < bodySize; i++){
                    bodyItems[i].collided = false;
                }

                for(int i = 0; i < bodySize; i++){
                    PhysicsBody body = bodyItems[i];
                    //for clients, the only body that collides is the local one; all other physics simulations are handled by the server.
                    if(!body.local) continue;

                    seq.size = 0;
                    tree.intersect(body.x - body.radius, body.y - body.radius, body.radius * 2, body.radius * 2, seq);
                    int size = seq.size;
                    var items = seq.items;

                    for(int j = 0; j < size; j++){
                        PhysicsBody other = items[j];

                        if(other == body || other.collided) continue;

                        float rs = body.radius + other.radius;
                        float dx = body.x - other.x, dy = body.y - other.y;
                        float dst2 = dx * dx + dy * dy;

                        //skip the sqrt entirely for non-colliding pairs
                        if(dst2 < rs * rs){
                            float dst = Mathf.sqrt(dst2);
                            vec.set(dx, dy);

                            if(vec.isZero()){ //exact stacked bodies will move in random directions away from each other
                                vec.trns(rand.random(360f), rs - dst);
                            }else{
                                vec.setLength(rs - dst);
                            }

                            float ms = body.mass + other.mass;
                            float m1 = other.mass / ms, m2 = body.mass / ms;

                            //first body is always local due to guard check above
                            body.x += vec.x * m1 / scl;
                            body.y += vec.y * m1 / scl;

                            if(other.local){
                                other.x -= vec.x * m2 / scl;
                                other.y -= vec.y * m2 / scl;

                            }
                        }
                    }

                    body.collided = true;
                }
            }

            maxPhysicsTime = Math.max(maxPhysicsTime, Time.timeSinceNanos(begin));
            return false;
        }

        public static class PhysicsBody implements QuadTreeObject{
            public float x, y, radius, mass;
            public boolean collided = false, local = true;

            @Override
            public void hitbox(Rect out){
                out.setCentered(x, y, radius * 2, radius * 2);
            }
        }
    }
}