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
import mindustry.io.SaveIO;
import mindustry.net.Net;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static mindustry.Vars.*;

/**
 * Experimental, standalone cave-snapshot runner. NOT connected to gameplay.
 * It runs bounded fixed steps in a separate JVM and writes a new snapshot;
 * it does not synchronize core inventories, provide rollback or support mods.
 */
public final class CaveSimulationWorker{
    private static final int maxSteps = 3600;

    private CaveSimulationWorker(){}

    public static void main(String[] args){
        if(args.length != 4){
            System.err.println("Usage: CaveSimulationWorker input.msav output.msav steps isolated-data-directory");
            System.exit(2);
        }
        try{
            Path input = Path.of(args[0]).toAbsolutePath().normalize();
            Path output = Path.of(args[1]).toAbsolutePath().normalize();
            Path data = Path.of(args[3]).toAbsolutePath().normalize();
            int steps = Integer.parseInt(args[2]);
            if(steps < 0 || steps > maxSteps || input.equals(output) || !Files.isRegularFile(input) ||
                Files.size(input) > CaveArchive.maxSnapshotBytes || Files.exists(output)){
                throw new IllegalArgumentException("Invalid snapshot, output path or step count");
            }
            new HeadlessApplication(new ApplicationListener(){
                @Override public void init(){
                    try{
                        run(input, output, data, steps);
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

    private static void run(Path input, Path output, Path data, int steps) throws Exception{
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
        state.set(State.playing);
        for(int i = 0; i < steps; i++){
            Time.updateGlobal();
            asyncCore.begin();
            logic.update();
            asyncCore.end();
        }
        asyncCore.awaitWorldTasks();
        if(output.getParent() != null) Files.createDirectories(output.getParent());
        Path temp = Files.createTempFile(output.getParent(), ".cave-simulation-", ".tmp");
        try{
            SaveIO.write(new Fi(temp.toFile()));
            if(Files.size(temp) > CaveArchive.maxSnapshotBytes) throw new IllegalStateException("Output snapshot exceeds size limit");
            Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE);
        }finally{
            Files.deleteIfExists(temp);
        }
    }
}
