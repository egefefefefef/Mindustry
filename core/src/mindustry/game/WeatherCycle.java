package mindustry.game;

import arc.util.Time;
import mindustry.content.Weathers;
import mindustry.gen.Call;
import mindustry.type.Weather;

import static mindustry.Vars.*;

/** Opt-in, local single-player weather cycle; does not alter map tiles. */
public class WeatherCycle{
    /** Each phase lasts three real-time game minutes. */
    public static final double phaseTicks = 3d * Time.toMinutes;
    /** Visual day and night take twelve real-time game minutes. */
    public static final double dayTicks = 12d * Time.toMinutes;

    public enum Phase { clear, rain, fog, snow }
    private static final Phase[] phases = Phase.values();

    public static boolean enabled(){
        return state != null && state.isGame() && !state.isEditor() && state.rules.weatherCycle && net != null && !net.active();
    }

    public static Phase phaseAt(double tick){
        if(!Double.isFinite(tick) || tick < 0) tick = 0;
        int index = (int)(Math.floor(tick / phaseTicks) % phases.length);
        return phases[index];
    }

    /** Smooth visual darkness; no changes to solar generation or map rules. */
    public static float nightAlpha(double tick){
        if(!Double.isFinite(tick) || tick < 0) tick = 0;
        return (float)((1.0 - Math.cos((tick % dayTicks) / dayTicks * Math.PI * 2.0)) * 0.33);
    }

    /** Called on the game thread only. Existing map weather entries are left intact. */
    public static void update(){
        if(!enabled()) return;
        double tick = state.tick;
        if(!Double.isFinite(tick) || tick < 0) tick = 0;
        Weather weather = switch(phaseAt(tick)){
            case clear -> null;
            case rain -> Weathers.rain;
            case fog -> Weathers.fog;
            case snow -> Weathers.snow;
        };
        if(weather != null && !weather.isActive()){
            float remaining = (float)(phaseTicks - tick % phaseTicks);
            Call.createWeather(weather, 1f, remaining, 1f, 0f);
        }
    }
}
