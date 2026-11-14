import arc.Core;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.game.caves.CaveBackground;
import mindustry.game.caves.CaveSaveStore;
import mindustry.game.caves.CaveWorldTransfer;
import mindustry.io.SaveIO;
import mindustry.world.blocks.production.Drill.DrillBuild;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

class CaveBackgroundTests{
    @BeforeAll static void init(){ ApplicationTests.launchApplication(); }
    @BeforeEach void load(){
        logic.reset();
        world.loadMap(ApplicationTests.testMap);
        state.set(State.playing);
    }

    private DrillBuild prepareDrill(boolean connected){
        for(int x = 66; x <= 73; x++) for(int y = 63; y <= 65; y++){
            var tile = world.tile(x, y);
            tile.setBlock(Blocks.air);
            tile.setFloor(Blocks.stone.asFloor());
            tile.setOverlay(Blocks.air);
        }
        for(int x = 72; x <= 73; x++) for(int y = 64; y <= 65; y++) world.tile(x, y).setOverlay(Blocks.oreCoal);
        for(int x = 66; x <= 71; x++) if(connected || x != 69) world.tile(x, 64).setBlock(Blocks.conveyor, Team.sharded, 2);
        world.tile(72, 64).setBlock(Blocks.mechanicalDrill, Team.sharded);
        DrillBuild drill = (DrillBuild)world.tile(72, 64).build;
        drill.onProximityUpdate();
        drill.warmup = 1f;
        drill.efficiency = 1f;
        return drill;
    }

    @Test void productionIsSavedAndRestoredWithTheCave(){
        boolean previous = Core.settings.getBool("experimental-caves", false);
        var file = saveDirectory.child("cave-background-test.msav");
        try{
            Core.settings.put("experimental-caves", true);
            state.rules.caves = true;
            CaveWorldTransfer.createCave(1234);
            prepareDrill(true);
            Team.sharded.core().items.set(Items.coal, 0);
            CaveWorldTransfer.travel();
            var background = CaveBackground.instance;
            assertNotNull(background.plan());
            assertEquals(1, background.plan().size());
            background.advance(1000);
            int first = Team.sharded.core().items.get(Items.coal);
            assertTrue(first > 0);
            SaveIO.save(file);
            logic.reset();
            SaveIO.load(file);
            state.set(State.playing);
            assertEquals(first, Team.sharded.core().items.get(Items.coal));
            assertEquals(1, background.plan().size());
            background.advance(1000);
            int total = Team.sharded.core().items.get(Items.coal);
            assertTrue(total > first);
            CaveWorldTransfer.travel();
            assertTrue(CaveSaveStore.instance.archive().inCave());
            assertEquals(total, Team.sharded.core().items.get(Items.coal));
            assertSame(Blocks.mechanicalDrill, world.tile(72, 64).block());
            assertNull(background.plan());
        }finally{
            Core.settings.put("experimental-caves", previous);
            file.delete();
            SaveIO.backupFileFor(file).delete();
        }
    }

    @Test void disconnectedDrillsAreNotCredited(){
        boolean previous = Core.settings.getBool("experimental-caves", false);
        try{
            Core.settings.put("experimental-caves", true);
            state.rules.caves = true;
            CaveWorldTransfer.createCave(1234);
            prepareDrill(false);
            CaveWorldTransfer.travel();
            assertEquals(0, CaveBackground.instance.plan().size());
            int coal = Team.sharded.core().items.get(Items.coal);
            CaveBackground.instance.advance(10000);
            assertEquals(coal, Team.sharded.core().items.get(Items.coal));
        }finally{
            Core.settings.put("experimental-caves", previous);
        }
    }
}
