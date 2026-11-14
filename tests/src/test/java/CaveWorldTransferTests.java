import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.game.caves.CaveArchive;
import mindustry.game.caves.CaveSaveStore;
import mindustry.game.caves.CaveWorldTransfer;
import mindustry.io.SaveIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.zip.InflaterInputStream;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

class CaveWorldTransferTests{
    private final CaveSaveStore store = CaveSaveStore.instance;

    @BeforeAll
    static void init(){ ApplicationTests.launchApplication(); }

    @BeforeEach
    void load(){
        logic.reset();
        world.loadMap(ApplicationTests.testMap);
        state.set(State.playing);
        assertNotNull(Team.sharded.core());
    }

    private void restore(byte[] bytes){
        SaveIO.load(new InflaterInputStream(new ByteArrayInputStream(bytes)), world.context);
        state.set(State.playing);
    }

    private void prepareTwoWorlds(){
        world.tile(5, 5).setBlock(Blocks.copperWall, Team.sharded);
        Team.sharded.core().items.clear();
        Team.sharded.core().items.add(Items.copper, 100);
        byte[] surface = store.captureWorld();
        world.tile(5, 5).setBlock(Blocks.titaniumWall, Team.sharded);
        Team.sharded.core().items.clear();
        Team.sharded.core().items.add(Items.lead, 3);
        byte[] cave = store.captureWorld();
        restore(surface);
        store.setArchive(new CaveArchive(false, 123, cave));
    }

    @Test
    void roundTripPreservesWorldsAndUsesOneSharedCoreInventory(){
        prepareTwoWorlds();
        CaveWorldTransfer.travel();
        assertTrue(store.archive().inCave());
        assertSame(Blocks.titaniumWall, world.tile(5, 5).block());
        assertEquals(100, Team.sharded.core().items.get(Items.copper));
        assertEquals(0, Team.sharded.core().items.get(Items.lead));
        Team.sharded.core().items.set(Items.copper, 40);
        Team.sharded.core().items.add(Items.thorium, 12);
        world.tile(6, 6).setBlock(Blocks.graphitePress, Team.sharded);
        CaveWorldTransfer.travel();
        assertFalse(store.archive().inCave());
        assertSame(Blocks.copperWall, world.tile(5, 5).block());
        assertEquals(40, Team.sharded.core().items.get(Items.copper));
        assertEquals(12, Team.sharded.core().items.get(Items.thorium));
        CaveWorldTransfer.travel();
        assertSame(Blocks.titaniumWall, world.tile(5, 5).block());
        assertSame(Blocks.graphitePress, world.tile(6, 6).block());
        assertEquals(40, Team.sharded.core().items.get(Items.copper));
        assertEquals(12, Team.sharded.core().items.get(Items.thorium));
    }

    @Test
    void regularSaveKeepsBothWorldsAndSaveSlotThroughReload(){
        prepareTwoWorlds();
        var slot = control.saves.addSave("cave-transition-test");
        try{
            assertSame(slot, control.saves.getCurrent());
            CaveWorldTransfer.travel();
            assertSame(slot, control.saves.getCurrent(), "World switch must not detach the current save");
            Team.sharded.core().items.set(Items.copper, 55);
            world.tile(6, 6).setBlock(Blocks.graphitePress, Team.sharded);
            slot.save();
            logic.reset();
            slot.load();
            state.set(State.playing);
            assertTrue(store.archive().inCave());
            assertSame(Blocks.graphitePress, world.tile(6, 6).block());
            assertEquals(55, Team.sharded.core().items.get(Items.copper));
            CaveWorldTransfer.travel();
            assertSame(slot, control.saves.getCurrent());
            assertSame(Blocks.copperWall, world.tile(5, 5).block());
            assertEquals(55, Team.sharded.core().items.get(Items.copper));
            slot.save();
            slot.load();
            state.set(State.playing);
            CaveWorldTransfer.travel();
            assertSame(Blocks.graphitePress, world.tile(6, 6).block());
            assertEquals(55, Team.sharded.core().items.get(Items.copper));
        }finally{
            slot.delete();
        }
    }

    @Test
    void editorAndNetworkCannotSwitchWorlds() throws Exception{
        prepareTwoWorlds();
        state.rules.editor = true;
        assertThrows(IllegalStateException.class, CaveWorldTransfer::travel);
        assertSame(Blocks.copperWall, world.tile(5, 5).block());
        state.rules.editor = false;
        var active = net.getClass().getDeclaredField("active");
        active.setAccessible(true);
        active.setBoolean(net, true);
        try{
            assertThrows(IllegalStateException.class, CaveWorldTransfer::travel);
            assertSame(Blocks.copperWall, world.tile(5, 5).block());
            assertFalse(store.archive().inCave());
        }finally{
            active.setBoolean(net, false);
        }
    }

    @Test
    void corruptedDestinationRollsBackWorldAndArchive(){
        world.tile(5, 5).setBlock(Blocks.copperWall, Team.sharded);
        Team.sharded.core().items.set(Items.copper, 75);
        store.setArchive(new CaveArchive(false, 55, new byte[]{1, 2, 3}));
        assertThrows(IllegalStateException.class, CaveWorldTransfer::travel);
        assertSame(Blocks.copperWall, world.tile(5, 5).block());
        assertEquals(75, Team.sharded.core().items.get(Items.copper));
        assertFalse(store.archive().inCave());
        assertArrayEquals(new byte[]{1, 2, 3}, store.archive().inactiveWorld());
        assertFalse(store.isTransferring());
        assertEquals(State.playing, state.getState());
    }

    @Test
    void insufficientCapacityRollsBackWithoutDiscardingItems(){
        prepareTwoWorlds();
        int amount = Team.sharded.core().storageCapacity + 2000;
        Team.sharded.core().items.set(Items.copper, amount);
        assertThrows(IllegalStateException.class, CaveWorldTransfer::travel);
        assertSame(Blocks.copperWall, world.tile(5, 5).block());
        assertEquals(amount, Team.sharded.core().items.get(Items.copper));
        assertFalse(store.archive().inCave());
    }
}
