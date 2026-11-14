import mindustry.game.caves.CaveArchive;
import mindustry.game.caves.CavePersistentProtocol;
import mindustry.game.caves.CaveSimulationExchange;

import java.io.*;
import java.nio.file.Path;

/** Deliberately faulty subprocess used only by CavePersistentClientTests. */
public class CavePersistentTestChild{
    public static void main(String[] args) throws Exception{
        String mode = args[0];
        if(mode.equals("stall")){ Thread.sleep(30000); return; }
        var out = new DataOutputStream(new BufferedOutputStream(System.out));
        if(mode.equals("bad-magic")){ out.writeInt(0); out.flush(); return; }
        out.writeInt(CavePersistentProtocol.magic); out.writeInt(CavePersistentProtocol.version);
        out.writeInt(10); out.writeInt(2); out.writeUTF("copper"); out.writeUTF("lead");
        out.write(CaveSimulationExchange.digest(Path.of(args[1]))); out.flush();
        var in = new DataInputStream(new BufferedInputStream(System.in));
        int command = in.readUnsignedByte(); long id = in.readLong();
        in.readInt(); in.readInt(); int count = in.readInt();
        if(command == CavePersistentProtocol.step) in.readInt();
        for(int i = 0; i < count; i++) in.readInt();
        if(mode.equals("hang-step")){ Thread.sleep(30000); return; }
        if(mode.equals("bad-checkpoint")){
            out.writeByte(command); out.writeLong(id); out.writeInt(count);
            for(int i = 0; i < count; i++) out.writeInt(0);
            out.writeInt(CaveArchive.maxSnapshotBytes + 1); out.flush();
            Thread.sleep(30000);
        }
    }
}
