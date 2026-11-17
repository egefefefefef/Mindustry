package mindustry.game;

import arc.Core;
import arc.util.Time;
import mindustry.content.Weathers;
import mindustry.gen.Call;
import mindustry.type.Weather;
import mindustry.type.Weather.WeatherEntry;

import static mindustry.Vars.*;

/** Opt-in local single-player weather. Conditions derive from the saved game tick. */
public class WeatherCycle{
    public static final double phaseTicks = 3d * Time.toMinutes;
    public static final double dayTicks = 12d * Time.toMinutes;
    private static final double snowBuildTicks = 90d * Time.toSeconds, snowMeltTicks = 4d * Time.toMinutes;
    private static final double puddleBuildTicks = 45d * Time.toSeconds, puddleDryTicks = 2d * Time.toMinutes;

    public enum Phase { clear, rain, fog, snow, rainFog }
    private static final Phase[] phases = Phase.values();
    private static double cachedTick = Double.NaN;
    private static float cachedSnow, cachedPuddle;
    private static int cachedRainMode = -1;

    public static boolean localGame(){
        return state != null && state.isGame() && !state.isEditor() && net != null && !net.active();
    }

    public static boolean enabled(){
        return localGame() && state.rules.weatherCycle && !state.rules.caveInterior;
    }

    private static double validTick(double tick){
        return Double.isFinite(tick) && tick >= 0 ? tick : 0;
    }

    // Deterministic permutations preserve all five kinds of weather within each
    // cycle while changing their order between cycles. New games begin clear.
    private static Phase phaseForSlot(long slot){
        if(slot < 0) return Phase.clear;
        long cycle = slot / phases.length;
        int position = (int)(slot % phases.length);
        if(cycle == 0) return phases[position];
        long hash = mix(cycle);
        int offset = (int)Long.remainderUnsigned(hash, phases.length);
        int step = (hash & 8L) == 0 ? 1 : -1;
        return phases[Math.floorMod(offset + position * step, phases.length)];
    }

