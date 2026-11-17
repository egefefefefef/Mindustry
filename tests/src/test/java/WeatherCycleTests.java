import arc.Core;
import arc.Events;
import mindustry.content.SectorPresets;
import mindustry.async.PhysicsProcess;
import mindustry.core.Version;
import mindustry.content.Weathers;
import mindustry.content.UnitTypes;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.core.GameState.State;
import mindustry.game.EventType.RulesLoadEvent;
import mindustry.game.Rules;
import mindustry.game.WeatherCycle;
import mindustry.type.Weather.WeatherEntry;
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
    void rainPreferenceChangesCurrentRainAndPuddlesWithoutChangingOtherWeather(){
        int previous = Core.settings.getInt("weathercycle-rainmode", 0);
        try{
            state.rules.weatherCycle = true;
            state.tick = WeatherCycle.phaseTicks + 10 * 60;
            Core.settings.put("weathercycle-rainmode", 1);
            WeatherCycle.update();
            assertEquals(0.55f, Weathers.rain.instance().intensity, 0.0001f);
            float normal = WeatherCycle.puddles();
            assertTrue(normal > 0.01f, "Puddles should start appearing early in a rain phase");
            var rain = Weathers.rain.instance();
            Core.settings.put("weathercycle-rainmode", 2);
            WeatherCycle.update();
            assertSame(rain, Weathers.rain.instance());
            assertEquals(1f, rain.intensity, 0.0001f);
            assertTrue(WeatherCycle.puddles() > normal, "Downpour accumulates more water");
            assertEquals(1, Groups.weather.size());
            Core.settings.put("weathercycle-rainmode", 0);
            WeatherCycle.update();
            assertEquals(WeatherCycle.intensityAt(state.tick, WeatherCycle.Phase.rain), rain.intensity, 0.0001f);
            Core.settings.put("weathercycle-rainmode", 99);
            assertEquals(0, WeatherCycle.rainMode());
            state.rules.weatherCycle = false;
            assertEquals(0f, WeatherCycle.puddles(), 0.0001f);
        }finally{
            Core.settings.put("weathercycle-rainmode", previous);
        }
    }

    @Test
    void alwaysRainStartsImmediatelyAcrossCyclePhasesAndCanBeStopped(){
        int previousMode = Core.settings.getInt("weathercycle-rainmode", 0);
        try{
            state.rules.weather.clear();
            var entry = new WeatherEntry(Weathers.rain);
            entry.always = true;
            entry.intensity = 0.7f;
            state.rules.weather.add(entry);
            state.rules.weatherCycle = true;
            state.tick = 0; // normally the first phase is clear
            Core.settings.put("weathercycle-rainmode", 2);
            WeatherCycle.update();
            var rain = Weathers.rain.instance();
            assertNotNull(rain, "Always-on rain must not wait for a cycle phase");
            assertEquals(1f, rain.intensity, 0.0001f);
            assertTrue(Float.isFinite(rain.life));
            rain.opacity(0.5f);
            assertTrue(WeatherCycle.puddles() > 0f, "Always-on rain builds puddles during clear phases");
            rain.life(1f);
            state.tick = 2 * WeatherCycle.phaseTicks;
            WeatherCycle.update();
            assertSame(rain, Weathers.rain.instance());
            assertTrue(rain.life > 1f);
            assertTrue(Weathers.fog.isActive());
            Core.settings.put("weathercycle-rainmode", 1);
            WeatherCycle.update();
            assertEquals(0.55f, rain.intensity, 0.0001f);
            Core.settings.put("weathercycle-rainmode", 0);
            WeatherCycle.update();
            assertEquals(0.7f, rain.intensity, 0.0001f);
            entry.always = false;
            state.tick = 0;
            rain.life(-1f);
            rain.update();
            WeatherCycle.update();
            assertFalse(Weathers.rain.isActive(), "Removed always-on rain must not restart in a clear phase");
        }finally{
            Core.settings.put("weathercycle-rainmode", previousMode);
        }
    }

    @Test
    void alwaysRainHonorsSelectorWhenCycleIsDisabled() throws Exception{
        int previousMode = Core.settings.getInt("weathercycle-rainmode", 0);
        try{
            state.rules.weather.clear();
            var entry = new WeatherEntry(Weathers.rain);
            entry.always = true;
            entry.intensity = 0.7f;
            state.rules.weather.add(entry);
            state.rules.weatherCycle = false;
            var update = mindustry.core.Logic.class.getDeclaredMethod("updateWeather");
            update.setAccessible(true);
            Core.settings.put("weathercycle-rainmode", 2);
            update.invoke(logic);
            assertNotNull(Weathers.rain.instance());
            assertEquals(1f, Weathers.rain.instance().intensity, 0.0001f);
            Core.settings.put("weathercycle-rainmode", 1);
            update.invoke(logic);
            assertEquals(0.55f, Weathers.rain.instance().intensity, 0.0001f);
            Core.settings.put("weathercycle-rainmode", 0);
            update.invoke(logic);
            assertEquals(0.7f, Weathers.rain.instance().intensity, 0.0001f);
        }finally{
            Core.settings.put("weathercycle-rainmode", previousMode);
        }
    }

    @Test
    void snowSlowsAllGroundTeamsButNotFlyingOrWaterUnits(){
        state.rules.disableUnitCap = true;
        var tile = world.tile(10, 10);
        tile.setBlock(Blocks.air);
        tile.setFloor(Blocks.stone.asFloor());
        var enemyTile = world.tile(11, 10);
        enemyTile.setBlock(Blocks.air);
        enemyTile.setFloor(Blocks.stone.asFloor());
        float x = tile.worldx(), y = tile.worldy();
        var ally = UnitTypes.dagger.spawn(Team.sharded, x, y);
        var enemy = UnitTypes.dagger.spawn(Team.crux, x + 8, y);
        var flying = UnitTypes.flare.spawn(Team.sharded, x + 16, y);
        flying.elevation = 1f;
        var tank = UnitTypes.stell.spawn(Team.sharded, x, y);
        var crawler = UnitTypes.crawler.spawn(Team.crux, x, y);
        state.rules.weatherCycle = false;
        float tankBase = tank.speed(), crawlerBase = crawler.speed();
        float allyBase = ally.speed(), enemyBase = enemy.speed(), airBase = flying.speed();
        state.rules.weatherCycle = true;
        state.tick = WeatherCycle.phaseTicks * 3.5;
        assertTrue(WeatherCycle.snowDepth() > 0);
        assertTrue(ally.speed() < allyBase);
        assertTrue(enemy.speed() < enemyBase);
        assertTrue(tank.speed() < tankBase);
        assertTrue(crawler.speed() < crawlerBase);
        assertEquals(airBase, flying.speed(), 0.0001f);
        tile.setFloor(Blocks.water.asFloor());
        state.rules.weatherCycle = false;
        float waterBase = ally.speed();
        state.rules.weatherCycle = true;
        assertEquals(waterBase, ally.speed(), 0.0001f);
        state.rules.weatherCycle = false;
        tile.setFloor(Blocks.stone.asFloor());
        assertEquals(allyBase, ally.speed(), 0.0001f);
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
    void experimentalWeatherPreferenceHandlesCustomWorldsAndPreservesOldRules() throws Exception{
        boolean oldHeadless = headless;
        boolean oldPreference = Core.settings.getBool("campaignweathercycle", false);
        try{
            headless = false;
            Rules rules = new Rules();
            Core.settings.put("campaignweathercycle", true);
            Events.fire(new RulesLoadEvent(rules, true));
            assertTrue(rules.weatherCycle);
            Core.settings.put("campaignweathercycle", false);
            Events.fire(new RulesLoadEvent(rules, true));
            assertTrue(rules.weatherCycle, "Existing per-world custom rules must not be erased");
            Rules fresh = new Rules();
            Events.fire(new RulesLoadEvent(fresh));
            assertFalse(fresh.weatherCycle);
            Core.settings.put("campaignweathercycle", true);
            fresh.editor = true;
            Events.fire(new RulesLoadEvent(fresh));
            assertFalse(fresh.weatherCycle);
            fresh.editor = false;
            var active = net.getClass().getDeclaredField("active");
            active.setAccessible(true);
            active.setBoolean(net, true);
            try{
                Events.fire(new RulesLoadEvent(fresh));
                assertFalse(fresh.weatherCycle);
            }finally{
                active.setBoolean(net, false);
            }
        }finally{
            headless = oldHeadless;
            Core.settings.put("campaignweathercycle", oldPreference);
        }
    }

    @Test
    void rustPhysicsPreferenceAndCommandLineOverride(){
        String property = System.getProperty("mindustry.experimentalNativePhysics");
        boolean previous = Core.settings.getBool("experimental-native-physics", Version.nativePhysicsDefault);
        try{
            System.clearProperty("mindustry.experimentalNativePhysics");
            Core.settings.put("experimental-native-physics", false);
            assertFalse(PhysicsProcess.nativePhysicsEnabled());
            Core.settings.put("experimental-native-physics", true);
            assertTrue(PhysicsProcess.nativePhysicsEnabled());
            System.setProperty("mindustry.experimentalNativePhysics", "false");
            assertFalse(PhysicsProcess.nativePhysicsEnabled());
            Core.settings.put("experimental-native-physics", false);
            System.setProperty("mindustry.experimentalNativePhysics", "true");
            assertTrue(PhysicsProcess.nativePhysicsEnabled());
        }finally{
            Core.settings.put("experimental-native-physics", previous);
            if(property == null) System.clearProperty("mindustry.experimentalNativePhysics");
            else System.setProperty("mindustry.experimentalNativePhysics", property);
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
