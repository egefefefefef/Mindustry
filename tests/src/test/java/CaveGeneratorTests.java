import mindustry.game.caves.CaveGenerator;
import mindustry.game.caves.CaveGenerator.Layout;
import mindustry.game.caves.CaveGenerator.Tile;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CaveGeneratorTests{
    @Test
    void layoutsAreDeterministicAndVaryWithSeed(){
        Layout a = CaveGenerator.generate(1234, 128, 128);
        Layout b = CaveGenerator.generate(1234, 128, 128);
        Layout c = CaveGenerator.generate(1235, 128, 128);
        int different = 0;
        for(int y = 0; y < a.height; y++) for(int x = 0; x < a.width; x++){
            assertEquals(a.get(x, y), b.get(x, y));
            if(a.get(x, y) != c.get(x, y)) different++;
        }
        assertTrue(different > 100);
    }

    @Test
    void manySeedsHaveConnectedPathsLakesAndEveryOre(){
        for(int size : new int[]{64, 128, 256}) for(int seed = 0; seed < 12; seed++){
            Layout layout = CaveGenerator.generate(seed, size, size);
            int[] counts = new int[Tile.values().length];
            for(int y = 0; y < size; y++) for(int x = 0; x < size; x++){
                Tile tile = layout.get(x, y);
                counts[tile.ordinal()]++;
                if(x == 0 || y == 0 || x == size - 1 || y == size - 1) assertEquals(Tile.wall, tile);
                if(Math.abs(x - layout.entranceX) <= 4 && Math.abs(y - layout.entranceY) <= 4){
                    assertEquals(Tile.floor, tile, "The core and entrance need dry clear ground");
                }
            }
            assertTrue(counts[Tile.water.ordinal()] >= 8);
            assertTrue(counts[Tile.coal.ordinal()] >= 40);
            assertTrue(counts[Tile.titanium.ordinal()] >= 20);
            assertTrue(counts[Tile.thorium.ordinal()] >= 16);
            assertTrue(counts[Tile.amethyst.ordinal()] >= 12);
            assertTrue(counts[Tile.adamantite.ordinal()] >= 8);

            boolean[] seen = new boolean[size * size];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            int start = layout.entranceX + layout.entranceY * size;
            seen[start] = true;
            queue.add(start);
            int visited = 0;
            while(!queue.isEmpty()){
                int pos = queue.remove(), x = pos % size, y = pos / size;
                visited++;
                for(int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}){
                    int nx = x + d[0], ny = y + d[1];
                    if(nx < 0 || ny < 0 || nx >= size || ny >= size) continue;
                    int next = nx + ny * size;
                    if(!seen[next] && layout.get(nx, ny) != Tile.wall){
                        seen[next] = true;
                        queue.add(next);
                    }
                }
            }
            // Shallow water and ore are traversable; every carved region is reachable.
            assertEquals(size * size - counts[Tile.wall.ordinal()], visited, "seed " + seed);
        }
    }

    @Test
    void rejectsUnsupportedSizes(){
        assertThrows(IllegalArgumentException.class, () -> CaveGenerator.generate(1, 63, 128));
        assertThrows(IllegalArgumentException.class, () -> CaveGenerator.generate(1, 128, 257));
    }
}
