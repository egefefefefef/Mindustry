package mindustry.graphics;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Mathf;
import arc.math.geom.Rect;
import mindustry.content.Weathers;
import mindustry.game.WeatherCycle;
import mindustry.gen.WeatherState;
import mindustry.world.Tile;

import static mindustry.Vars.*;

/** Temporary ground overlay only: never modifies tiles, liquids or buildings. */
public class WeatherGroundRenderer{
    private final Rect bounds = new Rect();
    private static final Color puddle = Color.valueOf("465e80");

    public void draw(){
        if(!WeatherCycle.enabled() || !renderer.drawWeather || !Core.settings.getBool("showweather", true)) return;
        WeatherState snow = Weathers.snow.instance(), rain = Weathers.rain.instance();
        float snowAlpha = snow == null ? 0f : snow.opacity * snow.intensity * renderer.weatherAlpha;
        float rainAlpha = rain == null ? 0f : rain.opacity * rain.intensity * renderer.weatherAlpha;
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
            Draw.color(puddle, Mathf.clamp(rainAlpha * 0.35f));
            for(int x = minX; x <= maxX; x += stride){
                for(int y = minY; y <= maxY; y += stride){
                    // Stable world-space pattern so puddles do not move with the camera.
                    int hash = (x * 73856093) ^ (y * 19349663);
                    if((hash & 7) != 0) continue;
                    Tile tile = world.tile(x, y);
                    if(tile != null && !tile.floor().isLiquid && !tile.block().solid){
                        Fill.circle(tile.worldx() + ((hash >>> 4) & 3) - 1.5f, tile.worldy(), 2f);
                    }
                }
            }
        }
        Draw.reset();
    }
}
