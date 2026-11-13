import arc.Core;
import arc.Events;
import mindustry.content.SectorPresets;
import mindustry.content.Weathers;
import mindustry.content.UnitTypes;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.core.GameState.State;
import mindustry.game.EventType.RulesLoadEvent;
import mindustry.game.Rules;
import mindustry.game.WeatherCycle;
import mindustry.gen.Groups;
import mindustry.io.JsonIO;
import mindustry.io.SaveIO;
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
        assertEquals(WeatherCycle.Phase.rainFog, WeatherCycle.phaseAt(4 * p));
        for(int cycle = 0; cycle < 20; cycle++){
            var found = java.util.EnumSet.noneOf(WeatherCycle.Phase.class);
            for(int i = 0; i < WeatherCycle.Phase.values().length; i++) found.add(WeatherCycle.phaseAt((cycle * 5 + i) * p));
            assertEquals(java.util.EnumSet.allOf(WeatherCycle.Phase.class), found);
        }
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
        Weathers.snow.remove();
        state.tick = WeatherCycle.phaseTicks * 4;
        WeatherCycle.update();
        assertTrue(Weathers.rain.isActive());
        assertTrue(Weathers.fog.isActive());
        assertEquals(2, Groups.weather.size());
        WeatherCycle.update();
        assertEquals(2, Groups.weather.size());
        assertEquals(WeatherCycle.intensityAt(state.tick, WeatherCycle.Phase.rain), Weathers.rain.instance().intensity, 0.0001f);
        assertEquals(WeatherCycle.intensityAt(state.tick, WeatherCycle.Phase.fog), Weathers.fog.instance().intensity, 0.0001f);
        assertSame(floor, tile.floor());
        assertSame(block, tile.block());
        assertSame(overlay, tile.overlay());
    }

    @Test
    void intensityVariesAndCoverBuildsAndMelts(){
        double p = WeatherCycle.phaseTicks;
        var levels = new java.util.HashSet<Float>();
        for(int slot = 0; slot < 30; slot++) levels.add(WeatherCycle.intensityAt(slot * p, WeatherCycle.Phase.rain));
        assertEquals(3, levels.size());
        assertEquals(0f, WeatherCycle.puddlesAt(p), 0.0001f);
        float wet = WeatherCycle.puddlesAt(1.5 * p);
        assertTrue(wet > 0f);
        assertTrue(WeatherCycle.puddlesAt(1.9 * p) >= wet);
        assertTrue(WeatherCycle.puddlesAt(2.9 * p) < WeatherCycle.puddlesAt(2 * p));
        assertEquals(0f, WeatherCycle.snowDepthAt(3 * p), 0.0001f);
        float snow = WeatherCycle.snowDepthAt(3.5 * p);
        assertTrue(snow > 0f);
        assertTrue(WeatherCycle.snowDepthAt(3.9 * p) >= snow);
        assertTrue(WeatherCycle.snowDepthAt(4.5 * p) < WeatherCycle.snowDepthAt(4 * p));
    }

    @Test
    void snowSlowsAllGroundTeamsButNotFlyingOrWaterUnits(){
        state.rules.disableUnitCap = true;
        var tile = world.tile(10, 10);
        tile.setFloor(Blocks.stone.asFloor());
        float x = tile.worldx(), y = tile.worldy();
        var ally = UnitTypes.dagger.spawn(Team.sharded, x, y);
        var enemy = UnitTypes.dagger.spawn(Team.crux, x + 8, y);
        var flying = UnitTypes.flare.spawn(Team.sharded, x + 16, y);
        state.rules.weatherCycle = false;
        float allyBase = ally.floorSpeedMultiplier(), enemyBase = enemy.floorSpeedMultiplier(), airBase = flying.floorSpeedMultiplier();
        state.rules.weatherCycle = true;
        state.tick = WeatherCycle.phaseTicks * 3.5;
        assertTrue(WeatherCycle.snowDepth() > 0);
        assertTrue(ally.floorSpeedMultiplier() < allyBase);
        assertTrue(enemy.floorSpeedMultiplier() < enemyBase);
        assertEquals(airBase, flying.floorSpeedMultiplier(), 0.0001f);
        tile.setFloor(Blocks.water.asFloor());
        state.rules.weatherCycle = false;
        float waterBase = ally.floorSpeedMultiplier();
        state.rules.weatherCycle = true;
        assertEquals(waterBase, ally.floorSpeedMultiplier(), 0.0001f);
        state.rules.weatherCycle = false;
        tile.setFloor(Blocks.stone.asFloor());
        assertEquals(allyBase, ally.floorSpeedMultiplier(), 0.0001f);
    }

    @Test
    void snowDepthAndRuleSurviveSavingAndReloading(){
        state.rules.weatherCycle = true;
        state.tick = WeatherCycle.phaseTicks * 3.5;
        float snow = WeatherCycle.snowDepth();
        var file = saveDirectory.child("weather-cycle-test.msav");
        try{
            SaveIO.save(file);
            logic.reset();
            SaveIO.load(file);
            state.set(State.playing);
            assertTrue(state.rules.weatherCycle);
            assertEquals(WeatherCycle.phaseTicks * 3.5, state.tick, 1.0);
            assertEquals(snow, WeatherCycle.snowDepth(), 0.0001f);
        }finally{
            file.delete();
        }
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
