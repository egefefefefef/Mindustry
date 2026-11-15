import arc.Core;
import mindustry.content.Blocks;
import mindustry.content.CaveContent;
import mindustry.content.Items;
import mindustry.world.blocks.distribution.Conveyor;
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
        var rubyDrill = (Drill)CaveContent.rubyDrill;
        for(var ore : new mindustry.world.Block[]{CaveContent.oreAmethyst, CaveContent.oreAdamantite, CaveContent.oreRuby}){
            tile.setOverlay(ore);
            assertFalse(pneumatic.canMine(tile));
            assertTrue(laser.canMine(tile));
            assertTrue(amethystDrill.canMine(tile));
            assertTrue(adamantiteDrill.canMine(tile));
            assertTrue(rubyDrill.canMine(tile));
            var item = tile.drop();
            float laserSpeed = laser.size * laser.size / laser.getDrillTime(item);
            float amethystSpeed = amethystDrill.size * amethystDrill.size / amethystDrill.getDrillTime(item);
            float adamantiteSpeed = adamantiteDrill.size * adamantiteDrill.size / adamantiteDrill.getDrillTime(item);
            assertTrue(amethystSpeed > laserSpeed);
            assertTrue(adamantiteSpeed > amethystSpeed);
            assertTrue(rubyDrill.size * rubyDrill.size / rubyDrill.getDrillTime(item) > adamantiteSpeed);
        }
    }

    @Test
    void rubyDrillBeatsVanillaDrillsEvenWithTheirLiquidBoost(){
        Drill ruby = (Drill)CaveContent.rubyDrill;
        for(var other : new Drill[]{(Drill)Blocks.pneumaticDrill, (Drill)Blocks.laserDrill,
            (Drill)Blocks.blastDrill, (Drill)Blocks.impactDrill, (Drill)Blocks.eruptionDrill,
            (Drill)CaveContent.amethystDrill, (Drill)CaveContent.adamantiteDrill}){
            for(var item : new mindustry.type.Item[]{Items.copper, Items.titanium, Items.thorium, Items.beryllium, CaveContent.ruby}){
                if(item.hardness > other.tier || item == other.blockedItem) continue;
                float otherRate = other.size * other.size / other.getDrillTime(item);
                float rubyRate = ruby.size * ruby.size / ruby.getDrillTime(item);
                assertTrue(rubyRate > otherRate, other.name + " on " + item.name);
                assertTrue(rubyRate * ruby.liquidBoostIntensity > otherRate * other.liquidBoostIntensity, other.name + " boosted on " + item.name);
            }
        }
        var belt = (Conveyor)CaveContent.rubyConveyor;
        assertTrue(belt.speed > ((Conveyor)Blocks.titaniumConveyor).speed);
        // A straight belt can accept another item after four 60 Hz ticks.
        assertEquals(4, (int)Math.ceil(0.4f / belt.speed));
        assertEquals(15f, belt.displayedSpeed);
    }

    @Test
    void cavesRequireBothSwitchesAndStayOutOfEditorAndNetwork() throws Exception{
        boolean previous = Core.settings.getBool("experimental-caves", false);
        try{
            assertFalse(JsonIO.read(Rules.class, "{}").caves);
            state.rules.caves = false;
            Core.settings.put("experimental-caves", false);
            assertFalse(CaveContent.available());
            assertTrue(CaveContent.ruby.isHidden());
            assertTrue(CaveContent.rubyDrill.isHidden());
            assertTrue(CaveContent.rubyConveyor.isHidden());
            state.rules.caves = true;
            assertFalse(CaveContent.available());
            Core.settings.put("experimental-caves", true);
            assertTrue(CaveContent.available());
            assertFalse(CaveContent.amethystDrill.isHidden());
            assertFalse(CaveContent.ruby.isHidden());
            assertFalse(CaveContent.rubyDrill.isHidden());
            assertFalse(CaveContent.rubyConveyor.isHidden());
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
