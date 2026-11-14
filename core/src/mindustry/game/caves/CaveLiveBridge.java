package mindustry.game.caves;

import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.modules.ItemModule;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Supplier;

import static mindustry.Vars.*;

/**
 * Experimental synchronous bridge to a real surface core. Not installed in
 * gameplay. Every method must run on the game thread while no other thread can
 * modify the world or core; a failed or conflicting response poisons the
 * session, which must then be discarded rather than saved from an old snapshot.
 */
public final class CaveLiveBridge implements AutoCloseable{
    private final Thread owner = Thread.currentThread();
    private final CavePersistentClient client;
    private final Supplier<CoreBuild> coreProvider;
    private final int team;
    private final String[] names;
    private boolean failed, closed;

    public CaveLiveBridge(CavePersistentClient client, Supplier<CoreBuild> coreProvider, int team){
        if(client == null || coreProvider == null || team < 0 || team > 255) throw new IllegalArgumentException("Invalid cave bridge");
        this.client = client;
        this.coreProvider = coreProvider;
        this.team = team;
        names = new String[content.items().size];
        for(Item item : content.items()) names[item.id] = item.name;
        if(!client.matchesIdentity(team, names)) throw new IllegalArgumentException("Worker team or content identity mismatch");
    }

    /** Perform actual cave ticks, then apply the resulting shared core items. */
    public void step(int ticks) throws IOException{
        ensureUsable();
        if(ticks < 1 || ticks > CavePersistentProtocol.maxStepTicks) throw new IllegalArgumentException("Invalid cave step size");
        try{
            Capture before = capture();
            int[] result = client.step(ticks, before.capacity, before.counts);
            ensureUnchanged(before);
            // Remove first to avoid overflowing ItemModule.total while replacing values.
            for(int i = 0; i < result.length; i++) if(result[i] < before.counts[i]) before.module.set(content.item(i), result[i]);
            for(int i = 0; i < result.length; i++) if(result[i] > before.counts[i]) before.module.set(content.item(i), result[i]);
        }catch(IOException | RuntimeException error){
            poison();
            throw error;
        }
    }

    /** Obtain an uncommitted cave snapshot matching the surface core right now. */
    public byte[] checkpoint() throws IOException{
        ensureUsable();
        try{
            Capture before = capture();
            var checkpoint = client.checkpoint(before.capacity, before.counts);
            ensureUnchanged(before);
            if(!Arrays.equals(before.counts, checkpoint.counts())) throw new IOException("Checkpoint inventory mismatch");
            return checkpoint.snapshot();
        }catch(IOException | RuntimeException error){
            poison();
            throw error;
        }
    }

    public boolean failed(){ return failed; }

    private Capture capture() throws IOException{
        CoreBuild core = coreProvider.get();
        if(core == null || core.team.id != team || core.items == null || core.items.length() != names.length || content.items().size != names.length){
            throw new IOException("Surface core or content changed");
        }
        int capacity = Math.min(core.storageCapacity, client.caveCapacity());
        if(capacity < 1) throw new IOException("Shared core has no capacity");
        int[] counts = new int[names.length];
        for(int i = 0; i < names.length; i++){
            Item item = content.item(i);
            if(item == null || !names[i].equals(item.name)) throw new IOException("Surface content order changed");
            counts[i] = core.items.get(item);
            if(counts[i] < 0 || counts[i] > capacity) throw new IOException("Surface core exceeds shared capacity");
        }
        return new Capture(core, core.items, capacity, core.storageCapacity, core.items.revision(), counts);
    }

    private void ensureUnchanged(Capture before) throws IOException{
        Capture after = capture();
        if(before.core != after.core || before.module != after.module || before.surfaceCapacity != after.surfaceCapacity ||
            before.capacity != after.capacity || before.revision != after.revision || !Arrays.equals(before.counts, after.counts)){
            throw new IOException("Surface core changed during cave simulation; session discarded");
        }
    }

    private void ensureUsable() throws IOException{
        if(Thread.currentThread() != owner) throw new IllegalStateException("Cave bridge must run on its owning game thread");
        if(closed || failed) throw new IOException("Cave simulation session is closed or failed");
    }

    private void poison(){ failed = true; client.discard(); }

    @Override public void close(){
        if(Thread.currentThread() != owner) throw new IllegalStateException("Cave bridge must close on its owning game thread");
        if(!closed){ closed = true; client.close(); }
    }

    private record Capture(CoreBuild core, ItemModule module, int capacity, int surfaceCapacity, long revision, int[] counts){}
}
