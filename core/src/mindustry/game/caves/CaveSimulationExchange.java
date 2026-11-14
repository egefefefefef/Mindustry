package mindustry.game.caves;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Bounded prototype wire format for one isolated, uncommitted simulation step. */
public final class CaveSimulationExchange{
    private static final int magic = 0x43415645, version = 1, maxItems = 4096;
    public static final int request = 0, result = 1;
    private final int kind, team, capacity;
    private final long revision;
    private final String[] names;
    private final int[] counts;
    private final byte[] snapshotHash;

    public CaveSimulationExchange(int kind, long revision, int team, int capacity, String[] names, int[] counts, byte[] snapshotHash){
        if((kind != request && kind != result) || revision < 0 || team < 0 || team > 255 || capacity < 1 ||
            names == null || counts == null || names.length == 0 || names.length > maxItems || names.length != counts.length ||
            snapshotHash == null || snapshotHash.length != 32) throw new IllegalArgumentException("Invalid cave exchange header");
        long total = 0;
        Set<String> unique = new HashSet<>();
        for(int i = 0; i < names.length; i++){
            String name = names[i];
            if(name == null || name.isEmpty() || name.length() > 128 || !unique.add(name) || counts[i] < 0 || counts[i] > capacity){
                throw new IllegalArgumentException("Invalid cave exchange item");
            }
            total += counts[i];
        }
        if(total > Integer.MAX_VALUE) throw new IllegalArgumentException("Cave inventory total overflow");
        this.kind = kind; this.revision = revision; this.team = team; this.capacity = capacity;
        this.names = names.clone(); this.counts = counts.clone(); this.snapshotHash = snapshotHash.clone();
    }

    public int kind(){ return kind; }
    public long revision(){ return revision; }
    public int team(){ return team; }
    public int capacity(){ return capacity; }
    public String[] names(){ return names.clone(); }
    public int[] counts(){ return counts.clone(); }
    public byte[] snapshotHash(){ return snapshotHash.clone(); }

    public void write(DataOutput out) throws IOException{
        out.writeInt(magic); out.writeInt(version); out.writeInt(kind); out.writeLong(revision);
        out.writeInt(team); out.writeInt(capacity); out.writeInt(names.length);
        for(int i = 0; i < names.length; i++){ out.writeUTF(names[i]); out.writeInt(counts[i]); }
        out.write(snapshotHash);
    }

    public static CaveSimulationExchange read(DataInput in) throws IOException{
        if(in.readInt() != magic || in.readInt() != version) throw new IOException("Invalid cave exchange version");
        int kind = in.readInt(); long revision = in.readLong();
        int team = in.readInt(), capacity = in.readInt(), length = in.readInt();
        if(length <= 0 || length > maxItems) throw new IOException("Invalid cave exchange item count");
        String[] names = new String[length]; int[] counts = new int[length];
        for(int i = 0; i < length; i++){ names[i] = in.readUTF(); counts[i] = in.readInt(); }
        byte[] hash = new byte[32]; in.readFully(hash);
        try{
            return new CaveSimulationExchange(kind, revision, team, capacity, names, counts, hash);
        }catch(IllegalArgumentException invalid){
            throw new IOException("Invalid cave exchange", invalid);
        }
    }

    public static byte[] digest(Path path) throws IOException{
        try{
            var md = MessageDigest.getInstance("SHA-256");
            try(var stream = Files.newInputStream(path)){
                byte[] buffer = new byte[8192]; int read; long size = 0;
                while((read = stream.read(buffer)) != -1){
                    size += read;
                    if(size > CaveArchive.maxSnapshotBytes) throw new IOException("Cave snapshot exceeds size limit");
                    md.update(buffer, 0, read);
                }
            }
            return md.digest();
        }catch(NoSuchAlgorithmException impossible){
            throw new IllegalStateException(impossible);
        }
    }
}
