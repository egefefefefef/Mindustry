package mindustry.graphics;

import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.util.Time;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.WeatherCycle;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

import static mindustry.Vars.*;

/** Bounded, transient visual footprints; no map mutations or saved entities. */
public class SnowTracks{
    static final int maxTracks = 4096, maxUnitsPerFrame = 512;
    static final double life = 90d * Time.toSeconds;
    private final float[] xs = new float[maxTracks], ys = new float[maxTracks], angles = new float[maxTracks], sizes = new float[maxTracks];
    private final double[] born = new double[maxTracks];
    private final Rect bounds = new Rect();
    private int next, count, cursor;
    private static final Color footprint = Color.valueOf("596b7b");

    public SnowTracks(){
        Events.on(ResetEvent.class, e -> clear());
        Events.on(WorldLoadEvent.class, e -> clear());
        Events.run(Trigger.afterGameUpdate, this::update);
    }

    private void clear(){ next = count = cursor = 0; }

    private void update(){
        if(!WeatherCycle.enabled() || WeatherCycle.snowDepth() < 0.08f || !renderer.drawWeather || !arc.Core.settings.getBool("showweather", true)) return;
        int total = Groups.unit.size();
        if(total == 0) return;
        int toScan = Math.min(total, maxUnitsPerFrame);
        cursor %= total;
        for(int i = 0; i < toScan; i++){
            Unit unit = Groups.unit.index((cursor + i) % total);
            if(unit == null || !unit.isGrounded() || (unit.type.hovering && !unit.type.allowLegStep) || unit.floorOn().isLiquid || unit.deltaLen() < 0.05f) continue;
            // Stagger prints per unit, avoiding a burst every frame.
            if(Math.floorMod((state.updateId ^ (state.updateId >>> 4)) + unit.id, 12) != 0) continue;
            float angle = unit.deltaAngle(), side = ((state.updateId / 12 + unit.id) & 1) == 0 ? 1f : -1f;
            float spread = Mathf.clamp(unit.hitSize * 0.12f, 1f, 4f) * side;
            xs[next] = unit.x + Angles.trnsx(angle + 90f, spread);
            ys[next] = unit.y + Angles.trnsy(angle + 90f, spread);
            angles[next] = angle;
            sizes[next] = Mathf.clamp(unit.hitSize * 0.18f, 1.5f, 5f);
            born[next] = state.tick;
            next = (next + 1) % maxTracks;
            count = Math.min(count + 1, maxTracks);
        }
        cursor = (cursor + toScan) % total;
    }

    public void draw(float snow){
        if(snow < 0.01f || count == 0) return;
        arc.Core.camera.bounds(bounds).grow(tilesize);
        for(int i = 0; i < count; i++){
            double age = state.tick - born[i];
            if(age < 0 || age >= life || !bounds.contains(xs[i], ys[i])) continue;
            float alpha = (float)(1.0 - age / life) * Math.min(1f, snow * 2f) * 0.65f;
            Draw.color(footprint, alpha);
            Draw.rect("circle", xs[i], ys[i], sizes[i], sizes[i] * 0.6f, angles[i]);
        }
        Draw.reset();
    }
}
