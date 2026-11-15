package mindustry.game.caves;

import arc.Events;
import mindustry.game.EventType.ResetEvent;
import mindustry.io.SaveFileReader.CustomChunk;
import mindustry.io.SaveVersion;

import java.io.*;

/** In-memory clock saved alongside the paired cave worlds; never nested in snapshots. */
public final class CaveTickStore implements CustomChunk{
    public static final CaveTickStore instance = new CaveTickStore();
    private static boolean installed;
    private CaveTickBudget.Snapshot clock = new CaveTickBudget.Snapshot(0, 0);
    private CaveTickStore(){}

    public static void install(){
        if(installed) return;
        installed = true;
        SaveVersion.addCustomChunk("arena-cave-clock-v1", instance);
        Events.on(ResetEvent.class, e -> {
            if(!CaveSaveStore.instance.isTransferring()) instance.clear();
        });
    }

    public CaveTickBudget.Snapshot snapshot(){ return clock; }
    public void set(CaveTickBudget.Snapshot value){
        if(value == null) throw new IllegalArgumentException("Missing cave clock");
        clock = value;
    }
    public void clear(){ clock = new CaveTickBudget.Snapshot(0, 0); }

    @Override public boolean shouldWrite(){
        return CaveSaveStore.instance.archive() != null && !CaveSaveStore.instance.isWritingSnapshot();
    }
    @Override public boolean writeNet(){ return false; }
    @Override public void write(DataOutput out) throws IOException{
        out.writeInt(1); out.writeLong(clock.ticks()); out.writeDouble(clock.fraction());
    }
    @Override public void read(DataInput in) throws IOException{
        if(in.readInt() != 1) throw new IOException("Unsupported cave clock version");
        long ticks = in.readLong(); double fraction = in.readDouble();
        try{
            clock = new CaveTickBudget.Snapshot(ticks, fraction);
        }catch(IllegalArgumentException invalid){
            throw new IOException("Invalid cave clock", invalid);
        }
    }
}
