package mindustry.world.blocks.caves;

import arc.Core;
import mindustry.content.CaveContent;
import mindustry.game.WeatherCycle;
import mindustry.game.caves.CaveSaveStore;
import mindustry.game.caves.CaveWorldTransfer;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.Tile;

import java.util.Random;

import static mindustry.Vars.*;

/** A non-destructible, local-only entrance/exit. */
public class CavePortal extends Block{
    public CavePortal(String name){
        super(name);
        configurable = true;
        update = true; // A configurable portal needs a Building entity.
        solid = false;
        destructible = false;
        targetable = false;
        replaceable = false;
        buildType = CavePortalBuild::new;
    }

    @Override
    public boolean canBreak(Tile tile){ return false; }

    public class CavePortalBuild extends Building{
        @Override
        public boolean configTapped(){
            if(!WeatherCycle.localGame() || state.isCampaign()) return false;
            var archive = CaveSaveStore.instance.archive();
            // Always permit leaving a cave, even if the feature was disabled inside it.
            if((archive == null || !archive.inCave()) && !CaveContent.available()){
                ui.showInfo("@caves.disabled");
                return false;
            }
            ui.loadAnd("@caves.loading", () -> {
                try{
                    if(CaveSaveStore.instance.archive() == null) CaveWorldTransfer.createCave(new Random().nextLong());
                    else CaveWorldTransfer.travel();
                }catch(Exception error){
                    arc.util.Log.err(error);
                    ui.showErrorMessage(Core.bundle.get("caves.transitionerror"));
                }
            });
            return false;
        }
    }
}
