package mindustry.game.caves;

import mindustry.content.CaveContent;
import mindustry.core.GameState.State;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.io.SaveIO;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.modules.ItemModule;

import java.io.*;
import java.util.zip.InflaterInputStream;

import static mindustry.Vars.*;

/** Transactional switch between two saved worlds, with shared team-core items. */
public final class CaveWorldTransfer{
    private CaveWorldTransfer(){}

    /** Create and enter the first cave; the source world is snapshotted first. */
    public static void createCave(long seed){
        var store = CaveSaveStore.instance;
        if(store.archive() != null || store.isTransferring()) throw new IllegalStateException("Cave already exists or a switch is running");
        if(!CaveContent.available()) throw new IllegalStateException("Caves are not enabled for this local custom game");
        if(control != null && control.saves != null && control.saves.isSaving()) throw new IllegalStateException("Wait for the current save to finish");
        Team team = player != null ? player.team() : state.rules.defaultTeam;
        CoreBuild source = team.core();
        if(source == null) throw new IllegalStateException("A team core is required to share resources");
        if(asyncCore != null) asyncCore.awaitWorldTasks();
        ItemModule items = source.items.copy();
        int capacity = source.storageCapacity;
        for(var item : content.items()){
            if(items.get(item) > capacity) throw new IllegalStateException("Surface core already exceeds its capacity for " + item.name);
        }
        Rules caveRules = state.rules.copy();
        var surfaceMap = state.map;
        State previousState = state.getState();
        byte[] surface = store.captureWorld();
        store.transferring(true);
        try{
            logic.reset();
            state.rules = caveRules;
            state.rules.waves = false;
            state.rules.attackMode = false;
            state.rules.pvp = false;
            state.rules.weatherCycle = false;
            state.rules.caveInterior = true;
            state.rules.weather.clear();
            state.rules.coreIncinerates = false;
            state.rules.caveStorageCapacity = capacity;
            // Only the generated cave core should manage the shared inventory.
            state.rules.blockWhitelist = false;
            for(var block : content.blocks()) if(block instanceof CoreBlock) state.rules.bannedBlocks.add(block);
            state.map = surfaceMap;
            world.loadGenerator(128, 128, tiles -> {
                CaveMapGenerator.populate(tiles, seed);
                tiles.getn(64, 64).setBlock(CaveContent.caveCore, team);
                tiles.getn(70, 64).setBlock(CaveContent.cavePortal, team);
            });
            CoreBuild caveCore = team.core();
            if(caveCore == null || caveCore.storageCapacity < capacity) throw new IllegalStateException("Failed to create the cave core");
            caveCore.items.set(items);
            store.setArchive(new CaveArchive(true, seed, surface));
            if(player != null){
                player.team(team);
                player.add();
                CoreBlock.playerSpawn(caveCore.tile, player);
            }
            state.set(previousState);
        }catch(Exception failure){
            try{
                load(surface);
                store.clear();
                state.set(previousState);
            }catch(Exception rollback){
                failure.addSuppressed(rollback);
            }
            throw new IllegalStateException("Cave creation failed; surface restored if rollback succeeded", failure);
        }finally{
            store.transferring(false);
        }
    }

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
        CaveBackground background = CaveBackground.instance;
        CaveBackground.Plan oldPlan = background.plan();
        CaveBackground.Plan nextPlan = archive.inCave() ? CaveBackground.capture(team) : oldPlan;
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
            if(!archive.inCave()) CaveBackground.apply(nextPlan);
            store.setArchive(new CaveArchive(!archive.inCave(), archive.seed(), originSnapshot));
            background.setPlan(archive.inCave() ? nextPlan : null);
            state.set(previousState);
        }catch(Exception failure){
            try{
                load(originSnapshot);
                store.setArchive(archive);
                background.setPlan(oldPlan);
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
