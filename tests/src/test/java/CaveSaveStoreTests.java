import mindustry.content.Blocks;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.game.caves.CaveArchive;
import mindustry.game.caves.CaveSaveStore;
import mindustry.io.SaveIO;
import mindustry.io.SaveOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.zip.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

class CaveSaveStoreTests{
    @BeforeAll
    static void init(){ ApplicationTests.launchApplication(); }

    @BeforeEach
    void load(){
        logic.reset();
        world.loadMap(ApplicationTests.testMap);
        state.set(State.playing);
    }

    @Test
    void inactiveSnapshotDoesNotRecursivelyIncludeArchiveAndRestoresWorld() throws IOException{
        var store = CaveSaveStore.instance;
        world.tile(5, 5).setBlock(Blocks.copperWall, Team.sharded);
        store.setArchive(new CaveArchive(true, 5, new byte[]{1, 2, 3}));
        byte[] snapshot = store.captureWorld();
        assertNotNull(store.archive(), "Capturing must not alter the active archive");
        world.tile(5, 5).setBlock(Blocks.air);
        SaveIO.load(new InflaterInputStream(new ByteArrayInputStream(snapshot)), world.context);
        assertSame(Blocks.copperWall, world.tile(5, 5).block());
        assertNull(store.archive(), "Nested snapshot must omit the cave archive");
    }

    @Test
    void regularSaveRoundTripsArchiveAlongsideActiveMap() throws IOException{
        var store = CaveSaveStore.instance;
        byte[] inactive = store.captureWorld();
        store.setArchive(new CaveArchive(true, 9876, inactive));
        world.tile(5, 5).setBlock(Blocks.copperWall, Team.sharded);
        var bytes = new ByteArrayOutputStream();
        SaveIO.write(new DeflaterOutputStream(bytes), new SaveOptions());
        logic.reset();
        assertNull(store.archive());
        SaveIO.load(new InflaterInputStream(new ByteArrayInputStream(bytes.toByteArray())), world.context);
        assertSame(Blocks.copperWall, world.tile(5, 5).block());
        assertNotNull(store.archive());
        assertTrue(store.archive().inCave());
        assertEquals(9876, store.archive().seed());
        assertArrayEquals(inactive, store.archive().inactiveWorld());
        assertFalse(store.writeNet(), "Cave saves are local single-player only");
    }
}
