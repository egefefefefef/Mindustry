package mindustry.game.caves;

import arc.Core;
import arc.Events;
import arc.util.Log;
import mindustry.content.CaveContent;
import mindustry.core.GameState.State;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.Team;

import java.io.IOException;
import java.util.function.BooleanSupplier;

import static mindustry.Vars.*;

/**
 * Experimental desktop integration. A bounded step/checkpoint is serialized on
 * the game thread before the corresponding surface frame. This favors a paired
 * save over frame time; large caves or a slow worker may visibly stall gameplay.
 */
public final class CaveFullRuntime{
    public interface Session extends AutoCloseable{
        CavePersistentClient client();
        @Override void close();
    }
    @FunctionalInterface public interface Launcher{
        Session launch(byte[] snapshot, String[] names, int team) throws IOException;
    }

    public static final CaveFullRuntime instance = new CaveFullRuntime((snapshot, names, team) -> {
        var session = CaveWorkerSession.launch(snapshot, names, team);
        return new Session(){
            @Override public CavePersistentClient client(){ return session.client(); }
            @Override public void close(){ session.close(); }
        };
    }, CaveFullRuntime::configured);

    private static boolean installed;
    private final Launcher launcher;
    private final BooleanSupplier enabled;
    private Session session;
    private CavePairedBridge bridge;
    private CaveArchive current;

    public CaveFullRuntime(Launcher launcher, BooleanSupplier enabled){
        if(launcher == null || enabled == null) throw new IllegalArgumentException("Missing cave runtime configuration");
        this.launcher = launcher;
        this.enabled = enabled;
    }

    public static void install(){
        if(installed) return;
        installed = true;
        CaveTickStore.install();
        Events.on(ResetEvent.class, e -> instance.close());
    }

    /** This experimental mode is only distributed on desktop, not mobile or headless. */
    public static boolean configured(){
        return !headless && Core.app != null && Core.app.isDesktop() && Core.settings != null &&
            Core.settings.getBool("experimental-caves-full", true);
    }

    /** Called before the surface logic update. False means do not advance the surface this frame. */
    public boolean beforeUpdate(float delta){
        var archive = CaveSaveStore.instance.archive();
        if(!enabled.getAsBoolean() || !CaveContent.available() || archive == null || archive.inCave() ||
            CaveSaveStore.instance.isTransferring() || CaveBackground.instance.plan() != null){
            close();
            return true;
        }
        try{
            if(current != archive) close();
            var clock = new CaveTickBudget(CaveTickStore.instance.snapshot());
            // Restore any already-recorded work before advancing the surface.
            if(clock.snapshot().ticks() > 0){
                simulate(clock, archive);
                CaveTickStore.instance.set(clock.snapshot());
                return false;
            }
            clock.advance(delta, true);
            if(clock.snapshot().ticks() > 0) simulate(clock, archive);
            CaveTickStore.instance.set(clock.snapshot());
            return true;
        }catch(IOException | RuntimeException failure){
            close();
            Log.err(failure);
            // The failed step was not committed; leave the previous clock and
            // archive in place and stop the surface until the player responds.
            state.set(State.paused);
            if(!headless && ui != null && Core.bundle != null){
                Core.app.post(() -> ui.showErrorMessage(Core.bundle.get("caves.simulationerror")));
            }
            return false;
        }
    }

    private void simulate(CaveTickBudget clock, CaveArchive archive) throws IOException{
        var ticket = clock.reserve(CavePersistentProtocol.maxStepTicks);
        if(ticket == null) return;
        try{
            if(bridge == null){
                if(mods != null && mods.list().contains(mod -> mod.enabled())){
                    throw new IOException("Modded cave simulation is not supported");
                }
                Team team = player != null ? player.team() : state.rules.defaultTeam;
                String[] names = new String[content.items().size];
                for(var item : content.items()) names[item.id] = item.name;
                session = launcher.launch(archive.inactiveWorld(), names, team.id);
                bridge = new CavePairedBridge(session.client(), team::core, team.id);
                current = archive;
            }
            bridge.step(ticket.ticks());
            clock.commit(ticket);
            current = CaveSaveStore.instance.archive();
        }catch(IOException | RuntimeException failure){
            clock.release(ticket);
            throw failure;
        }
    }

    public void close(){
        if(bridge != null){ bridge.close(); bridge = null; }
        if(session != null){ session.close(); session = null; }
        current = null;
    }
}
