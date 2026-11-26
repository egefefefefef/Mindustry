import mindustry.game.caves.CaveNetworkCheckpoint;
import mindustry.game.caves.CaveNetworkCheckpoint.Location;
import mindustry.game.caves.CavePlayerTransfers.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class CaveNetworkCheckpointTests{
    @TempDir Path directory;

    @Test void roundTripAndReplacementKeepBothWorldsAndPlayersTogether() throws Exception{
        byte[] surface = {1, 2, 3}, cave = {4, 5};
        Map<String, Location> locations = new HashMap<>();
        locations.put("alice", new Location(World.cave, 1));
        locations.put("bob", new Location(World.surface, 2));
        var checkpoint = new CaveNetworkCheckpoint(9, surface, cave, locations);
        surface[0] = 0; cave[0] = 0; locations.clear();
        Path path = directory.resolve("paired.cave");
        checkpoint.writeAtomically(path);
        var restored = CaveNetworkCheckpoint.read(path);
        assertEquals(9, restored.revision());
        assertArrayEquals(new byte[]{1, 2, 3}, restored.surface());
        assertArrayEquals(new byte[]{4, 5}, restored.cave());
        assertEquals(new Location(World.cave, 1), restored.players().get("alice"));
        assertEquals(new Location(World.surface, 2), restored.players().get("bob"));
        assertThrows(UnsupportedOperationException.class, () -> restored.players().clear());
        var copy = restored.surface(); copy[0] = 9;
        assertEquals(1, restored.surface()[0]);
        new CaveNetworkCheckpoint(10, new byte[]{7}, new byte[]{8}, Map.of()).writeAtomically(path);
        var replaced = CaveNetworkCheckpoint.read(path);
        assertEquals(10, replaced.revision());
        assertArrayEquals(new byte[]{7}, replaced.surface());
        assertArrayEquals(new byte[]{8}, replaced.cave());
        assertTrue(replaced.players().isEmpty());
    }

    @Test void refusesCorruptionTruncationAndTrailingData() throws Exception{
        Path path = directory.resolve("paired.cave");
        new CaveNetworkCheckpoint(1, new byte[]{1, 2, 3}, new byte[]{4, 5}, Map.of()).writeAtomically(path);
        byte[] original = Files.readAllBytes(path);
        byte[] changed = original.clone(); changed[changed.length - 34] ^= 1;
        Files.write(path, changed);
        assertThrows(IOException.class, () -> CaveNetworkCheckpoint.read(path));
        Files.write(path, Arrays.copyOf(original, original.length - 1));
        assertThrows(IOException.class, () -> CaveNetworkCheckpoint.read(path));
        Files.write(path, Arrays.copyOf(original, original.length + 1));
        assertThrows(IOException.class, () -> CaveNetworkCheckpoint.read(path));
    }

    @Test void rejectsInvalidSnapshotsAndPlayerMetadata(){
        byte[] data = {1};
        assertThrows(IllegalArgumentException.class, () -> new CaveNetworkCheckpoint(-1, data, data, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new CaveNetworkCheckpoint(0, new byte[0], data, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Location(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new Location(World.cave, 256));
        assertThrows(IllegalArgumentException.class, () -> new CaveNetworkCheckpoint(0, data, data, Map.of("", new Location(World.cave, 0))));
        Map<String, Location> many = new HashMap<>();
        for(int i = 0; i < 257; i++) many.put("player" + i, new Location(World.surface, 0));
        assertThrows(IllegalArgumentException.class, () -> new CaveNetworkCheckpoint(0, data, data, many));
    }

    @Test void failedReplaceKeepsExistingTargetAndCleansTemporaryFile() throws Exception{
        Path target = directory.resolve("cannot-replace-directory");
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep"), "original");
        var checkpoint = new CaveNetworkCheckpoint(1, new byte[]{1}, new byte[]{2}, Map.of());
        assertThrows(IOException.class, () -> checkpoint.writeAtomically(target));
        assertEquals("original", Files.readString(target.resolve("keep")));
        try(var entries = Files.list(directory)){
            assertEquals(List.of(target), entries.toList());
        }
    }

    @Test void concurrentReadersNeverObserveHalfOfAReplacement() throws Exception{
        Path path = directory.resolve("paired.cave");
        var a = new CaveNetworkCheckpoint(1, new byte[]{1}, new byte[]{1}, Map.of());
        var b = new CaveNetworkCheckpoint(2, new byte[]{2}, new byte[]{2}, Map.of());
        a.writeAtomically(path);
        var pool = Executors.newSingleThreadExecutor();
        try{
            var reader = pool.submit(() -> {
                for(int i = 0; i < 100; i++){
                    var checkpoint = CaveNetworkCheckpoint.read(path);
                    assertEquals(checkpoint.revision(), checkpoint.surface()[0]);
                    assertEquals(checkpoint.revision(), checkpoint.cave()[0]);
                }
                return null;
            });
            for(int i = 0; i < 40; i++) (i % 2 == 0 ? b : a).writeAtomically(path);
            reader.get(10, TimeUnit.SECONDS);
        }finally{
            pool.shutdownNow();
        }
    }
}
