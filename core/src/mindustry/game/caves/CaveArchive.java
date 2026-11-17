package mindustry.game.caves;

import java.io.*;
import java.util.zip.CRC32;

/**
 * The inactive world's compressed save, stored in the active world's save.
 * The payload must itself omit this archive to prevent recursive nesting.
 * This type is deliberately independent of global game state.
 */
public final class CaveArchive{
    public static final int formatVersion = 1;
    public static final int maxSnapshotBytes = 64 * 1024 * 1024;
    private final boolean inCave;
    private final long seed;
    private final byte[] inactiveWorld;

    public CaveArchive(boolean inCave, long seed, byte[] inactiveWorld){
        if(inactiveWorld == null || inactiveWorld.length == 0 || inactiveWorld.length > maxSnapshotBytes){
            throw new IllegalArgumentException("Invalid inactive world snapshot size");
        }
        this.inCave = inCave;
        this.seed = seed;
        this.inactiveWorld = inactiveWorld.clone();
    }

    public boolean inCave(){ return inCave; }
    public long seed(){ return seed; }
    public byte[] inactiveWorld(){ return inactiveWorld.clone(); }

    public void write(DataOutput out) throws IOException{
        out.writeInt(formatVersion);
        out.writeBoolean(inCave);
        out.writeLong(seed);
        out.writeInt(inactiveWorld.length);
        out.writeInt(checksum(inactiveWorld));
        out.write(inactiveWorld);
    }

    public static CaveArchive read(DataInput in) throws IOException{
        int version = in.readInt();
        if(version != formatVersion) throw new IOException("Unsupported cave archive version: " + version);
        boolean inCave = in.readBoolean();
        long seed = in.readLong();
        int length = in.readInt();
        if(length <= 0 || length > maxSnapshotBytes) throw new IOException("Invalid cave snapshot length: " + length);
        int expected = in.readInt();
        byte[] data = new byte[length];
        in.readFully(data);
        if(checksum(data) != expected) throw new IOException("Cave snapshot checksum mismatch");
        return new CaveArchive(inCave, seed, data);
    }

    private static int checksum(byte[] data){
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int)crc.getValue();
    }
}
