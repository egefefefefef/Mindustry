import arc.Core;
import arc.files.Fi;
import mindustry.content.Blocks;
import mindustry.content.CaveContent;
import mindustry.content.Items;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.game.caves.CaveSaveStore;
import mindustry.game.caves.CaveWorldTransfer;
import mindustry.io.SaveIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

class CaveSimulationWorkerTests{
    @TempDir Path directory;

    @BeforeAll static void init(){ ApplicationTests.launchApplication(); }
    @BeforeEach void load(){
        logic.reset();
        world.loadMap(ApplicationTests.testMap);
        state.set(State.playing);
    }

    private int runWorker(Path input, Path output, int steps) throws Exception{
        String classpath = System.getProperty("cave.worker.test.classpath");
        assertNotNull(classpath);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path log = directory.resolve("worker.log");
        var process = new ProcessBuilder(java, "-cp", classpath, "mindustry.server.CaveSimulationWorker",
            input.toString(), output.toString(), String.valueOf(steps), directory.resolve("worker-data").toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(90, TimeUnit.SECONDS)){
            process.destroyForcibly();
            fail("Worker timed out: " + Files.readString(log));
        }
        if(process.exitValue() != 0) System.out.println("Worker log: " + Files.readString(log));
        return process.exitValue();
    }

    private Path prepareCave() throws Exception{
        state.rules.caves = true;
        Core.settings.put("experimental-caves", true);
        CaveWorldTransfer.createCave(4321);
        for(int x = 66; x <= 75; x++) for(int y = 62; y <= 66; y++){
            var tile = world.tile(x, y);
            tile.setBlock(Blocks.air);
            tile.setFloor(Blocks.stone.asFloor());
            tile.setOverlay(Blocks.air);
        }
        for(int x = 71; x <= 73; x++) for(int y = 63; y <= 65; y++) world.tile(x, y).setOverlay(CaveContent.oreAmethyst);
        for(int x = 66; x <= 70; x++) world.tile(x, 64).setBlock(x == 69 ? Blocks.router : Blocks.conveyor, Team.sharded, 2);
        world.tile(72, 64).setBlock(Blocks.laserDrill, Team.sharded);
        world.tile(74, 63).setBlock(Blocks.combustionGenerator, Team.sharded);
        world.tile(74, 65).setBlock(Blocks.combustionGenerator, Team.sharded);
        world.tile(74, 63).build.items.set(Items.coal, 10);
        world.tile(74, 65).build.items.set(Items.coal, 10);
        Team.sharded.core().items.set(CaveContent.amethyst, 0);
        Path input = directory.resolve("input.msav");
        Files.write(input, CaveSaveStore.instance.captureWorld());
        return input;
    }

    @Test void isolatedWorkerRunsRealRouterDrillAndFuelGenerator() throws Exception{
        boolean previous = Core.settings.getBool("experimental-caves", false);
        try{
            Path input = prepareCave(), output = directory.resolve("output.msav");
            byte[] original = Files.readAllBytes(input);
            assertEquals(0, runWorker(input, output, 1200), "Worker must complete successfully");
            assertArrayEquals(original, Files.readAllBytes(input), "Worker must never alter the input snapshot");
            assertEquals(0, Team.sharded.core().items.get(CaveContent.amethyst), "Child cannot mutate the parent world");
            assertEquals(10, world.tile(74, 63).build.items.get(Items.coal));
            SaveIO.load(new Fi(output.toFile()));
            state.set(State.playing);
            assertSame(Blocks.router, world.tile(69, 64).block());
            assertTrue(Team.sharded.core().items.get(CaveContent.amethyst) > 0, "Items must travel through the router");
            assertTrue(world.tile(74, 63).build.items.get(Items.coal) < 10, "Generator must consume fuel");
        }finally{
            Core.settings.put("experimental-caves", previous);
        }
    }

    @Test void invalidSnapshotAndExcessiveStepsNeverCreateOutput() throws Exception{
        Path input = directory.resolve("invalid.msav"), output = directory.resolve("output.msav");
        Files.write(input, new byte[]{1, 2, 3});
        assertNotEquals(0, runWorker(input, output, 1));
        assertFalse(Files.exists(output));
        assertNotEquals(0, runWorker(input, output, 3601));
        assertFalse(Files.exists(output));
    }
}
