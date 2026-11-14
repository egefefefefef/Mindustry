package mindustry.game.caves;

import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Experimental game-thread scheduler. It is not installed in gameplay or the
 * save format. Call update once per frame, including the pause transition; call
 * close before travel/reset/disable. Its snapshot must be saved atomically with
 * the paired worlds before it can be used for durable recovery.
 */
public final class CaveAsyncScheduler implements AutoCloseable{
    public enum Status{ idle, pending, stopped }

    private final Thread owner = Thread.currentThread();
    private final CaveAsyncBridge bridge;
    private final CaveTickBudget budget;
    private CaveTickBudget.Ticket ticket;
    private boolean closed;

    public CaveAsyncScheduler(CavePersistentClient client, Supplier<CoreBuild> core, int team, CaveTickBudget.Snapshot snapshot){
        budget = new CaveTickBudget(snapshot);
        bridge = new CaveAsyncBridge(client, core, team);
    }

    /** Add elapsed ticks, collect an available result, and submit at most one step. */
    public Status update(double delta, boolean playing) throws IOException{
        checkThread();
        if(closed) throw new IOException("Cave scheduler is stopped");
        budget.advance(delta, playing);
        if(!playing){
            // A worker may already have advanced, but the step is not published.
            // Discard it; its reserved ticks remain in the budget for a restart.
            if(ticket != null){ close(); return Status.stopped; }
            return Status.idle;
        }
        try{
            if(ticket != null){
                var result = bridge.poll();
                if(result == CaveAsyncBridge.Status.pending) return Status.pending;
                if(result != CaveAsyncBridge.Status.applied) throw new IOException("Cave step disappeared");
                budget.commit(ticket);
                ticket = null;
            }
            ticket = budget.reserve(CavePersistentProtocol.maxStepTicks);
            if(ticket == null) return Status.idle;
            bridge.begin(ticket.ticks());
            return Status.pending;
        }catch(IOException | RuntimeException error){
            close();
            throw error;
        }
    }

    /** Includes reserved ticks until the associated world and inventory commit. */
    public CaveTickBudget.Snapshot snapshot(){
        checkThread();
        return budget.snapshot();
    }

    private void checkThread(){
        if(Thread.currentThread() != owner) throw new IllegalStateException("Cave scheduler must run on its owning game thread");
    }

    @Override public void close(){
        checkThread();
        if(closed) return;
        closed = true;
        if(ticket != null){ budget.release(ticket); ticket = null; }
        bridge.close();
    }
}
