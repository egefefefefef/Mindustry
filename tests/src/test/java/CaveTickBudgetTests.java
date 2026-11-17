import mindustry.game.caves.CaveTickBudget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaveTickBudgetTests{
    @Test void fractionalTicksPauseAndSuccessfulCommit(){
        var budget = new CaveTickBudget();
        budget.advance(0.25, true);
        budget.advance(0.5, false);
        assertEquals(0, budget.snapshot().ticks());
        assertEquals(0.25, budget.snapshot().fraction(), 1e-10);
        budget.advance(65.5, true);
        var ticket = budget.reserve(60);
        assertEquals(60, ticket.ticks());
        assertEquals(65, budget.snapshot().ticks(), "Reserved ticks remain uncommitted");
        budget.advance(2.25, true);
        budget.commit(ticket);
        assertEquals(8, budget.snapshot().ticks());
        assertEquals(0, budget.snapshot().fraction(), 1e-10);
        var rest = budget.reserve(60);
        assertEquals(8, rest.ticks());
        budget.commit(rest);
        assertNull(budget.reserve(60));
    }

    @Test void failedStepAndSavedReservationCanBeRetried(){
        var budget = new CaveTickBudget();
        budget.advance(120.75, true);
        var first = budget.reserve(60);
        assertThrows(IllegalStateException.class, () -> budget.reserve(60));
        var persisted = budget.snapshot();
        budget.release(first);
        assertEquals(persisted, budget.snapshot());
        assertThrows(IllegalArgumentException.class, () -> budget.commit(first));
        var restored = new CaveTickBudget(persisted);
        var retry = restored.reserve(60);
        assertEquals(60, retry.ticks());
        assertThrows(IllegalArgumentException.class, () -> budget.commit(retry));
        restored.commit(retry);
        assertEquals(60, restored.snapshot().ticks());
        assertEquals(0.75, restored.snapshot().fraction());
    }

    @Test void rejectsInvalidDeltasAndOverflowWithoutChangingBudget(){
        var budget = new CaveTickBudget(new CaveTickBudget.Snapshot(Long.MAX_VALUE, 0.25));
        var before = budget.snapshot();
        assertThrows(IllegalArgumentException.class, () -> budget.advance(Double.NaN, true));
        assertThrows(IllegalArgumentException.class, () -> budget.advance(-1, true));
        assertThrows(IllegalArgumentException.class, () -> budget.advance(CaveTickBudget.maxDelta + 1, true));
        assertThrows(IllegalStateException.class, () -> budget.advance(1, true));
        assertEquals(before, budget.snapshot());
        assertThrows(IllegalArgumentException.class, () -> new CaveTickBudget.Snapshot(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CaveTickBudget.Snapshot(0, 1));
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(61));
    }
}
