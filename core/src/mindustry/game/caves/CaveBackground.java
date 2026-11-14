package mindustry.game.caves;

import arc.Events;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.content.Blocks;
import mindustry.content.CaveContent;
import mindustry.ctype.ContentType;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.io.SaveFileReader.CustomChunk;
import mindustry.io.SaveVersion;
import mindustry.type.Item;
import mindustry.world.Edges;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.Conveyor.ConveyorBuild;
import mindustry.world.blocks.power.SolarGenerator;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.io.*;

import static mindustry.Vars.*;

/** A deliberately limited, approximate background model, not a second world simulation. */
public final class CaveBackground implements CustomChunk{
    public static final CaveBackground instance = new CaveBackground();
    private static final int version = 1, maxDrills = 4096;
    private static boolean installed;
    private Plan plan;

    private CaveBackground(){}

    public static void install(){
        if(installed) return;
        installed = true;
        SaveVersion.addCustomChunk("arena-cave-background-v1", instance);
        Events.on(ResetEvent.class, e -> instance.plan = null);
        Events.run(Trigger.afterGameUpdate, instance::update);
    }

    public static final class Plan{
        private final int team, capacity;
        private final Seq<Entry> entries = new Seq<>();
        private Plan(int team, int capacity){ this.team = team; this.capacity = capacity; }
        public int size(){ return entries.size; }
    }

    private static final class Entry{
        int pos;
        String item;
        float rate;
        double fraction;
        Entry(int pos, String item, float rate, double fraction){
            this.pos = pos; this.item = item; this.rate = rate; this.fraction = fraction;
        }
    }

    public Plan plan(){ return plan; }
    public void setPlan(Plan plan){ this.plan = plan; }

    /** Snapshot supported production routes while the cave is still the active world. */
    public static Plan capture(Team team){
        CoreBuild core = team.core();
        if(core == null || core.storageCapacity < 1) return null;
        Plan plan = new Plan(team.id, core.storageCapacity);
        IntSet used = new IntSet();
        for(Building b : mindustry.gen.Groups.build){
            if(!(b instanceof DrillBuild drill) || b.team != team || !drill.enabled || drill.dominantItem == null ||
                drill.dominantItems <= 0 || drill.efficiency < 0.99f || drill.warmup < 0.99f ||
                Math.abs(drill.timeScale() - 1f) > 0.001f || !steadyPower(drill)) continue;
            Drill type = (Drill)drill.block;
            float rate = drill.dominantItems / type.getDrillTime(drill.dominantItem);
            IntSet route = new IntSet();
            float maxRate = routeToCore(drill, core, route, used);
            if(maxRate <= 0 || !Float.isFinite(rate) || rate <= 0) continue;
            rate = Math.min(rate, maxRate);
            double fraction = drill.progress / type.getDrillTime(drill.dominantItem);
            if(!Float.isFinite(rate) || rate <= 0f || rate > 100f || !Double.isFinite(fraction)) continue;
            if(plan.entries.size >= maxDrills) break;
            plan.entries.add(new Entry(drill.pos(), drill.dominantItem.name, rate,
                Math.max(0d, Math.min(0.999999d, fraction))));
            for(var iter = route.iterator(); iter.hasNext;) used.add(iter.next());
        }
        return plan;
    }

    // Fuel, batteries and non-drill loads are intentionally not approximated.
    private static boolean steadyPower(DrillBuild drill){
        if(!drill.block.hasPower) return true;
        if(drill.power == null || drill.power.graph == null) return false;
        var graph = drill.power.graph;
        if(graph.producers.isEmpty()) return false;
        float supplied = 0f, needed = 0f;
        for(Building b : graph.producers){
            if(!(b.block instanceof SolarGenerator) || !b.enabled) return false;
            supplied += b.getPowerProduction();
        }
        for(Building b : graph.consumers){
            if(b.block.consPower == null || b.block.consPower.buffered) continue;
            if(!(b instanceof DrillBuild) && b.block.consPower.usage > 0f) return false;
            if(b.enabled) needed += b.block.consPower.usage;
        }
        return Float.isFinite(supplied) && Float.isFinite(needed) && supplied >= needed && needed > 0f;
    }

