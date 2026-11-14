package mindustry.game.caves;

import mindustry.content.Blocks;
import mindustry.content.CaveContent;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.Tiles;

/** Converts a deterministic cave layout into real Mindustry terrain. */
public final class CaveMapGenerator{
    private CaveMapGenerator(){}

    /** Call only inside World.loadGenerator, with the world in generating mode. */
    public static void populate(Tiles tiles, long seed){
        CaveGenerator.Layout layout = CaveGenerator.generate(seed, tiles.width, tiles.height);
        for(int y = 0; y < tiles.height; y++){
            for(int x = 0; x < tiles.width; x++){
                var terrain = layout.get(x, y);
                Block floor = terrain == CaveGenerator.Tile.water ? Blocks.water : Blocks.stone;
                Block wall = terrain == CaveGenerator.Tile.wall ? Blocks.stoneWall : Blocks.air;
                Block ore = switch(terrain){
                    case coal -> Blocks.oreCoal;
                    case titanium -> Blocks.oreTitanium;
                    case thorium -> Blocks.oreThorium;
                    case amethyst -> CaveContent.oreAmethyst;
                    case adamantite -> CaveContent.oreAdamantite;
                    default -> Blocks.air;
                };
                tiles.set(x, y, new Tile(x, y, floor, ore, wall));
            }
        }
    }
}
