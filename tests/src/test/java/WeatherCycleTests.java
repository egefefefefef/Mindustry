import arc.Core;
import arc.Events;
import mindustry.content.SectorPresets;
import mindustry.content.Weathers;
import mindustry.core.GameState.State;
import mindustry.game.EventType.RulesLoadEvent;
import mindustry.game.Rules;
import mindustry.game.WeatherCycle;
import mindustry.gen.Groups;
import mindustry.io.JsonIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

class WeatherCycleTests{
    @BeforeAll
    static void init(){ ApplicationTests.launchApplication(); }

    @BeforeEach
    void load(){
        logic.reset();
        world.loadMap(ApplicationTests.testMap);
        state.set(State.playing);
        Groups.weather.clear();
    }

    @Test
    void phasesAndNightAreContinuousAndRepeat(){
        double p = WeatherCycle.phaseTicks;
        assertEquals(WeatherCycle.Phase.clear, WeatherCycle.phaseAt(0));
        assertEquals(WeatherCycle.Phase.clear, WeatherCycle.phaseAt(p - 1));
        assertEquals(WeatherCycle.Phase.rain, WeatherCycle.phaseAt(p));
        assertEquals(WeatherCycle.Phase.fog, WeatherCycle.phaseAt(2 * p));
        assertEquals(WeatherCycle.Phase.snow, WeatherCycle.phaseAt(3 * p));
        assertEquals(WeatherCycle.Phase.clear, WeatherCycle.phaseAt(4 * p));
        assertEquals(WeatherCycle.Phase.clear, WeatherCycle.phaseAt(Double.NaN));
        assertEquals(0f, WeatherCycle.nightAlpha(0), 0.0001f);
        assertTrue(WeatherCycle.nightAlpha(WeatherCycle.dayTicks / 2) > 0.6f);
        assertEquals(0f, WeatherCycle.nightAlpha(WeatherCycle.dayTicks), 0.0001f);
    }

    @Test
    void rulesPersistAndOldRulesDefaultOff(){
        Rules rules = new Rules();
        assertFalse(rules.weatherCycle);
        rules.weatherCycle = true;
        assertTrue(JsonIO.read(Rules.class, JsonIO.write(rules)).weatherCycle);
        assertFalse(JsonIO.read(Rules.class, "{}").weatherCycle);
    }

    @Test
    void onlySpawnsOncePerPhaseAndDoesNotModifyGround(){
        state.rules.weatherCycle = true;
        var tile = world.tile(5, 5);
        var floor = tile.floor();
        var block = tile.block();
        var overlay = tile.overlay();
        state.tick = 0;
        WeatherCycle.update();
        assertEquals(0, Groups.weather.size());
        state.tick = WeatherCycle.phaseTicks;
        WeatherCycle.update();
        assertTrue(Weathers.rain.isActive());
        WeatherCycle.update();
        assertEquals(1, Groups.weather.size());
        Weathers.rain.remove();
        state.tick = WeatherCycle.phaseTicks * 2;
        WeatherCycle.update();
        assertTrue(Weathers.fog.isActive());
        Weathers.fog.remove();
        state.tick = WeatherCycle.phaseTicks * 3;
        WeatherCycle.update();
        assertTrue(Weathers.snow.isActive());
        assertSame(floor, tile.floor());
        assertSame(block, tile.block());
        assertSame(overlay, tile.overlay());
    }

    @Test
    void disabledAndMultiplayerAndEditorDoNotSpawnWeather() throws Exception{
        state.tick = WeatherCycle.phaseTicks;
        state.rules.weatherCycle = false;
        WeatherCycle.update();
        assertEquals(0, Groups.weather.size());
        state.rules.weatherCycle = true;
        state.rules.editor = true;
        WeatherCycle.update();
        assertEquals(0, Groups.weather.size());
        state.rules.editor = false;
        var active = net.getClass().getDeclaredField("active");
        active.setAccessible(true);
        active.setBoolean(net, true);
        try{
            WeatherCycle.update();
            assertEquals(0, Groups.weather.size());
        }finally{
            active.setBoolean(net, false);
        }
    }

    @Test
    void campaignPreferenceAppliesWhenSectorRulesLoad(){
        boolean previousHeadless = headless;
        boolean previousSetting = Core.settings.getBool("campaignweathercycle", false);
        try{
            headless = false;
            Rules rules = new Rules();
            rules.sector = SectorPresets.groundZero.sector;
            Core.settings.put("campaignweathercycle", true);
            Events.fire(new RulesLoadEvent(rules));
            assertTrue(rules.weatherCycle);
            Core.settings.put("campaignweathercycle", false);
            Events.fire(new RulesLoadEvent(rules, true));
            assertFalse(rules.weatherCycle);
        }finally{
            headless = previousHeadless;
            Core.settings.put("campaignweathercycle", previousSetting);
        }
    }
}
