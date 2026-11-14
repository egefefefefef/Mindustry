import mindustry.game.caves.CaveArchive;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CaveArchiveTests{
    private byte[] encode(CaveArchive archive) throws IOException{
        var bytes = new ByteArrayOutputStream();
        archive.write(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private CaveArchive decode(byte[] data) throws IOException{
        return CaveArchive.read(new DataInputStream(new ByteArrayInputStream(data)));
    }

    @Test
    void roundTripBothSidesAndIsolateSnapshotsFromMutation() throws IOException{
        for(boolean inCave : new boolean[]{false, true}){
            byte[] snapshot = {1, 2, 3, 4, 5};
            CaveArchive original = new CaveArchive(inCave, Long.MIN_VALUE, snapshot);
            snapshot[0] = 42;
            CaveArchive loaded = decode(encode(original));
            assertEquals(inCave, loaded.inCave());
            assertEquals(Long.MIN_VALUE, loaded.seed());
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, loaded.inactiveWorld());
            byte[] copy = loaded.inactiveWorld();
            copy[1] = 42;
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, loaded.inactiveWorld());
        }
    }

    @Test
    void corruptedTruncatedAndUnsupportedDataAreRejected() throws IOException{
        byte[] bytes = encode(new CaveArchive(true, 42, new byte[]{7, 8, 9, 10}));
        byte[] corrupt = bytes.clone();
        corrupt[corrupt.length - 1] ^= 1;
        assertThrows(IOException.class, () -> decode(corrupt));
        assertThrows(IOException.class, () -> decode(Arrays.copyOf(bytes, bytes.length - 1)));
        byte[] unsupported = bytes.clone();
        unsupported[3] = 99;
        assertThrows(IOException.class, () -> decode(unsupported));
        byte[] oversized = bytes.clone();
        // After the version, side and seed comes the signed length.
        Arrays.fill(oversized, 13, 17, (byte)0x7f);
        assertThrows(IOException.class, () -> decode(oversized));
        assertThrows(IllegalArgumentException.class, () -> new CaveArchive(false, 0, new byte[0]));
    }
}
