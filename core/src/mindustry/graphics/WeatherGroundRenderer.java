package mindustry.graphics;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Mathf;
import arc.math.geom.Rect;
import mindustry.game.WeatherCycle;
import mindustry.world.Tile;

import static mindustry.Vars.*;

/** Temporary ground overlay only: never modifies tiles, liquids or buildings. */
public class WeatherGroundRenderer{
    private final Rect bounds = new Rect();
    private final SnowTracks tracks = new SnowTracks();
    private static final Color puddle = Color.valueOf("293e5c");
    private static final Color glint = Color.valueOf("b8d7eb");

    public void draw(){
        if(!WeatherCycle.enabled() || !renderer.drawWeather || !Core.settings.getBool("showweather", true)) return;
        float snowAlpha = WeatherCycle.snowDepth() * renderer.weatherAlpha;
        float rainAlpha = WeatherCycle.puddles() * renderer.weatherAlpha;
        if(snowAlpha < 0.01f && rainAlpha < 0.01f) return;
        Core.camera.bounds(bounds).grow(tilesize);
        int minX = Math.max(0, Mathf.floor(bounds.x / tilesize));
        int minY = Math.max(0, Mathf.floor(bounds.y / tilesize));
        int maxX = Math.min(world.width() - 1, Mathf.ceil((bounds.x + bounds.width) / tilesize));
        int maxY = Math.min(world.height() - 1, Mathf.ceil((bounds.y + bounds.height) / tilesize));
        if(maxX < minX || maxY < minY) return;
        long area = (long)(maxX - minX + 1) * (maxY - minY + 1);
        // Bound draw calls at extreme zoom; sample tiles rather than mutating the map.
        int stride = Math.max(1, (int)Math.ceil(Math.sqrt(area / 20000.0)));
        if(snowAlpha >= 0.01f){
            Draw.color(Color.white, Mathf.clamp(snowAlpha * 0.64f));
            for(int x = minX; x <= maxX; x += stride){
                for(int y = minY; y <= maxY; y += stride){
                    Tile tile = world.tile(x, y);
                    if(tile != null && !tile.floor().isLiquid && !tile.block().solid){
                        Fill.square(tile.worldx(), tile.worldy(), tilesize / 2f);
                    }
                }
            }
        }
        if(rainAlpha >= 0.01f){
            // Contrast and baseline size make fresh puddles visible within seconds,
            // then both their size and coverage increase with accumulated rain.
            float growth = Mathf.clamp(rainAlpha * 2f);
            for(int x = minX; x <= maxX; x += stride){
                for(int y = minY; y <= maxY; y += stride){
                    int hash = (x * 73856093) ^ (y * 19349663);
                    if((hash & 15) >= 6 + (int)(growth * 7f)) continue;
                    Tile tile = world.tile(x, y);
                    if(tile != null && !tile.floor().isLiquid && !tile.block().solid){
                        float radius = 1.5f + 2.2f * growth;
                        float px = tile.worldx() + ((hash >>> 4) & 3) - 1.5f;
                        float py = tile.worldy() + ((hash >>> 6) & 3) - 1.5f;
                        Draw.color(puddle, Mathf.clamp((0.32f + 0.38f * growth) * renderer.weatherAlpha));
                        Fill.circle(px, py, radius);
                        if(growth > 0.18f) Fill.circle(px + radius * 0.65f, py + radius * 0.25f, radius * 0.7f);
                        // Soft moving reflections, not map water or physical liquid.
                        float shimmer = 0.5f + 0.5f * Mathf.sin((float)state.tick / 28f + (hash & 255));
                        Draw.color(glint, (0.15f + 0.2f * shimmer) * renderer.weatherAlpha);
                        Fill.circle(px - radius * 0.25f, py + radius * 0.25f, Math.max(0.35f, radius * 0.22f));
                    }
                }
            }
        }
        Draw.reset();
        tracks.draw(snowAlpha);
    }
}
