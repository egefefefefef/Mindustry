import mindustry.content.UnitTypes;
import mindustry.core.DevTestTools;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

class DeveloperPanelTests{
    @BeforeAll
    static void init(){ ApplicationTests.launchApplication(); }

    @BeforeEach
    void load(){
        logic.reset();
        world.loadMap(ApplicationTests.testMap);
        state.set(State.playing);
    }

    @Test
    void spawnAndClearOnlyPanelUnits(){
        DevTestTools tools = new DevTestTools();
        Unit existing = UnitTypes.dagger.spawn(Team.sharded, 100, 100);
        int before = Groups.unit.size();
        assertEquals(DevTestTools.Result.ok, tools.spawn(UnitTypes.dagger, Team.crux, 1, 1, 12));
        assertEquals(12, tools.trackedCount());
        assertEquals(before + 12, Groups.unit.size());
        assertEquals(DevTestTools.Result.ok, tools.clear());
        assertTrue(existing.isAdded());
        assertEquals(before, Groups.unit.size());
        assertEquals(0, tools.trackedCount());
    }

    @Test
    void rejectInvalidAmountsAndStopOnlineOrInEditor() throws Exception{
        DevTestTools tools = new DevTestTools();
        assertEquals(DevTestTools.Result.invalid, tools.spawn(UnitTypes.dagger, Team.sharded, 10, 10, 0));
        assertEquals(DevTestTools.Result.invalid, tools.spawn(UnitTypes.dagger, Team.sharded, 10, 10, 501));
        assertEquals(DevTestTools.Result.invalid, tools.spawn(UnitTypes.dagger, Team.sharded, Float.NaN, 10, 1));
        state.rules.editor = true;
        assertEquals(DevTestTools.Result.unavailable, tools.spawn(UnitTypes.dagger, Team.sharded, 10, 10, 1));
        assertEquals(DevTestTools.Result.unavailable, tools.clear());
        assertEquals(DevTestTools.Result.unavailable, tools.nextWave());
        assertEquals(DevTestTools.Result.unavailable, tools.setUnitCapDisabled(true));
        state.rules.editor = false;
        var active = net.getClass().getDeclaredField("active");
        active.setAccessible(true);
        active.setBoolean(net, true);
        try{
            assertEquals(DevTestTools.Result.unavailable, tools.spawn(UnitTypes.dagger, Team.sharded, 10, 10, 1));
            assertEquals(DevTestTools.Result.unavailable, tools.clear());
            assertEquals(DevTestTools.Result.unavailable, tools.nextWave());
            assertEquals(DevTestTools.Result.unavailable, tools.setUnitCapDisabled(true));
        }finally{
            active.setBoolean(net, false);
        }
    }

    @Test
    void unitCapChangesOnlyAfterExplicitAction(){
        DevTestTools tools = new DevTestTools();
        state.rules.disableUnitCap = false;
        assertEquals(DevTestTools.Result.ok, tools.setUnitCapDisabled(true));
        assertTrue(state.rules.disableUnitCap);
        assertEquals(DevTestTools.Result.ok, tools.setUnitCapDisabled(false));
        assertFalse(state.rules.disableUnitCap);
    }

    @Test
    void worldLoadDiscardsTrackingWithoutDeletingOldOrNewUnits(){
        DevTestTools tools = new DevTestTools();
        assertEquals(DevTestTools.Result.ok, tools.spawn(UnitTypes.dagger, Team.sharded, 100, 100, 2));
        assertEquals(2, tools.trackedCount());
        world.loadMap(ApplicationTests.testMap);
        assertEquals(0, tools.trackedCount());
        state.set(State.playing);
        Unit existing = UnitTypes.dagger.spawn(Team.sharded, 100, 100);
        assertEquals(DevTestTools.Result.ok, tools.clear());
        assertTrue(existing.isAdded());
    }
}
