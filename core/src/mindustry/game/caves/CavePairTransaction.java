package mindustry.game.caves;

import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.modules.ItemModule;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Supplier;

import static mindustry.Vars.*;

/** Game-thread-only capture and paired publication shared by experimental bridges. */
final class CavePairTransaction{
    private final CavePersistentClient client;
    private final Supplier<CoreBuild> coreProvider;
    private final CaveSaveStore store = CaveSaveStore.instance;
    private final int team;
    private final String[] names;
    private CaveArchive expected;

    CavePairTransaction(CavePersistentClient client, Supplier<CoreBuild> coreProvider, int team){
        if(client == null || coreProvider == null || team < 0 || team > 255) throw new IllegalArgumentException("Invalid cave bridge");
        this.client = client;
        this.coreProvider = coreProvider;
        this.team = team;
        names = new String[content.items().size];
        for(Item item : content.items()) names[item.id] = item.name;
        expected = store.archive();
        if(expected == null || expected.inCave() || CaveBackground.instance.plan() != null ||
            !client.matchesIdentity(team, names) || !client.matchesInitialSnapshot(expected.inactiveWorld())){
            throw new IllegalArgumentException("Cave worker does not match inactive archive or approximation is enabled");
        }
    }

    private void checkContext() throws IOException{
        if(state == null || !state.isGame() || state.isPaused() || state.rules.caveInterior || state.isCampaign() || state.isEditor() ||
            net == null || net.active() || store.isTransferring() || store.archive() != expected ||
            CaveBackground.instance.plan() != null || (control != null && control.saves != null && control.saves.isSaving())){
            throw new IOException("Cave session context changed");
        }
    }

    Capture capture() throws IOException{
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
        return new Capture(core, core.items, capacity, core.storageCapacity, core.items.revision(), counts);
    }

    void verify(Capture before) throws IOException{
        Capture after = capture();
        if(before.core != after.core || before.module != after.module || before.capacity != after.capacity ||
            before.surfaceCapacity != after.surfaceCapacity || before.revision != after.revision || !Arrays.equals(before.counts, after.counts)){
            throw new IOException("Surface changed during cave step; response discarded");
        }
    }

    void publish(Capture before, CavePersistentClient.Checkpoint response) throws IOException{
        int[] result = response.counts();
        CaveArchive next = new CaveArchive(false, expected.seed(), response.snapshot());
        verify(before);
        // All values and item IDs were validated. Reductions first keep the
        // module's aggregate total within the integer range during publication.
        for(int i = 0; i < result.length; i++) if(result[i] < before.counts[i]) before.module.set(content.item(i), result[i]);
        for(int i = 0; i < result.length; i++) if(result[i] > before.counts[i]) before.module.set(content.item(i), result[i]);
        store.setArchive(next);
        expected = next;
    }

    record Capture(CoreBuild core, ItemModule module, int capacity, int surfaceCapacity, long revision, int[] counts){}
}
