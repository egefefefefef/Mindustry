package mindustry.server;

import arc.ApplicationListener;
import mindustry.content.CaveContent;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.game.caves.CaveArchive;
import mindustry.game.caves.CaveSaveStore;
import mindustry.game.caves.CaveSimulationExchange;
import mindustry.game.caves.CavePersistentProtocol;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.io.*;
import java.nio.file.*;

import static mindustry.Vars.*;

/**
 * Test-only persistent isolated cave process. No gameplay client launches it.
 * Requests are strictly sequential: an accepted step is not rollbackable;
 * a caller must abort the whole session if it cannot apply the response.
 */
public final class CavePersistentService{
    public static final int magic = CavePersistentProtocol.magic, protocolVersion = CavePersistentProtocol.version;
    public static final int step = CavePersistentProtocol.step, checkpoint = CavePersistentProtocol.checkpoint, quit = CavePersistentProtocol.quit;
    private static final int maxStepTicks = CavePersistentProtocol.maxStepTicks;

    private CavePersistentService(){}

    public static void main(String[] args){
        // Preserve a clean binary channel even when game initialization logs to stdout.
        var output = new DataOutputStream(new BufferedOutputStream(System.out));
        System.setOut(System.err);
        if(args.length != 2){
            System.err.println("Usage: CavePersistentService input.msav isolated-data-directory");
            System.exit(2);
        }
        try{
            Path input = Path.of(args[0]).toAbsolutePath().normalize();
            Path data = Path.of(args[1]).toAbsolutePath().normalize();
            if(!Files.isRegularFile(input) || Files.size(input) <= 0 || Files.size(input) > CaveArchive.maxSnapshotBytes){
                throw new IllegalArgumentException("Invalid cave snapshot");
            }
            new CaveWorkerApplication(new ApplicationListener(){
                @Override public void init(){
                    try{
                        CaveSimulationWorker.initializeWorld(input, data);
                        state.set(State.playing);
                        serve(new DataInputStream(new BufferedInputStream(System.in)), output, input);
                        System.exit(0);
                    }catch(Throwable error){
                        error.printStackTrace(System.err);
                        System.exit(1);
                    }
                }
            }, error -> {
                error.printStackTrace(System.err);
                System.exit(1);
            });
        }catch(Throwable error){
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void serve(DataInputStream in, DataOutputStream out, Path input) throws Exception{
        int originalCapacity = state.rules.caveStorageCapacity;
        if(originalCapacity < 1 || content.items().size <= 0 || content.items().size > CavePersistentProtocol.maxItems){
            throw new IllegalArgumentException("Invalid cave capacity or item count");
        }
        out.writeInt(magic); out.writeInt(protocolVersion); out.writeInt(originalCapacity);
        out.writeInt(content.items().size);
        for(var item : content.items()) out.writeUTF(item.name);
        out.write(CaveSimulationExchange.digest(input));
        out.flush();
        long lastId = -1;
        int activeTeam = -1;
        while(true){
            int command = in.read();
            if(command == -1) return;
            if(command == quit){ out.writeByte(quit); out.flush(); return; }
            if(command != step && command != checkpoint && command != CavePersistentProtocol.stepCheckpoint) throw new IOException("Unknown cave command");
            boolean advances = command == step || command == CavePersistentProtocol.stepCheckpoint;
            boolean snapshots = command == checkpoint || command == CavePersistentProtocol.stepCheckpoint;
            long id = in.readLong();
            int team = in.readInt(), capacity = in.readInt(), count = in.readInt();
            int steps = advances ? in.readInt() : 0;
            if(id < 0 || id <= lastId || team < 0 || team > 255 || (activeTeam != -1 && team != activeTeam) ||
                capacity < 1 || capacity > originalCapacity || count != content.items().size ||
                (advances && (steps < 1 || steps > maxStepTicks))){
                throw new IOException("Invalid cave command header");
            }
            int[] inventory = new int[count]; long total = 0;
            for(int i = 0; i < count; i++){
                inventory[i] = in.readInt();
                if(inventory[i] < 0 || inventory[i] > capacity) throw new IOException("Invalid shared item amount");
                total += inventory[i];
            }
            if(total > Integer.MAX_VALUE) throw new IOException("Shared inventory overflow");
            CoreBuild core = Team.get(team).core();
            if(core == null || core.block != CaveContent.caveCore) throw new IOException("Cave core unavailable");
            lastId = id;
            activeTeam = team;
            state.rules.caveStorageCapacity = capacity;
            core.storageCapacity = capacity;
            core.items.clear();
            for(int i = 0; i < count; i++) core.items.set(content.item(i), inventory[i]);
            if(advances) CaveSimulationWorker.runTicks(steps);
            if(snapshots) CaveSimulationWorker.prepareSnapshot();
            if(Team.get(team).core() != core) throw new IOException("Cave core changed during simulation");
            int[] result = new int[count]; total = 0;
            for(int i = 0; i < count; i++){
                result[i] = core.items.get(content.item(i));
                if(result[i] < 0 || result[i] > capacity) throw new IOException("Invalid produced item amount");
                total += result[i];
            }
            if(total > Integer.MAX_VALUE) throw new IOException("Produced inventory overflow");
            byte[] snapshot = null;
            if(snapshots){
                // Snapshot preserves the cave's configured limit rather than the
                // possibly smaller temporary shared limit used for this step.
                state.rules.caveStorageCapacity = originalCapacity;
                core.storageCapacity = originalCapacity;
                try{
                    snapshot = CaveSaveStore.instance.captureWorld();
                }finally{
                    state.rules.caveStorageCapacity = capacity;
                    core.storageCapacity = capacity;
                }
            }
            out.writeByte(command); out.writeLong(id); out.writeInt(count);
            for(int amount : result) out.writeInt(amount);
            if(snapshot != null){ out.writeInt(snapshot.length); out.write(snapshot); }
            out.flush();
        }
    }
}