    private static float routeToCore(DrillBuild drill, CoreBuild core, IntSet route, IntSet used){
        for(Building next : drill.proximity){
            route.clear();
            if(next == core) return Float.MAX_VALUE;
            if(!(next instanceof ConveyorBuild) || next.team != drill.team) continue;
            Building previous = drill, current = next;
            float rate = Float.MAX_VALUE;
            while(current instanceof ConveyorBuild belt && (current.block == Blocks.conveyor || current.block == Blocks.titaniumConveyor) && current.team == drill.team &&
                current.enabled && !used.contains(current.pos()) && route.add(current.pos())){
                var facing = Edges.getFacingEdge(previous.tile, current.tile);
                if(facing == null) break;
                int diff = Math.floorMod(facing.relativeTo(current.tile.x, current.tile.y) - current.rotation, 4);
                if(diff == 2) break;
                Building front = belt.front();
                boolean competingSource = false;
                for(Building neighbor : belt.proximity){
                    if(neighbor != drill && neighbor != previous && neighbor != front && neighbor.team == drill.team && neighbor.block.outputsItems()){
                        competingSource = true;
                        break;
                    }
                }
                if(competingSource) break;
                rate = Math.min(rate, ((Conveyor)current.block).displayedSpeed / 60f);
                previous = current;
                current = front;
                if(current == core) return rate;
            }
        }
        route.clear();
        return 0f;
    }

    /** Advance only while playing on the surface; deposit directly into the shared core. */
    public void update(){ advance(Time.delta); }

    public void advance(float ticks){
        var archive = CaveSaveStore.instance.archive();
        if(plan == null || archive == null || archive.inCave() || CaveSaveStore.instance.isTransferring() ||
            !CaveContent.available() || !state.isPlaying() || !Float.isFinite(ticks) || ticks <= 0f) return;
        CoreBuild core = Team.get(plan.team).core();
        if(core == null) return;
        int capacity = Math.min(plan.capacity, core.storageCapacity);
        for(Entry entry : plan.entries){
            Item item = content.getByName(ContentType.item, entry.item);
            if(item == null) continue;
            int room = Math.max(0, capacity - core.items.get(item));
            if(room == 0) continue;
            double produced = entry.fraction + entry.rate * (double)ticks;
            int amount = (int)Math.min(room, Math.floor(produced));
            if(amount > 0) core.items.add(item, amount);
            entry.fraction = produced >= room ? 0d : produced - amount;
        }
    }

    /** Restore fractional drill progress after the original cave snapshot is reloaded. */
    public static void apply(Plan plan){
        if(plan == null) return;
        for(Entry entry : plan.entries){
            Building b = world.build(entry.pos);
            if(b instanceof DrillBuild drill && drill.team.id == plan.team && drill.dominantItem != null &&
                drill.dominantItem.name.equals(entry.item)){
                drill.progress = (float)(entry.fraction * ((Drill)drill.block).getDrillTime(drill.dominantItem));
            }
        }
    }

    @Override public boolean shouldWrite(){
        return plan != null && CaveSaveStore.instance.archive() != null && !CaveSaveStore.instance.isWritingSnapshot();
    }
    @Override public boolean writeNet(){ return false; }
    @Override public void write(DataOutput out) throws IOException{
        out.writeInt(version); out.writeInt(plan.team); out.writeInt(plan.capacity); out.writeInt(plan.entries.size);
        for(Entry e : plan.entries){ out.writeInt(e.pos); out.writeUTF(e.item); out.writeFloat(e.rate); out.writeDouble(e.fraction); }
    }
    @Override public void read(DataInput in) throws IOException{
        if(in.readInt() != version) throw new IOException("Unsupported background cave version");
        int team = in.readInt(), capacity = in.readInt(), count = in.readInt();
        if(team < 0 || team > 255 || capacity < 1 || count < 0 || count > maxDrills) throw new IOException("Invalid cave background header");
        Plan loaded = new Plan(team, capacity);
        for(int i = 0; i < count; i++){
            int pos = in.readInt(); String item = in.readUTF(); float rate = in.readFloat(); double fraction = in.readDouble();
            if(item.isEmpty() || !Float.isFinite(rate) || rate <= 0f || rate > 100f || !Double.isFinite(fraction) || fraction < 0d || fraction >= 1d){
                throw new IOException("Invalid cave background drill");
            }
            loaded.entries.add(new Entry(pos, item, rate, fraction));
        }
        plan = loaded;
    }
}
