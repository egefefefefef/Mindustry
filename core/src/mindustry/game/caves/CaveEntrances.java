package mindustry.game.caves;

import arc.Events;
import mindustry.content.Blocks;
import mindustry.content.CaveContent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.world.Tile;

import static mindustry.Vars.*;

/** Places at most one portal in unobstructed dry ground, never replacing a building or ore. */
public final class CaveEntrances{
    private static boolean installed, checked;

    private CaveEntrances(){}

    public static void install(){
        if(installed) return;
        installed = true;
        Events.on(WorldLoadEvent.class, e -> checked = false);
        Events.run(Trigger.afterGameUpdate, CaveEntrances::update);
    }

    public static void update(){
        var store = CaveSaveStore.instance;
        if(store.isTransferring() || !CaveContent.available() || world.isGenerating() || checked) return;
        if(store.archive() != null && store.archive().inCave()) return;
        var core = (player != null ? player.team() : state.rules.defaultTeam).core();
        if(core == null) return;
        // Existing entrance persists with the surface map, including after saves.
        for(int y = 0; y < world.height(); y++) for(int x = 0; x < world.width(); x++){
            if(world.tile(x, y).block() == CaveContent.cavePortal){ checked = true; return; }
        }
        checked = true;
        int cx = core.tileX(), cy = core.tileY();
        // Limit work on enormous or entirely liquid maps; never search cubic rings.
        int max = Math.min(128, Math.max(world.width(), world.height()));
        for(int radius = core.block.size + 2; radius <= max; radius++){
            for(int offset = -radius; offset <= radius; offset++){
                if(place(cx + offset, cy - radius, core.team) || place(cx + offset, cy + radius, core.team)) return;
                if(Math.abs(offset) < radius && (place(cx - radius, cy + offset, core.team) || place(cx + radius, cy + offset, core.team))) return;
            }
        }
        if(!headless && ui != null) ui.showInfo("@caves.noground");
    }

    private static boolean place(int x, int y, mindustry.game.Team team){
        Tile tile = world.tile(x, y);
        if(tile == null || tile.block() != Blocks.air || tile.overlay() != Blocks.air || tile.floor().isLiquid) return false;
        tile.setBlock(CaveContent.cavePortal, team);
        return true;
    }
}
