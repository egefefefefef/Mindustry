package mindustry.server;

import arc.*;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import arc.mock.MockGraphics;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.FileTree;
import mindustry.core.GameState.State;
import mindustry.core.Logic;
import mindustry.core.NetServer;
import mindustry.game.caves.CaveArchive;
import mindustry.game.caves.CaveSaveStore;
import mindustry.game.caves.CaveSimulationExchange;
import mindustry.game.Team;
import mindustry.io.SaveIO;
import mindustry.net.Net;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.io.*;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static mindustry.Vars.*;

/**
 * Experimental, standalone cave-snapshot runner. NOT connected to gameplay.
 * It runs bounded fixed steps in a separate JVM and writes an uncommitted
 * snapshot, optionally with a versioned inventory exchange. The caller must
 * validate and commit or discard the complete result; there is no live game
 * integration, crash recovery, or mod support.
 */
public final class CaveSimulationWorker{
    private static final int maxSteps = 3600;

    private CaveSimulationWorker(){}

    public static void main(String[] args){
        if(args.length != 4 && args.length != 6){
            System.err.println("Usage: CaveSimulationWorker input.msav output.msav steps isolated-data-directory [request.bin result.bin]");
            System.exit(2);
        }
        try{
            Path input = Path.of(args[0]).toAbsolutePath().normalize();
            Path output = Path.of(args[1]).toAbsolutePath().normalize();
            Path data = Path.of(args[3]).toAbsolutePath().normalize();
            Path request = args.length == 6 ? Path.of(args[4]).toAbsolutePath().normalize() : null;
            Path result = args.length == 6 ? Path.of(args[5]).toAbsolutePath().normalize() : null;
            int steps = Integer.parseInt(args[2]);
            if(steps < 0 || steps > maxSteps || input.equals(output) || !Files.isRegularFile(input) ||
                Files.size(input) > CaveArchive.maxSnapshotBytes || Files.exists(output) ||
                (request != null && (!Files.isRegularFile(request) || Files.size(request) > 1024 * 1024 ||
                    Files.exists(result) || request.equals(input) || request.equals(output) || result.equals(input) ||
                    result.equals(output) || result.equals(request)))){
                throw new IllegalArgumentException("Invalid snapshot, exchange, output path or step count");
            }
            new HeadlessApplication(new ApplicationListener(){
                @Override public void init(){
                    try{
                        run(input, output, data, steps, request, result);
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

    private static void run(Path input, Path output, Path data, int steps, Path requestFile, Path resultFile) throws Exception{
        initializeWorld(input, data);
        CaveSimulationExchange request = null;
        CoreBuild exchangeCore = null;
        int originalCapacity = state.rules.caveStorageCapacity;
        if(requestFile != null){
            try(var in = new DataInputStream(Files.newInputStream(requestFile))){
                request = CaveSimulationExchange.read(in);
                if(in.read() != -1) throw new IOException("Trailing exchange data");
            }
            if(request.kind() != CaveSimulationExchange.request || !Arrays.equals(request.snapshotHash(), CaveSimulationExchange.digest(input)) ||
                request.capacity() > originalCapacity){
                throw new IllegalArgumentException("Request does not match cave snapshot or capacity");
            }
            exchangeCore = Team.get(request.team()).core();
            if(exchangeCore == null || exchangeCore.block != mindustry.content.CaveContent.caveCore){
                throw new IllegalArgumentException("Requested team has no cave core");
            }
            String[] names = request.names(); int[] counts = request.counts();
            if(names.length != content.items().size) throw new IllegalArgumentException("Content differs from worker");
            for(int i = 0; i < names.length; i++){
                if(!names[i].equals(content.item(i).name)) throw new IllegalArgumentException("Item order differs from worker");
            }
            state.rules.caveStorageCapacity = request.capacity();
            exchangeCore.storageCapacity = request.capacity();
            for(int i = 0; i < names.length; i++) exchangeCore.items.set(content.item(i), counts[i]);
        }
        state.set(State.playing);
        runTicks(steps);
        if(exchangeCore != null){
            // Preserve the cave's configured capacity in its snapshot; the request
            // capacity is only the temporary shared limit during this step.
            state.rules.caveStorageCapacity = originalCapacity;
            exchangeCore.storageCapacity = originalCapacity;
        }
        if(output.getParent() != null) Files.createDirectories(output.getParent());
        if(resultFile != null && resultFile.getParent() != null) Files.createDirectories(resultFile.getParent());
        Path temp = Files.createTempFile(output.getParent(), ".cave-simulation-", ".tmp");
        Path resultTemp = resultFile == null ? null : Files.createTempFile(resultFile.getParent(), ".cave-inventory-", ".tmp");
        try{
            SaveIO.write(new Fi(temp.toFile()));
            if(Files.size(temp) > CaveArchive.maxSnapshotBytes) throw new IllegalStateException("Output snapshot exceeds size limit");
            if(request != null){
                int[] counts = new int[content.items().size];
                for(int i = 0; i < counts.length; i++) counts[i] = exchangeCore.items.get(content.item(i));
                var result = new CaveSimulationExchange(CaveSimulationExchange.result, request.revision(), request.team(),
                    request.capacity(), request.names(), counts, CaveSimulationExchange.digest(temp));
                try(var out = new DataOutputStream(Files.newOutputStream(resultTemp))){ result.write(out); }
            }
            Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE);
            if(resultTemp != null){
                try{
                    Files.move(resultTemp, resultFile, StandardCopyOption.ATOMIC_MOVE);
                }catch(Exception error){
                    Files.deleteIfExists(output);
                    throw error;
                }
            }
        }finally{
            Files.deleteIfExists(temp);
            if(resultTemp != null) Files.deleteIfExists(resultTemp);
        }
    }
    /** Initialize only in a fresh isolated JVM. */
    static void initializeWorld(Path input, Path data) throws Exception{
        // The child has its own Arc globals and its own empty data directory.
        Core.settings.setDataDirectory(new Fi(data.toFile()));
        Vars.headless = true;
        Vars.loadLocales = false;
        Vars.net = new Net(null);
        Vars.tree = new FileTree();
        Vars.init();
        Core.graphics = new MockGraphics(){
            @Override public float getDeltaTime(){ return 1f / 60f; }
        };
        Time.setDeltaProvider(() -> 1f);
        content.createBaseContent();
        mods.loadScripts();
        content.createModContent();
        logic = new Logic();
        netServer = new NetServer();
        content.init();
        if(mods.hasContentErrors()) throw new IllegalStateException("Worker content failed to initialize");

        // Reject modded saves rather than silently substituting missing content.
        var file = new Fi(input.toFile());
        var meta = SaveIO.getMeta(SaveIO.getStream(file));
        if(meta.mods.length != 0) throw new IllegalArgumentException("Modded saves are not supported by the prototype");
        SaveIO.load(SaveIO.getStream(file), world.context);
        if(!state.rules.caveInterior || state.isCampaign() || state.isEditor() || CaveSaveStore.instance.archive() != null){
            throw new IllegalArgumentException("Expected an unpaired custom cave snapshot");
        }
    }

    /** Advance the actual game logic with fixed ticks in the isolated world. */
    static void runTicks(int steps){
        for(int i = 0; i < steps; i++){
            Time.updateGlobal();
            asyncCore.begin();
            logic.update();
            asyncCore.end();
        }
        asyncCore.awaitWorldTasks();
    }

}
