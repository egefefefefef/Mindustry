package mindustry.game.caves;

import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static mindustry.Vars.*;

/**
 * Experimental single-flight bridge, NOT installed in gameplay. Construct,
 * begin, poll and close on the game thread and exclusively own the client.
 * Only IPC runs on its daemon thread; world and save access remain on the
 * owner. A failed, cancelled or stale response discards the entire worker.
 * The caller must track uncommitted ticks and coordinate pause, save, travel,
 * resets and worker startup; this class does not schedule or retry them.
 */
public final class CaveAsyncBridge implements AutoCloseable{
    public enum Status{ idle, pending, applied }

    private final Thread owner = Thread.currentThread();
    private final CavePersistentClient client;
    private final CavePairTransaction transaction;
    private final ExecutorService executor;
    private Future<CavePersistentClient.Checkpoint> pending;
    private CavePairTransaction.Capture before;
    private boolean failed, closed;

    public CaveAsyncBridge(CavePersistentClient client, Supplier<CoreBuild> coreProvider, int team){
        this.client = client;
        transaction = new CavePairTransaction(client, coreProvider, team);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Cave-step-prototype");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Start one bounded step without waiting for IPC; at most one may be outstanding. */
    public void begin(int ticks) throws IOException{
        ensureUsable();
        if(pending != null) throw new IllegalStateException("A cave step is already outstanding");
        if(ticks < 1 || ticks > CavePersistentProtocol.maxStepTicks) throw new IllegalArgumentException("Invalid cave step size");
        if(state != null && state.isPaused()) throw new IllegalStateException("Cave simulation is paused");
        try{
            var capture = transaction.capture();
            before = capture;
            pending = executor.submit(() -> client.stepCheckpoint(ticks, capture.capacity(), capture.counts()));
        }catch(IOException | RuntimeException error){
            discard();
            throw error;
        }
    }

    /** Poll once; never waits for worker IPC. Only applied publishes a paired result. */
    public Status poll() throws IOException{
        ensureUsable();
        if(pending == null) return Status.idle;
        if(!pending.isDone()) return Status.pending;
        try{
            var result = pending.get(); // already complete
            transaction.publish(before, result);
            pending = null;
            before = null;
            return Status.applied;
        }catch(InterruptedException error){
            Thread.currentThread().interrupt();
            discard();
            throw new IOException("Interrupted retrieving cave result", error);
        }catch(ExecutionException | CancellationException error){
            discard();
            throw new IOException("Cave worker failed; session discarded", error);
        }catch(IOException | RuntimeException error){
            discard();
            throw error;
        }
    }

    public boolean failed(){ return failed; }

    private void ensureUsable() throws IOException{
        if(Thread.currentThread() != owner) throw new IllegalStateException("Cave bridge must run on its owning game thread");
        if(closed || failed) throw new IOException("Cave simulation session is closed or failed");
    }

    private void discard(){
        failed = true;
        if(pending != null) pending.cancel(true);
        client.discard();
        executor.shutdownNow();
        pending = null;
        before = null;
    }

    /** Cancel any outstanding step and discard its uncommitted worker state. */
    @Override public void close(){
        if(Thread.currentThread() != owner) throw new IllegalStateException("Cave bridge must close on its owning game thread");
        if(!closed){
            closed = true;
            if(pending != null) pending.cancel(true);
            client.discard();
            executor.shutdownNow();
            pending = null;
            before = null;
        }
    }
}
