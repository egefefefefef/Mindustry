package mindustry.game.caves;

import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.modules.ItemModule;

import java.io.IOException;
import java.util.Arrays;
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
    private final Supplier<CoreBuild> coreProvider;
    private final CaveSaveStore store;
    private final int team;
    private final String[] names;
    private CaveArchive expected;
    private boolean failed, closed;

    public CavePairedBridge(CavePersistentClient client, Supplier<CoreBuild> coreProvider, int team){
        if(client == null || coreProvider == null || team < 0 || team > 255) throw new IllegalArgumentException("Invalid cave bridge");
        this.client = client;
        this.coreProvider = coreProvider;
        this.team = team;
        store = CaveSaveStore.instance;
        names = new String[content.items().size];
        for(Item item : content.items()) names[item.id] = item.name;
        expected = store.archive();
        if(expected == null || expected.inCave() || CaveBackground.instance.plan() != null ||
            !client.matchesIdentity(team, names) || !client.matchesInitialSnapshot(expected.inactiveWorld())){
            throw new IllegalArgumentException("Cave worker does not match inactive archive or approximation is enabled");
        }
    }

    /** Advance real cave mechanics and publish the resulting snapshot and core counts together. */
    public void step(int ticks) throws IOException{
        ensureUsable();
        if(ticks < 1 || ticks > CavePersistentProtocol.maxStepTicks) throw new IllegalArgumentException("Invalid cave step size");
        // Pausing does not invalidate the worker, and must never advance it.
        if(state != null && state.isPaused()) throw new IllegalStateException("Cave simulation is paused");
        try{
            Capture before = capture();
            var response = client.stepCheckpoint(ticks, before.capacity, before.counts);
            int[] result = response.counts();
            // Allocate the new archive before touching the live inventory.
            CaveArchive next = new CaveArchive(false, expected.seed(), response.snapshot());
            ensureUnchanged(before);
            // All values and item IDs were validated before publication. Reductions
            // first keep the module's aggregate total within the int range.
            for(int i = 0; i < result.length; i++) if(result[i] < before.counts[i]) before.module.set(content.item(i), result[i]);
            for(int i = 0; i < result.length; i++) if(result[i] > before.counts[i]) before.module.set(content.item(i), result[i]);
            store.setArchive(next);
            expected = next;
        }catch(IOException | RuntimeException error){
            failed = true;
            client.discard();
            throw error;
        }
    }

    public boolean failed(){ return failed; }

    private void checkContext() throws IOException{
        if(state == null || !state.isGame() || state.isPaused() || state.rules.caveInterior || state.isCampaign() || state.isEditor() ||
            net == null || net.active() || store.isTransferring() || store.archive() != expected ||
            CaveBackground.instance.plan() != null || (control != null && control.saves != null && control.saves.isSaving())){
            throw new IOException("Cave session context changed");
        }
    }

    private Capture capture() throws IOException{
        checkContext();
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
        checkContext();
        return new Capture(core, core.items, capacity, core.storageCapacity, counts);
    }

    private void ensureUnchanged(Capture before) throws IOException{
        Capture after = capture();
        if(before.core != after.core || before.module != after.module || before.capacity != after.capacity ||
            before.surfaceCapacity != after.surfaceCapacity || !Arrays.equals(before.counts, after.counts)){
            throw new IOException("Surface changed during cave step; response discarded");
        }
    }

    private void ensureUsable() throws IOException{
        if(Thread.currentThread() != owner) throw new IllegalStateException("Cave bridge must run on its owning game thread");
        if(closed || failed) throw new IOException("Cave simulation session is closed or failed");
    }

    @Override public void close(){
        if(Thread.currentThread() != owner) throw new IllegalStateException("Cave bridge must close on its owning game thread");
        if(!closed){ closed = true; client.close(); }
    }

    private record Capture(CoreBuild core, ItemModule module, int capacity, int surfaceCapacity, int[] counts){}
}
