import arc.Core;
import mindustry.content.Blocks;
import mindustry.content.CaveContent;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.game.caves.CaveEntrances;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

class CaveEntrancesTests{
    @BeforeAll
    static void init(){ ApplicationTests.launchApplication(); }

    @BeforeEach
    void load(){
        logic.reset();
        world.loadMap(ApplicationTests.testMap);
        state.set(State.playing);
    }

    private int portals(){
        int count = 0;
        for(int y = 0; y < world.height(); y++) for(int x = 0; x < world.width(); x++){
            if(world.tile(x, y).block() == CaveContent.cavePortal) count++;
        }
        return count;
    }

    @Test
    void needsBothSwitchesAndNeverReplacesAnExistingBuilding(){
        boolean previous = Core.settings.getBool("experimental-caves", false);
        try{
            world.tile(5, 5).setBlock(Blocks.copperWall, Team.sharded);
            Core.settings.put("experimental-caves", false);
            state.rules.caves = true;
            CaveEntrances.update();
            assertEquals(0, portals());
            Core.settings.put("experimental-caves", true);
            state.rules.caves = false;
            CaveEntrances.update();
            assertEquals(0, portals());
            state.rules.caves = true;
            CaveEntrances.update();
            assertEquals(1, portals());
            assertSame(Blocks.copperWall, world.tile(5, 5).block());
            CaveEntrances.update();
            assertEquals(1, portals());
            for(int y = 0; y < world.height(); y++) for(int x = 0; x < world.width(); x++){
                var tile = world.tile(x, y);
                if(tile.block() == CaveContent.cavePortal){
                    assertNotNull(tile.build, "Portal must have a clickable building entity");
                    assertFalse(tile.floor().isLiquid);
                    assertSame(Blocks.air, tile.overlay());
                }
            }
        }finally{
            Core.settings.put("experimental-caves", previous);
        }
    }
}
