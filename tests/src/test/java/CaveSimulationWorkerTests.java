import arc.Core;
import arc.files.Fi;
import mindustry.content.Blocks;
import mindustry.content.CaveContent;
import mindustry.content.Items;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.game.caves.CaveSaveStore;
import mindustry.game.caves.CaveWorldTransfer;
import mindustry.game.caves.CaveInventoryLedger;
import mindustry.game.caves.CaveSimulationCoordinator;
import mindustry.game.caves.CaveSimulationExchange;
import mindustry.io.SaveIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

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
        return runWorker(input, output, steps, null, null);
    }

    private int runWorker(Path input, Path output, int steps, Path request, Path result) throws Exception{
        String classpath = System.getProperty("cave.worker.test.classpath");
        assertNotNull(classpath);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path log = directory.resolve("worker.log");
        var command = new ArrayList<>(List.of(java, "-cp", classpath, "mindustry.server.CaveSimulationWorker",
            input.toString(), output.toString(), String.valueOf(steps), directory.resolve("worker-data").toString()));
        if(request != null){ command.add(request.toString()); command.add(result.toString()); }
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
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

    private void request(Path file, Path snapshot, CaveInventoryLedger.Snapshot base) throws Exception{
        String[] names = new String[content.items().size];
        for(var item : content.items()) names[item.id] = item.name;
        var packet = new CaveSimulationExchange(CaveSimulationExchange.request, base.revision(), Team.sharded.id,
            base.capacity(), names, base.counts(), CaveSimulationExchange.digest(snapshot));
        try(var out = new DataOutputStream(Files.newOutputStream(file))){ packet.write(out); }
    }

    private CaveSimulationExchange response(Path file, Path snapshot) throws Exception{
        CaveSimulationExchange result;
        try(var in = new DataInputStream(Files.newInputStream(file))){
            result = CaveSimulationExchange.read(in);
            assertEquals(-1, in.read());
        }
        assertEquals(CaveSimulationExchange.result, result.kind());
        assertArrayEquals(CaveSimulationExchange.digest(snapshot), result.snapshotHash());
        return result;
    }

    @Test void workerProposesVersionedInventoryAndStaleResultIsDiscarded() throws Exception{
        boolean previous = Core.settings.getBool("experimental-caves", false);
        try{
            Path input = prepareCave();
            int capacity = Team.sharded.core().storageCapacity;
            int[] counts = new int[content.items().size];
            counts[Items.copper.id] = 50;
            var ledger = new CaveInventoryLedger(capacity, counts);
            var base = ledger.snapshot();
            Path request = directory.resolve("request.bin"), output = directory.resolve("proposal.msav"), result = directory.resolve("result.bin");
            request(request, input, base);
            assertEquals(0, runWorker(input, output, 1200, request, result));
            var proposal = response(result, output);
            assertEquals(base.revision(), proposal.revision());
            assertTrue(proposal.counts()[CaveContent.amethyst.id] > 0);
            assertEquals(50, proposal.counts()[Items.copper.id]);
            assertEquals(0, Team.sharded.core().items.get(CaveContent.amethyst), "Proposal must not mutate the active world");
            var coordinator = new CaveSimulationCoordinator(Team.sharded.id, capacity, proposal.names(), counts, Files.readAllBytes(input));
            var ticket = coordinator.begin();
            assertEquals(CaveInventoryLedger.CommitResult.invalid, coordinator.commit(ticket, proposal, new byte[]{1}));
            assertArrayEquals(Files.readAllBytes(input), coordinator.begin().caveSnapshot());
            assertEquals(CaveInventoryLedger.CommitResult.applied, coordinator.commit(ticket, proposal, Files.readAllBytes(output)));
            assertArrayEquals(Files.readAllBytes(output), coordinator.begin().caveSnapshot());
            assertTrue(coordinator.begin().counts()[CaveContent.amethyst.id] > 0);
            assertTrue(ledger.adjust(Items.copper.id, -10));
            assertEquals(CaveInventoryLedger.CommitResult.conflict, ledger.commit(base, proposal.counts()));
            assertEquals(0, ledger.snapshot().counts()[CaveContent.amethyst.id]);

            var retry = ledger.snapshot();
            Path request2 = directory.resolve("request2.bin"), output2 = directory.resolve("proposal2.msav"), result2 = directory.resolve("result2.bin");
            request(request2, input, retry);
            assertEquals(0, runWorker(input, output2, 1200, request2, result2));
            var accepted = response(result2, output2);
            assertEquals(retry.revision(), accepted.revision());
            assertEquals(CaveInventoryLedger.CommitResult.applied, ledger.commit(retry, accepted.counts()));
            assertEquals(40, ledger.snapshot().counts()[Items.copper.id]);
            assertTrue(ledger.snapshot().counts()[CaveContent.amethyst.id] > 0);
            SaveIO.load(new Fi(output2.toFile()));
            state.set(State.playing);
            int[] committed = ledger.snapshot().counts();
            for(var item : content.items()) assertEquals(committed[item.id], Team.sharded.core().items.get(item), item.name);
        }finally{
            Core.settings.put("experimental-caves", previous);
        }
    }

    private record PersistentResult(int[] counts, byte[] snapshot){}

    private Process startPersistent(Path input, Path log) throws Exception{
        String classpath = System.getProperty("cave.worker.test.classpath");
        assertNotNull(classpath);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", classpath, "mindustry.server.CavePersistentService",
            input.toString(), directory.resolve("persistent-data").toString()).redirectError(log.toFile()).start();
    }

    private void handshake(DataInputStream in, Path snapshot, String[] expectedNames, int capacity) throws Exception{
        assertEquals(mindustry.server.CavePersistentService.magic, in.readInt());
        assertEquals(mindustry.server.CavePersistentService.protocolVersion, in.readInt());
        assertEquals(capacity, in.readInt());
        assertEquals(expectedNames.length, in.readInt());
        for(String name : expectedNames) assertEquals(name, in.readUTF());
        byte[] hash = new byte[32]; in.readFully(hash);
        assertArrayEquals(CaveSimulationExchange.digest(snapshot), hash);
    }

    private void sendCommand(DataOutputStream out, int command, long id, int capacity, int steps, int[] counts) throws Exception{
        out.writeByte(command); out.writeLong(id); out.writeInt(Team.sharded.id); out.writeInt(capacity); out.writeInt(counts.length);
        if(command == mindustry.server.CavePersistentService.step) out.writeInt(steps);
        for(int amount : counts) out.writeInt(amount);
        out.flush();
    }

    private int[] readCounts(DataInputStream in, int command, long id, int expectedCount) throws Exception{
        assertEquals(command, in.readUnsignedByte());
        assertEquals(id, in.readLong());
        assertEquals(expectedCount, in.readInt());
        int[] counts = new int[expectedCount];
        for(int i = 0; i < counts.length; i++) counts[i] = in.readInt();
        return counts;
    }

    @Test void persistentProcessAdvancesRealWorldAcrossManyStepsAndCheckpoints() throws Exception{
        boolean previous = Core.settings.getBool("experimental-caves", false);
        Process process = null;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try{
            Path input = prepareCave(), log = directory.resolve("persistent.log");
            int capacity = Team.sharded.core().storageCapacity;
            String[] names = new String[content.items().size];
            int[] initial = new int[names.length];
            for(var item : content.items()){
                names[item.id] = item.name;
                initial[item.id] = Team.sharded.core().items.get(item);
            }
            initial[Items.copper.id] = 50;
            process = startPersistent(input, log);
            Process child = process;
            Future<PersistentResult> future = pool.submit(() -> {
                try(var in = new DataInputStream(new BufferedInputStream(child.getInputStream()));
                    var out = new DataOutputStream(new BufferedOutputStream(child.getOutputStream()))){
                    handshake(in, input, names, capacity);
                    int[] counts = initial.clone();
                    for(int n = 0; n < 20; n++){
                        if(n == 10) counts[Items.copper.id] = 40; // surface spent 10
                        sendCommand(out, mindustry.server.CavePersistentService.step, n, capacity, 60, counts);
                        counts = readCounts(in, mindustry.server.CavePersistentService.step, n, names.length);
                        assertEquals(n < 10 ? 50 : 40, counts[Items.copper.id]);
                    }
                    assertTrue(counts[CaveContent.amethyst.id] > 0, "Real router and powered drill must work across steps");
                    sendCommand(out, mindustry.server.CavePersistentService.checkpoint, 20, capacity, 0, counts);
                    int[] checkpointCounts = readCounts(in, mindustry.server.CavePersistentService.checkpoint, 20, names.length);
                    assertArrayEquals(counts, checkpointCounts);
                    int length = in.readInt();
                    assertTrue(length > 0 && length <= mindustry.game.caves.CaveArchive.maxSnapshotBytes);
                    byte[] snapshot = new byte[length]; in.readFully(snapshot);
                    out.writeByte(mindustry.server.CavePersistentService.quit); out.flush();
                    assertEquals(mindustry.server.CavePersistentService.quit, in.readUnsignedByte());
                    return new PersistentResult(counts, snapshot);
                }
            });
            PersistentResult result = future.get(90, TimeUnit.SECONDS);
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertEquals(0, Team.sharded.core().items.get(CaveContent.amethyst), "Child must not mutate parent globals");
            Path output = directory.resolve("persistent-output.msav");
            Files.write(output, result.snapshot());
            SaveIO.load(new Fi(output.toFile()));
            state.set(State.playing);
            for(var item : content.items()) assertEquals(result.counts()[item.id], Team.sharded.core().items.get(item), item.name);
            assertTrue(world.tile(74, 63).build.items.get(Items.coal) < 10, "Real generator must consume fuel");
        }finally{
            if(process != null && process.isAlive()) process.destroyForcibly();
            pool.shutdownNow();
            Core.settings.put("experimental-caves", previous);
        }
    }

    @Test void mismatchedSnapshotHashProducesNoProposal() throws Exception{
        boolean previous = Core.settings.getBool("experimental-caves", false);
        try{
            Path input = prepareCave();
            var ledger = new CaveInventoryLedger(Team.sharded.core().storageCapacity, new int[content.items().size]);
            Path request = directory.resolve("bad-request.bin"), output = directory.resolve("bad-output.msav"), result = directory.resolve("bad-result.bin");
            request(request, input, ledger.snapshot());
            byte[] bytes = Files.readAllBytes(request);
            Arrays.fill(bytes, bytes.length - 32, bytes.length, (byte)0);
            Files.write(request, bytes);
            assertNotEquals(0, runWorker(input, output, 10, request, result));
            assertFalse(Files.exists(output));
            assertFalse(Files.exists(result));
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
