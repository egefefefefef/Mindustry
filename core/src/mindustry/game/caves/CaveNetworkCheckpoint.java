package mindustry.game.caves;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Experimental single-file checkpoint for a pair of server worlds. Not used by
 * gameplay. The caller must capture both snapshots and player locations at a
 * quiescent, authoritative transaction boundary; this class cannot prove that
 * they are mutually consistent or capture in-flight network events.
 */
public final class CaveNetworkCheckpoint{
    private static final int magic = 0x434e4350, version = 1, digestLength = 32, maxPlayers = 256;
    private static final long maxFileSize = 2L * CaveArchive.maxSnapshotBytes + 128L * 1024;

    public record Location(CavePlayerTransfers.World world, int team){
        public Location{
            if(world == null || team < 0 || team > 255) throw new IllegalArgumentException("Invalid player location");
        }
    }

    private final long revision;
    private final byte[] surface, cave;
    private final Map<String, Location> players;

    public CaveNetworkCheckpoint(long revision, byte[] surface, byte[] cave, Map<String, Location> players){
        if(revision < 0 || !validWorld(surface) || !validWorld(cave) || players == null || players.size() > maxPlayers){
            throw new IllegalArgumentException("Invalid paired checkpoint");
        }
        TreeMap<String, Location> copy = new TreeMap<>();
        for(var entry : players.entrySet()){
            String id = entry.getKey();
            if(!validPlayer(id) || entry.getValue() == null) throw new IllegalArgumentException("Invalid player location");
            copy.put(id, entry.getValue());
        }
        this.revision = revision;
        this.surface = surface.clone();
        this.cave = cave.clone();
        this.players = Collections.unmodifiableMap(copy);
    }

    public long revision(){ return revision; }
    public byte[] surface(){ return surface.clone(); }
    public byte[] cave(){ return cave.clone(); }
    public Map<String, Location> players(){ return players; }

    /**
     * Write a checksum-protected checkpoint to a temporary file beside the target,
     * flush its bytes, then replace the target in one atomic filesystem operation.
     * If atomic replacement is unsupported, fail instead of falling back to a
     * non-atomic copy. This does not guarantee directory-entry durability after
     * power loss and does not coordinate multiple writers.
     */
    public void writeAtomically(Path target) throws IOException{
        if(target == null) throw new IllegalArgumentException("Missing checkpoint path");
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if(parent == null || !Files.isDirectory(parent)) throw new IOException("Checkpoint directory does not exist");
        Path temp = Files.createTempFile(parent, ".cave-pair-", ".tmp");
        try{
            try(FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)){
                OutputStream raw = Channels.newOutputStream(channel);
                MessageDigest digest = digest();
                DigestOutputStream hashed = new DigestOutputStream(raw, digest);
                DataOutputStream out = new DataOutputStream(hashed);
                out.writeInt(magic);
                out.writeInt(version);
                out.writeLong(revision);
                out.writeInt(players.size());
                for(var entry : players.entrySet()){
                    out.writeUTF(entry.getKey());
                    out.writeByte(entry.getValue().world().ordinal());
                    out.writeInt(entry.getValue().team());
                }
                out.writeInt(surface.length);
                out.write(surface);
                out.writeInt(cave.length);
                out.write(cave);
                out.flush();
                hashed.on(false);
                out.write(digest.digest());
                out.flush();
                channel.force(true);
            }
            Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }finally{
            Files.deleteIfExists(temp);
        }
    }

    /** Read bounded data and reject corrupt, truncated, duplicate, or trailing bytes. */
    public static CaveNetworkCheckpoint read(Path path) throws IOException{
        if(path == null) throw new IllegalArgumentException("Missing checkpoint path");
        try(FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)){
            long size = channel.size();
            if(size <= digestLength || size > maxFileSize) throw new IOException("Invalid checkpoint size");
            InputStream raw = Channels.newInputStream(channel);
            MessageDigest digest = digest();
            DigestInputStream hashed = new DigestInputStream(raw, digest);
            DataInputStream in = new DataInputStream(hashed);
            if(in.readInt() != magic || in.readInt() != version) throw new IOException("Invalid checkpoint header");
            long revision = in.readLong();
            int count = in.readInt();
            if(revision < 0 || count < 0 || count > maxPlayers) throw new IOException("Invalid checkpoint metadata");
            Map<String, Location> players = new TreeMap<>();
            for(int i = 0; i < count; i++){
                String player = in.readUTF();
                int world = in.readUnsignedByte(), team = in.readInt();
                if(!validPlayer(player) || world >= CavePlayerTransfers.World.values().length || team < 0 || team > 255 ||
                    players.putIfAbsent(player, new Location(CavePlayerTransfers.World.values()[world], team)) != null){
                    throw new IOException("Invalid checkpoint player");
                }
            }
            byte[] surface = readWorld(in, size), cave = readWorld(in, size);
            hashed.on(false);
            byte[] expected = new byte[digestLength];
            in.readFully(expected);
            if(in.read() != -1 || !MessageDigest.isEqual(expected, digest.digest())) throw new IOException("Checkpoint checksum or length mismatch");
            return new CaveNetworkCheckpoint(revision, surface, cave, players);
        }catch(IllegalArgumentException malformed){
            throw new IOException("Invalid checkpoint", malformed);
        }
    }

    private static byte[] readWorld(DataInput in, long fileSize) throws IOException{
        int length = in.readInt();
        if(length < 1 || length > CaveArchive.maxSnapshotBytes || length > fileSize - digestLength) throw new IOException("Invalid world snapshot length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static boolean validWorld(byte[] data){ return data != null && data.length > 0 && data.length <= CaveArchive.maxSnapshotBytes; }
    private static boolean validPlayer(String player){ return player != null && !player.isEmpty() && player.length() <= 128; }
    private static MessageDigest digest(){
        try{ return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException impossible){ throw new IllegalStateException(impossible); }
    }
}