    private static long mix(long value){
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static long slotAt(double tick){
        return (long)(validTick(tick) / phaseTicks);
    }

    public static Phase phaseAt(double tick){
        return phaseForSlot(slotAt(tick));
    }

    /** Per-phase intensity, independently varied for rain, fog and snow. */
    public static float intensityAt(double tick, Phase kind){
        return intensityForSlot(slotAt(tick), kind);
    }

    /** Local preference: 0 = automatic, 1 = normal rain, 2 = downpour. */
    public static int rainMode(){
        int mode = Core.settings == null ? 0 : Core.settings.getInt("weathercycle-rainmode", 0);
        return mode < 0 || mode > 2 ? 0 : mode;
    }

    public static float rainIntensityAt(double tick, int mode){
        return rainIntensityForSlot(slotAt(tick), mode);
    }

    /** For an explicitly configured always-on rain entry, automatic uses its own intensity. */
    public static float alwaysRainIntensity(WeatherEntry entry){
        int mode = rainMode();
        return mode == 1 ? 0.55f : mode == 2 ? 1f : entry.intensity;
    }

    private static WeatherEntry alwaysEntry(Weather weather){
        for(WeatherEntry entry : state.rules.weather){
            if(entry.always && entry.weather == weather) return entry;
        }
        return null;
    }

    private static float rainIntensityForSlot(long slot, int mode){
        return mode == 1 ? 0.55f : mode == 2 ? 1f : intensityForSlot(slot, Phase.rain);
    }

    private static float intensityForSlot(long slot, Phase kind){
        long mixed = mix(slot * 0x9e3779b97f4a7c15L + kind.ordinal() * 0x632be59bd9b4e019L);
        return switch((int)Long.remainderUnsigned(mixed, 3)){
            case 0 -> 0.4f;
            case 1 -> 0.7f;
            default -> 1f;
        };
    }

    /** Gradual world-wide snow and wetness; history is derived from the saved tick. */
    private static float coverAt(double tick, boolean snow, int rainMode){
        tick = validTick(tick);
        long current = slotAt(tick);
        float result = 0f;
        // Old contributions have fully melted/dried before eight phases pass.
        for(int i = 0; i < 8 && current >= i; i++){
            long slot = current - i;
            Phase phase = phaseForSlot(slot);
            if(snow ? phase != Phase.snow : phase != Phase.rain && phase != Phase.rainFog) continue;
            double start = slot * phaseTicks, end = start + phaseTicks;
            float strength = snow ? intensityForSlot(slot, Phase.snow) : rainIntensityForSlot(slot, rainMode);
            double activeTime = Math.max(0, Math.min(tick, end) - start);
            double build = snow ? snowBuildTicks : puddleBuildTicks;
            double dry = snow ? snowMeltTicks : puddleDryTicks;
            float coverage = (float)(strength * Math.min(1.0, activeTime / build) - Math.max(0, tick - end) / dry);
            result = Math.max(result, coverage);
        }
        return Math.min(1f, Math.max(0f, result));
    }

    public static float snowDepthAt(double tick){ return coverAt(tick, true, 0); }
    public static float puddlesAt(double tick){ return puddlesAt(tick, 0); }
    public static float puddlesAt(double tick, int rainMode){ return coverAt(tick, false, rainMode); }

    private static void refreshCover(){
        double tick = validTick(state.tick);
        int mode = rainMode();
        if(cachedTick != tick || cachedRainMode != mode){
            cachedTick = tick;
            cachedRainMode = mode;
            cachedSnow = snowDepthAt(tick);
            cachedPuddle = puddlesAt(tick, mode);
        }
    }

    public static float snowDepth(){
        if(!enabled()) return 0f;
        refreshCover();
        return cachedSnow;
    }

    public static float puddles(){
        if(!enabled()) return 0f;
        refreshCover();
        // A map can request continuous rain even during a clear cycle phase.
        // Fade in with the actual weather so fresh rain does not instantly flood.
        var rain = Weathers.rain.instance();
        if(alwaysEntry(Weathers.rain) != null && rain != null){
            return Math.max(cachedPuddle, Math.min(1f, rain.opacity * rain.intensity));
        }
        return cachedPuddle;
    }

    /** Up to 25% slower in deep snow, before other movement modifiers. */
    public static float groundSpeedMultiplier(){
        return 1f - 0.25f * snowDepth();
    }

    /** Smooth visual darkness; no changes to solar generation or map rules. */
    public static float nightAlpha(double tick){
        tick = validTick(tick);
        return (float)((1.0 - Math.cos((tick % dayTicks) / dayTicks * Math.PI * 2.0)) * 0.33);
    }

    private static void ensureWeather(Weather weather, float intensity, float remaining){
        if(weather != null && !weather.isActive()) Call.createWeather(weather, intensity, remaining, 1f, 0f);
    }

    /** Called on the game thread only. Scheduled entries pause, but explicit
     * always-on entries remain active even during a clear cycle phase. */
    public static void update(){
        if(!enabled()) return;
        double tick = validTick(state.tick);
        Phase phase = phaseAt(tick);
        float remaining = (float)(phaseTicks - tick % phaseTicks);

        // Renew a short lifetime rather than making a permanent infinite entity;
        // removing the always rule then lets the weather fade normally.
        float renewal = 15f * Time.toSeconds;
        for(WeatherEntry entry : state.rules.weather){
            if(!entry.always || entry.weather == null) continue;
            float intensity = entry.weather == Weathers.rain ? alwaysRainIntensity(entry) : entry.intensity;
            ensureWeather(entry.weather, intensity, renewal);
            var instance = entry.weather.instance();
            if(instance != null){
                if(!Float.isFinite(instance.life) || instance.life < renewal) instance.life(renewal);
                if(entry.weather == Weathers.rain) instance.intensity(intensity);
            }
        }

        if(phase == Phase.rain || phase == Phase.rainFog){
            var alwaysRain = alwaysEntry(Weathers.rain);
            float intensity = alwaysRain == null ? rainIntensityAt(tick, rainMode()) : alwaysRainIntensity(alwaysRain);
            ensureWeather(Weathers.rain, intensity, remaining);
            var rain = Weathers.rain.instance();
            if(rain != null) rain.intensity(intensity);
        }
        if(phase == Phase.fog || phase == Phase.rainFog){
            ensureWeather(Weathers.fog, intensityAt(tick, Phase.fog), remaining);
        }
        if(phase == Phase.snow){
            ensureWeather(Weathers.snow, intensityAt(tick, Phase.snow), remaining);
        }
    }
}
