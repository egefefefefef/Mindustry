package mindustry.server;

import arc.ApplicationListener;
import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.func.Cons;
import arc.mock.MockGraphics;
import arc.util.Time;

/** Headless backend whose fixed-step loop explicitly services Arc's main-thread queue. */
final class CaveWorkerApplication extends HeadlessApplication{
    CaveWorkerApplication(ApplicationListener listener, Cons<Throwable> errors){
        super(listener, errors);
    }

    /** Match the backend's ordering: posted tasks, frame ID, then global time. */
    void beginTick(){
        runnables.run();
        ((MockGraphics)Core.graphics).incrementFrameId();
        Time.updateGlobal();
    }

    /**
     * A save cannot serialize queued Runnables. Execute the tasks due before the
     * next update, and reject the snapshot if they enqueue another generation:
     * silently losing that generation on restart would corrupt simulation.
     */
    void prepareSnapshot(){
        runnables.run();
        assertSnapshotReady();
    }

    void assertSnapshotReady(){
        if(runnables.size() != 0) throw new IllegalStateException("Cave snapshot has pending deferred tasks");
    }
}
