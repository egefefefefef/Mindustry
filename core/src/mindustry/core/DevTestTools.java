package mindustry.core;

import arc.Events;
import arc.math.Mathf;
import arc.struct.Seq;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Segmentc;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

import static mindustry.Vars.*;

/** Local-only test actions. Never send developer commands to a server. */
public class DevTestTools{
    public static final int maxPerBatch = 500, maxTracked = 2000;
    private final Seq<Tracked> spawned = new Seq<>();

    private record Tracked(Unit unit, int id){}
    public enum Result { ok, unavailable, invalid, tooMany, noSpace }

    public DevTestTools(){
        Events.on(ResetEvent.class, e -> spawned.clear());
        Events.on(WorldLoadEvent.class, e -> spawned.clear());
    }

    public boolean available(){
        return state != null && state.isGame() && !state.isEditor() && net != null && !net.active();
    }

    private void prune(){
        // Entities are pooled: verify both object identity and ID before acting on one.
        spawned.removeAll(t -> t.unit.id != t.id || !t.unit.isAdded() || Groups.unit.getByID(t.id) != t.unit);
    }

    public int trackedCount(){
        prune();
        return spawned.size;
    }

    public Result spawn(UnitType type, Team team, float x, float y, int count){
        if(!available()) return Result.unavailable;
        if(type == null || type.constructor == null || type.hidden || type.internal || team == null ||
            count < 1 || count > maxPerBatch || !Float.isFinite(x) || !Float.isFinite(y)) return Result.invalid;
        prune();
        long segments = type.segmentUnits > 1 && type.sample instanceof Segmentc ? (long)type.segmentUnits + 1 : 1;
        if(segments * count > maxTracked - spawned.size) return Result.tooMany;

        float spacing = Math.max(tilesize, type.hitSize * 1.1f);
        if(!Float.isFinite(spacing) || spacing <= 0f || world.width() <= 0 || world.height() <= 0) return Result.invalid;
        float maxX = (world.width() - 1) * tilesize, maxY = (world.height() - 1) * tilesize;
        int maxCols = Math.min(count, 1 + (int)(maxX / spacing));
        int maxRows = Math.min(count, 1 + (int)(maxY / spacing));
        if((long)maxCols * maxRows < count) return Result.noSpace;
        int cols = Math.min(maxCols, Math.max(1, (int)Math.ceil(Math.sqrt(count))));
        cols = Math.max(cols, (count + maxRows - 1) / maxRows);
        int rows = (count + cols - 1) / cols;
        float startX = Mathf.clamp(x - (cols - 1) * spacing / 2f, 0f, maxX - (cols - 1) * spacing);
        float startY = Mathf.clamp(y - (rows - 1) * spacing / 2f, 0f, maxY - (rows - 1) * spacing);
        for(int i = 0; i < count; i++){
            type.spawn(team, startX + (i % cols) * spacing, startY + (i / cols) * spacing, 0f,
                unit -> spawned.add(new Tracked(unit, unit.id)));
        }
        return Result.ok;
    }

    /** Only removes units created by this tool, never the current player. */
    public Result clear(){
        if(!available()) return Result.unavailable;
        prune();
        for(Tracked t : spawned){
            if(!t.unit.isPlayer()) t.unit.remove();
        }
        spawned.clear();
        return Result.ok;
    }

    /** Explicit opt-in: changes the current world's rules and can be saved. */
    public Result setUnitCapDisabled(boolean disabled){
        if(!available()) return Result.unavailable;
        state.rules.disableUnitCap = disabled;
        return Result.ok;
    }

    public Result nextWave(){
        if(!available()) return Result.unavailable;
        if(!state.rules.waves || logic == null || spawner == null) return Result.invalid;
        logic.runWave();
        return Result.ok;
    }
}
