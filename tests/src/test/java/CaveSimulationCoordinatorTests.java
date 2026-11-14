import mindustry.game.caves.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaveSimulationCoordinatorTests{
    private static CaveSimulationExchange result(CaveSimulationCoordinator.Ticket ticket, int amount, byte[] snapshot){
        return new CaveSimulationExchange(CaveSimulationExchange.result, ticket.revision(), 1, 10,
            new String[]{"copper"}, new int[]{amount}, CaveSimulationExchange.digest(snapshot));
    }

    @Test void publishesSnapshotAndInventoryAsOneState(){
        var state = new CaveSimulationCoordinator(1, 10, new String[]{"copper"}, new int[]{5}, new byte[]{1, 2});
        var base = state.begin();
        var request = state.request(base);
        assertArrayEquals(CaveSimulationExchange.digest(base.caveSnapshot()), request.snapshotHash());
        byte[] proposed = {3, 4};
        assertEquals(CaveInventoryLedger.CommitResult.applied, state.commit(base, result(base, 7, proposed), proposed));
        proposed[0] = 99;
        var after = state.begin();
        assertArrayEquals(new byte[]{3, 4}, after.caveSnapshot());
        assertArrayEquals(new int[]{7}, after.counts());
        assertEquals(CaveInventoryLedger.CommitResult.conflict, state.commit(base, result(base, 7, new byte[]{3, 4}), new byte[]{3, 4}));
    }

    @Test void conflictsAndCorruptedResultsKeepThePreviousPair(){
        var state = new CaveSimulationCoordinator(1, 10, new String[]{"copper"}, new int[]{5}, new byte[]{1});
        var base = state.begin();
        assertEquals(CaveInventoryLedger.CommitResult.invalid, state.commit(base, result(base, 6, new byte[]{2}), new byte[]{3}));
        assertArrayEquals(new byte[]{1}, state.begin().caveSnapshot());
        assertArrayEquals(new int[]{5}, state.begin().counts());
        assertTrue(state.adjustSurface(0, -2));
        assertEquals(CaveInventoryLedger.CommitResult.conflict, state.commit(base, result(base, 6, new byte[]{2}), new byte[]{2}));
        var after = state.begin();
        assertArrayEquals(new byte[]{1}, after.caveSnapshot());
        assertArrayEquals(new int[]{3}, after.counts());
    }

    @Test void rejectsWrongTeamCapacityItemOrderAndForeignTickets(){
        var state = new CaveSimulationCoordinator(1, 10, new String[]{"copper"}, new int[]{5}, new byte[]{1});
        var base = state.begin(); byte[] snapshot = {2};
        var wrongTeam = new CaveSimulationExchange(CaveSimulationExchange.result, base.revision(), 2, 10,
            new String[]{"copper"}, new int[]{6}, CaveSimulationExchange.digest(snapshot));
        var wrongItem = new CaveSimulationExchange(CaveSimulationExchange.result, base.revision(), 1, 10,
            new String[]{"lead"}, new int[]{6}, CaveSimulationExchange.digest(snapshot));
        assertEquals(CaveInventoryLedger.CommitResult.invalid, state.commit(base, wrongTeam, snapshot));
        assertEquals(CaveInventoryLedger.CommitResult.invalid, state.commit(base, wrongItem, snapshot));
        var foreign = new CaveSimulationCoordinator(1, 10, new String[]{"copper"}, new int[]{5}, new byte[]{1});
        assertEquals(CaveInventoryLedger.CommitResult.conflict, state.commit(foreign.begin(), result(base, 6, snapshot), snapshot));
        assertTrue(state.setCapacity(6));
        assertEquals(CaveInventoryLedger.CommitResult.conflict, state.commit(base, result(base, 6, snapshot), snapshot));
    }
}
