import arc.Core;
import arc.assets.AssetManager;
import mindustry.content.Blocks;
import mindustry.content.CaveContent;
import mindustry.game.Saves;
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
    void regularSaveKeepsBothWorldsAndSaveSlotThroughReload() throws Exception{
        prepareTwoWorlds();
        // The headless harness has no Control, so exercise the Saves listener
        // separately instead of constructing the graphical Control module.
        var previousAssets = Core.assets;
        if(Core.assets == null) Core.assets = new AssetManager();
        Saves saves = new Saves();
        var file = saveDirectory.child("cave-transition-test.msav");
        var slot = saves.new SaveSlot(file);
        var current = Saves.class.getDeclaredField("current");
        current.setAccessible(true);
        try{
            current.set(saves, slot);
            assertSame(slot, saves.getCurrent());
            CaveWorldTransfer.travel();
            assertSame(slot, saves.getCurrent(), "World switch must not detach the current save");
            Team.sharded.core().items.set(Items.copper, 55);
            world.tile(6, 6).setBlock(Blocks.graphitePress, Team.sharded);
            SaveIO.save(file);
            logic.reset();
            SaveIO.load(file);
            state.set(State.playing);
            current.set(saves, slot);
            assertTrue(store.archive().inCave());
            assertSame(Blocks.graphitePress, world.tile(6, 6).block());
            assertEquals(55, Team.sharded.core().items.get(Items.copper));
            CaveWorldTransfer.travel();
            assertSame(slot, saves.getCurrent());
            assertSame(Blocks.copperWall, world.tile(5, 5).block());
            assertEquals(55, Team.sharded.core().items.get(Items.copper));
            SaveIO.save(file);
            SaveIO.load(file);
            state.set(State.playing);
            CaveWorldTransfer.travel();
            assertSame(Blocks.graphitePress, world.tile(6, 6).block());
            assertEquals(55, Team.sharded.core().items.get(Items.copper));
        }finally{
            file.delete();
            SaveIO.backupFileFor(file).delete();
            Core.assets = previousAssets;
        }
    }

    @Test
    void createsCaveSavesBothWorldsAndReturnsWithSharedResources(){
        boolean previous = Core.settings.getBool("experimental-caves", false);
        var file = saveDirectory.child("generated-cave-test.msav");
        try{
            Core.settings.put("experimental-caves", true);
            state.rules.caves = true;
            world.tile(5, 5).setBlock(Blocks.copperWall, Team.sharded);
            Team.sharded.core().items.set(Items.copper, 100);
            int surfaceCapacity = Team.sharded.core().storageCapacity;
            state.rules.pvp = true; // must never auto-host the underground world
            CaveWorldTransfer.createCave(98765);
            assertTrue(store.archive().inCave());
            assertTrue(state.rules.caveInterior);
            assertFalse(state.rules.pvp);
            assertTrue(state.rules.weather.isEmpty());
            assertEquals(128, world.width());
            assertSame(CaveContent.caveCore, Team.sharded.core().block);
            assertSame(CaveContent.cavePortal, world.tile(70, 64).block());
            assertEquals(surfaceCapacity, Team.sharded.core().storageCapacity);
            assertEquals(100, Team.sharded.core().items.get(Items.copper));
            world.tile(72, 64).setBlock(Blocks.graphitePress, Team.sharded);
            Team.sharded.core().items.add(CaveContent.amethyst, 25);
            SaveIO.save(file);
            logic.reset();
            SaveIO.load(file);
            state.set(State.playing);
            assertSame(Blocks.graphitePress, world.tile(72, 64).block());
            assertEquals(surfaceCapacity, Team.sharded.core().storageCapacity);
            CaveWorldTransfer.travel();
            assertFalse(store.archive().inCave());
            assertFalse(state.rules.caveInterior);
            assertTrue(state.rules.pvp);
            assertSame(Blocks.copperWall, world.tile(5, 5).block());
            assertEquals(25, Team.sharded.core().items.get(CaveContent.amethyst));
            CaveWorldTransfer.travel();
            assertSame(Blocks.graphitePress, world.tile(72, 64).block());
            assertEquals(25, Team.sharded.core().items.get(CaveContent.amethyst));
        }finally{
            Core.settings.put("experimental-caves", previous);
            file.delete();
            SaveIO.backupFileFor(file).delete();
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
