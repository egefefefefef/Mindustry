package mindustry.game.caves;

import arc.Events;
import mindustry.game.EventType.ResetEvent;
import mindustry.io.SaveFileReader.CustomChunk;
import mindustry.io.SaveIO;
import mindustry.io.SaveOptions;
import mindustry.io.SaveVersion;

import java.io.*;
import java.util.zip.DeflaterOutputStream;

/** Save-format integration for a dormant cave/surface snapshot. No travel is performed here. */
public final class CaveSaveStore implements CustomChunk{
    public static final CaveSaveStore instance = new CaveSaveStore();
    private static boolean installed;
    private CaveArchive archive;
    private boolean writingSnapshot;
    private boolean transferring;

    private CaveSaveStore(){}

    public static void install(){
        if(installed) return;
        installed = true;
        SaveVersion.addCustomChunk("arena-cave-world-v1", instance);
        Events.on(ResetEvent.class, e -> instance.clear());
    }

    public CaveArchive archive(){ return archive; }
    public boolean isTransferring(){ return transferring; }
    void transferring(boolean transferring){ this.transferring = transferring; }
    public void setArchive(CaveArchive archive){ this.archive = archive; }
    public void clear(){ archive = null; }

    /** Capture a full compressed save without recursively embedding the inactive world. */
    public byte[] captureWorld(){
        if(writingSnapshot) throw new IllegalStateException("Recursive cave snapshot");
        writingSnapshot = true;
        try{
            var bytes = new ByteArrayOutputStream();
            SaveIO.write(new DeflaterOutputStream(new LimitedOutputStream(bytes)), new SaveOptions());
            return bytes.toByteArray();
        }finally{
            writingSnapshot = false;
        }
    }

    @Override
    public boolean shouldWrite(){ return archive != null && !writingSnapshot; }

    @Override
    public boolean writeNet(){ return false; }

    @Override
    public void write(DataOutput output) throws IOException{
        if(archive == null) throw new IOException("No inactive cave world");
        archive.write(output);
    }

    @Override
    public void read(DataInput input) throws IOException{
        // Do not publish a partially read archive when the input is corrupt.
        archive = CaveArchive.read(input);
    }

    private static final class LimitedOutputStream extends FilterOutputStream{
        private int size;
        private LimitedOutputStream(OutputStream out){ super(out); }
        @Override
        public void write(int value) throws IOException{
            if(size >= CaveArchive.maxSnapshotBytes) throw new IOException("Cave snapshot is too large");
            out.write(value);
            size++;
        }
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException{
            if(length > CaveArchive.maxSnapshotBytes - size) throw new IOException("Cave snapshot is too large");
            out.write(bytes, offset, length);
            size += length;
        }
    }
}
