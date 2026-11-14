package mindustry.game.caves;

import java.util.*;

/** Deterministic cave layout; does not access or mutate the current game world. */
public final class CaveGenerator{
    public enum Tile { wall, floor, water, coal, titanium, thorium, amethyst, adamantite }

    public static final int minSize = 64, maxSize = 256;

    public static final class Layout{
        public final int width, height, entranceX, entranceY;
        private final Tile[] tiles;

        private Layout(int width, int height){
            this.width = width;
            this.height = height;
            entranceX = width / 2;
            entranceY = height / 2;
            tiles = new Tile[width * height];
            Arrays.fill(tiles, Tile.wall);
        }

        public Tile get(int x, int y){
            if(x < 0 || y < 0 || x >= width || y >= height) throw new IndexOutOfBoundsException();
            return tiles[x + y * width];
        }

        private void set(int x, int y, Tile tile){
            if(x > 0 && y > 0 && x < width - 1 && y < height - 1) tiles[x + y * width] = tile;
        }
    }

    private CaveGenerator(){}

    /** Generates connected tunnels, shallow pools and ore veins from a saved seed. */
    public static Layout generate(long seed, int width, int height){
        if(width < minSize || height < minSize || width > maxSize || height > maxSize){
            throw new IllegalArgumentException("Cave dimensions must be between " + minSize + " and " + maxSize);
        }
        Layout out = new Layout(width, height);
        Random random = new Random(seed);
        int x = out.entranceX, y = out.entranceY;
        carve(out, x, y, 10, Tile.floor);
        // Every walk starts at the entrance, keeping the resulting floor connected.
        int walkers = Math.max(30, width * height / 350);
        for(int w = 0; w < walkers; w++){
            x = out.entranceX;
            y = out.entranceY;
            int direction = random.nextInt(4);
            int steps = Math.max(width, height) + random.nextInt(Math.max(width, height));
            for(int i = 0; i < steps; i++){
                if(random.nextInt(5) == 0) direction = random.nextInt(4);
                x = Math.max(4, Math.min(width - 5, x + (direction == 0 ? 1 : direction == 1 ? -1 : 0)));
                y = Math.max(4, Math.min(height - 5, y + (direction == 2 ? 1 : direction == 3 ? -1 : 0)));
                carve(out, x, y, 1 + random.nextInt(3), Tile.floor);
            }
        }
        // Water replaces floor only and leaves a dry spawn clearing.
        for(int i = 0; i < width * height / 700; i++){
            int px = 2 + random.nextInt(width - 4), py = 2 + random.nextInt(height - 4);
            int radius = 2 + random.nextInt(4);
            for(int dx = -radius; dx <= radius; dx++){
                for(int dy = -radius; dy <= radius; dy++){
                    int nx = px + dx, ny = py + dy;
                    if(nx <= 0 || ny <= 0 || nx >= width - 1 || ny >= height - 1) continue;
                    if(Math.abs(nx - out.entranceX) <= 7 && Math.abs(ny - out.entranceY) <= 7) continue;
                    if(dx * dx + dy * dy <= radius * radius && out.get(nx, ny) == Tile.floor) out.set(nx, ny, Tile.water);
                }
            }
        }
        // Place rarer ores first so abundant coal cannot overwrite them.
        veins(out, random, Tile.adamantite, 5, 2);
        veins(out, random, Tile.amethyst, 8, 2);
        veins(out, random, Tile.thorium, 10, 3);
        veins(out, random, Tile.titanium, 15, 3);
        veins(out, random, Tile.coal, 38, 4);
        ensureMinimum(out, Tile.water, 8);
        ensureMinimum(out, Tile.adamantite, 8);
        ensureMinimum(out, Tile.amethyst, 12);
        ensureMinimum(out, Tile.thorium, 16);
        ensureMinimum(out, Tile.titanium, 20);
        ensureMinimum(out, Tile.coal, 40);
        return out;
    }

    private static void ensureMinimum(Layout out, Tile type, int minimum){
        int count = 0;
        for(Tile tile : out.tiles) if(tile == type) count++;
        for(int y = 1; y < out.height - 1 && count < minimum; y++){
            for(int x = 1; x < out.width - 1 && count < minimum; x++){
                if(Math.abs(x - out.entranceX) <= 7 && Math.abs(y - out.entranceY) <= 7) continue;
                if(out.get(x, y) == Tile.floor){
                    out.set(x, y, type);
                    count++;
                }
            }
        }
        if(count < minimum) throw new IllegalStateException("Not enough cave floor for " + type);
    }

    private static void carve(Layout out, int x, int y, int radius, Tile value){
        for(int dx = -radius; dx <= radius; dx++){
            for(int dy = -radius; dy <= radius; dy++){
                if(dx * dx + dy * dy <= radius * radius) out.set(x + dx, y + dy, value);
            }
        }
    }

    private static void veins(Layout out, Random random, Tile ore, int baseCount, int radius){
        int count = Math.max(1, baseCount * out.width * out.height / (128 * 128));
        for(int i = 0; i < count; i++){
            int px = 1 + random.nextInt(out.width - 2), py = 1 + random.nextInt(out.height - 2);
            for(int dx = -radius; dx <= radius; dx++){
                for(int dy = -radius; dy <= radius; dy++){
                    int nx = px + dx, ny = py + dy;
                    if(nx <= 0 || ny <= 0 || nx >= out.width - 1 || ny >= out.height - 1) continue;
                    if(Math.abs(nx - out.entranceX) <= 7 && Math.abs(ny - out.entranceY) <= 7) continue;
                    if(dx * dx + dy * dy <= radius * radius && out.get(nx, ny) == Tile.floor && random.nextFloat() < 0.8f){
                        out.set(nx, ny, ore);
                    }
                }
            }
        }
    }
}
