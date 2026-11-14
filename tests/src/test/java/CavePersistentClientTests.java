import mindustry.game.caves.CavePersistentClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CavePersistentClientTests{
    @TempDir Path directory;

    private CavePersistentClient launch(String mode) throws Exception{
        Path snapshot = directory.resolve("input.msav");
        if(!Files.exists(snapshot)) Files.write(snapshot, new byte[]{1, 2, 3});
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = List.of(java, "-cp", System.getProperty("cave.worker.test.classpath"),
            "CavePersistentTestChild", mode, snapshot.toString());
        return CavePersistentClient.launch(command, directory.resolve("child.log"), snapshot, new String[]{"copper", "lead"}, 1, 2000);
    }

    @Test void rejectsBrokenHandshake() throws Exception{
        assertThrows(IOException.class, () -> launch("bad-magic"));
    }

    @Test void startupTimeoutIsBounded() throws Exception{
        long start = System.nanoTime();
        assertThrows(IOException.class, () -> launch("stall"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 10000, "Timeout must not leave the game waiting forever");
    }

    @Test void timeoutDiscardsTheEntireSession() throws Exception{
        try(var client = launch("hang-step")){
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> client.step(1, 10, new int[]{3, 2}));
            assertTrue((System.nanoTime() - start) / 1_000_000 < 10000);
            assertThrows(IOException.class, () -> client.step(1, 10, new int[]{3, 2}));
        }
    }

    @Test void rejectsOversizedPairedStepWithoutAllocatingIt() throws Exception{
        try(var client = launch("bad-checkpoint")){
            assertThrows(IOException.class, () -> client.stepCheckpoint(1, 10, new int[]{3, 2}));
            assertThrows(IOException.class, () -> client.step(1, 10, new int[]{3, 2}));
        }
    }

    @Test void rejectsOversizedCheckpointWithoutAllocatingIt() throws Exception{
        try(var client = launch("bad-checkpoint")){
            assertThrows(IOException.class, () -> client.checkpoint(10, new int[]{3, 2}));
            assertThrows(IOException.class, () -> client.step(1, 10, new int[]{3, 2}));
        }
    }
}
