import arc.Core;
import mindustry.content.Blocks;
import mindustry.content.CaveContent;
import mindustry.core.GameState.State;
import mindustry.io.JsonIO;
import mindustry.game.Rules;
import mindustry.world.blocks.production.Drill;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

class CaveContentTests{
    @BeforeAll
    static void init(){ ApplicationTests.launchApplication(); }

    @BeforeEach
    void load(){
        logic.reset();
        world.loadMap(ApplicationTests.testMap);
        state.set(State.playing);
    }

    @Test
    void newOresNeedLaserAndNewDrillsProgressInSpeed(){
        var tile = world.tile(10, 10);
        tile.setBlock(Blocks.air);
        var pneumatic = (Drill)Blocks.pneumaticDrill;
        var laser = (Drill)Blocks.laserDrill;
        var amethystDrill = (Drill)CaveContent.amethystDrill;
        var adamantiteDrill = (Drill)CaveContent.adamantiteDrill;
        for(var ore : new mindustry.world.Block[]{CaveContent.oreAmethyst, CaveContent.oreAdamantite}){
            tile.setOverlay(ore);
            assertFalse(pneumatic.canMine(tile));
            assertTrue(laser.canMine(tile));
            assertTrue(amethystDrill.canMine(tile));
            assertTrue(adamantiteDrill.canMine(tile));
            var item = tile.drop();
            float laserSpeed = laser.size * laser.size / laser.getDrillTime(item);
            float amethystSpeed = amethystDrill.size * amethystDrill.size / amethystDrill.getDrillTime(item);
            float adamantiteSpeed = adamantiteDrill.size * adamantiteDrill.size / adamantiteDrill.getDrillTime(item);
            assertTrue(amethystSpeed > laserSpeed);
            assertTrue(adamantiteSpeed > amethystSpeed);
        }
    }

    @Test
    void cavesRequireBothSwitchesAndStayOutOfEditorAndNetwork() throws Exception{
        boolean previous = Core.settings.getBool("experimental-caves", false);
        try{
            assertFalse(JsonIO.read(Rules.class, "{}").caves);
            state.rules.caves = false;
            Core.settings.put("experimental-caves", false);
            assertFalse(CaveContent.available());
            state.rules.caves = true;
            assertFalse(CaveContent.available());
            Core.settings.put("experimental-caves", true);
            assertTrue(CaveContent.available());
            assertFalse(CaveContent.amethystDrill.isHidden());
            state.rules.editor = true;
            assertFalse(CaveContent.available());
            state.rules.editor = false;
            var active = net.getClass().getDeclaredField("active");
            active.setAccessible(true);
            active.setBoolean(net, true);
            try{
                assertFalse(CaveContent.available());
            }finally{
                active.setBoolean(net, false);
            }
        }finally{
            Core.settings.put("experimental-caves", previous);
        }
    }
}
