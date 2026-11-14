import mindustry.content.Blocks;
import mindustry.content.CaveContent;
import mindustry.game.caves.CaveMapGenerator;
import mindustry.world.blocks.production.Drill;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

class CaveMapGeneratorTests{
    @BeforeAll
    static void init(){ ApplicationTests.launchApplication(); }

    @Test
    void generatesActualMineableTerrainAndInteriorWater(){
        logic.reset();
        world.loadGenerator(128, 128, tiles -> CaveMapGenerator.populate(tiles, 12345));
        int water = 0, coal = 0, titanium = 0, thorium = 0, amethyst = 0, adamantite = 0;
        var laser = (Drill)Blocks.laserDrill;
        for(int y = 0; y < world.height(); y++) for(int x = 0; x < world.width(); x++){
            var tile = world.tile(x, y);
            if(tile.floor() == Blocks.water) water++;
            if(tile.overlay() == Blocks.oreCoal) coal++;
            if(tile.overlay() == Blocks.oreTitanium) titanium++;
            if(tile.overlay() == Blocks.oreThorium) thorium++;
            if(tile.overlay() == CaveContent.oreAmethyst){ amethyst++; assertTrue(laser.canMine(tile)); }
            if(tile.overlay() == CaveContent.oreAdamantite){ adamantite++; assertTrue(laser.canMine(tile)); }
        }
        assertTrue(water >= 8);
        assertTrue(coal >= 40);
        assertTrue(titanium >= 20);
        assertTrue(thorium >= 16);
        assertTrue(amethyst >= 12);
        assertTrue(adamantite >= 8);
        assertSame(Blocks.stone, world.tile(64, 64).floor());
        assertSame(Blocks.air, world.tile(64, 64).block());
    }
}
