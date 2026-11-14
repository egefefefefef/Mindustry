package mindustry.game.caves;

import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.io.IOException;
import java.util.function.Supplier;

import static mindustry.Vars.*;

/**
 * Experimental synchronous paired publication, NOT installed in gameplay.
 * The caller must exclusively own the client and serialize all world, archive,
 * inventory and save operations on the constructing game thread. A committed
 * step updates the real core and inactive archive together at that boundary;
 * it does not itself write a durable save or support concurrent readers.
 */
public final class CavePairedBridge implements AutoCloseable{
    private final Thread owner = Thread.currentThread();
    private final CavePersistentClient client;
    private final CavePairTransaction transaction;
    private boolean failed, closed;

    public CavePairedBridge(CavePersistentClient client, Supplier<CoreBuild> coreProvider, int team){
        this.client = client;
        transaction = new CavePairTransaction(client, coreProvider, team);
    }

    /** Advance real cave mechanics and publish the resulting snapshot and core counts together. */
    public void step(int ticks) throws IOException{
        ensureUsable();
        if(ticks < 1 || ticks > CavePersistentProtocol.maxStepTicks) throw new IllegalArgumentException("Invalid cave step size");
        if(state != null && state.isPaused()) throw new IllegalStateException("Cave simulation is paused");
        try{
            var before = transaction.capture();
            transaction.publish(before, client.stepCheckpoint(ticks, before.capacity(), before.counts()));
        }catch(IOException | RuntimeException error){
            failed = true;
            client.discard();
            throw error;
        }
    }

    public boolean failed(){ return failed; }

    private void ensureUsable() throws IOException{
        if(Thread.currentThread() != owner) throw new IllegalStateException("Cave bridge must run on its owning game thread");
        if(closed || failed) throw new IOException("Cave simulation session is closed or failed");
    }

    @Override public void close(){
        if(Thread.currentThread() != owner) throw new IllegalStateException("Cave bridge must close on its owning game thread");
        if(!closed){ closed = true; client.close(); }
    }
}
