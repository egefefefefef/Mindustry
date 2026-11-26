import arc.Core;
import mindustry.content.Blocks;
import mindustry.content.CaveContent;
import mindustry.content.Items;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.game.caves.CaveNetworkCheckpoint;
import mindustry.game.caves.CavePlayerTransfers.World;
import mindustry.game.caves.CaveSaveStore;
import mindustry.game.caves.CaveWorldTransfer;
import mindustry.io.SaveIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.InflaterInputStream;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tests only the checkpoint codec against real saves; this is not a network test. */
class CaveNetworkRealWorldTests{
    @TempDir Path directory;
    @BeforeAll static void init(){ ApplicationTests.launchApplication(); }

    @BeforeEach void load(){
        logic.reset();
        world.loadMap(ApplicationTests.testMap);
        state.set(State.playing);
    }

    @Test void realSurfaceAndCaveSnapshotsSurviveOnePairedCheckpoint() throws Exception{
        boolean previous = Core.settings.getBool("experimental-caves", false);
        try{
            Core.settings.put("experimental-caves", true);
            state.rules.caves = true;
            world.tile(5, 5).setBlock(Blocks.copperWall, Team.sharded);
            Team.sharded.core().items.set(Items.copper, 123);
            CaveWorldTransfer.createCave(5566);
            assertTrue(state.rules.caveInterior);
            world.tile(72, 64).setBlock(Blocks.graphitePress, Team.sharded);
            Team.sharded.core().items.set(Items.copper, 91);
            Team.sharded.core().items.set(CaveContent.ruby, 8);

            byte[] surface = CaveSaveStore.instance.archive().inactiveWorld();
            byte[] cave = CaveSaveStore.instance.captureWorld();
            var players = Map.of("player-surface", new CaveNetworkCheckpoint.Location(World.surface, Team.sharded.id),
                "player-cave", new CaveNetworkCheckpoint.Location(World.cave, Team.sharded.id));
            var checkpoint = new CaveNetworkCheckpoint(7, surface, cave, players);
            Path path = directory.resolve("world-pair.bin");
            checkpoint.writeAtomically(path);
            var restored = CaveNetworkCheckpoint.read(path);
            assertEquals(7, restored.revision());
            assertEquals(players, restored.players());

            // Restoring the two saves sequentially here verifies serialization only;
            // it does not imply concurrent worlds or shared network inventory work.
            SaveIO.load(new InflaterInputStream(new ByteArrayInputStream(restored.surface())), world.context);
            state.set(State.playing);
            assertFalse(state.rules.caveInterior);
            assertSame(Blocks.copperWall, world.tile(5, 5).block());
            assertEquals(123, Team.sharded.core().items.get(Items.copper));
            SaveIO.load(new InflaterInputStream(new ByteArrayInputStream(restored.cave())), world.context);
            state.set(State.playing);
            assertTrue(state.rules.caveInterior);
            assertSame(Blocks.graphitePress, world.tile(72, 64).block());
            assertEquals(91, Team.sharded.core().items.get(Items.copper));
            assertEquals(8, Team.sharded.core().items.get(CaveContent.ruby));
        }finally{
            Core.settings.put("experimental-caves", previous);
        }
    }
}
