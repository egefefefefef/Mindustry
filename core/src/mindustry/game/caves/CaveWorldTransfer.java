package mindustry.game.caves;

import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.io.SaveIO;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.modules.ItemModule;

import java.io.*;
import java.util.zip.InflaterInputStream;

import static mindustry.Vars.*;

/** Transactional switch between two saved worlds, with shared team-core items. */
public final class CaveWorldTransfer{
    private CaveWorldTransfer(){}

    /** Switch to the inactive world recorded in the current save. */
    public static void travel(){
        var store = CaveSaveStore.instance;
        CaveArchive archive = store.archive();
        if(archive == null) throw new IllegalStateException("No inactive cave world");
        if(store.isTransferring()) throw new IllegalStateException("Already switching worlds");
        if(state == null || !state.isGame() || state.isCampaign() || state.isEditor() || net == null || net.active()){
            throw new IllegalStateException("Cave travel is only available in local custom games");
        }
        if(control != null && control.saves != null && control.saves.isSaving()){
            throw new IllegalStateException("Wait for the current save to finish");
        }
        Team team = player != null ? player.team() : state.rules.defaultTeam;
        CoreBuild origin = team.core();
        if(origin == null) throw new IllegalStateException("A team core is required to share resources");
        if(asyncCore != null) asyncCore.awaitWorldTasks();
        ItemModule items = origin.items.copy();
        State previousState = state.getState();
        byte[] originSnapshot = store.captureWorld();
        byte[] targetSnapshot = archive.inactiveWorld();
        store.transferring(true);
        try{
            load(targetSnapshot);
            CoreBuild target = team.core();
            if(target == null) throw new IllegalStateException("Destination has no team core");
            for(var item : content.items()){
                if(items.get(item) > target.storageCapacity){
                    throw new IllegalStateException("Destination core lacks storage capacity for " + item.name);
                }
            }
            target.items.set(items);
            store.setArchive(new CaveArchive(!archive.inCave(), archive.seed(), originSnapshot));
            state.set(previousState);
        }catch(Exception failure){
            try{
                load(originSnapshot);
                store.setArchive(archive);
                state.set(previousState);
            }catch(Exception rollback){
                failure.addSuppressed(rollback);
            }
            throw new IllegalStateException("World switch failed; original world restored if rollback succeeded", failure);
        }finally{
            store.transferring(false);
        }
    }

    private static void load(byte[] snapshot){
        SaveIO.load(new InflaterInputStream(new ByteArrayInputStream(snapshot)), world.context);
    }
}
