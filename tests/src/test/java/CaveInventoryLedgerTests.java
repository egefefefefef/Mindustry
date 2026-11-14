import mindustry.game.caves.CaveInventoryLedger;
import mindustry.game.caves.CaveInventoryLedger.CommitResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class CaveInventoryLedgerTests{
    @Test void commitIsAtomicAndSnapshotsAreIsolated(){
        int[] initial = {10, 3};
        var ledger = new CaveInventoryLedger(20, initial);
        initial[0] = 0;
        var before = ledger.snapshot();
        var copy = before.counts();
        copy[0] = 0;
        assertArrayEquals(new int[]{10, 3}, ledger.snapshot().counts());
        int[] produced = {12, 2};
        assertEquals(CommitResult.applied, ledger.commit(before, produced));
        produced[0] = 0;
        assertArrayEquals(new int[]{12, 2}, ledger.snapshot().counts());
        assertEquals(CommitResult.conflict, ledger.commit(before, new int[]{12, 2}));
    }

    @Test void surfaceChangesRejectStaleWorkerResultEvenAfterAba(){
        var ledger = new CaveInventoryLedger(10, new int[]{5});
        var worker = ledger.snapshot();
        assertTrue(ledger.adjust(0, -1));
        assertTrue(ledger.adjust(0, 1));
        assertEquals(CommitResult.conflict, ledger.commit(worker, new int[]{7}));
        assertArrayEquals(new int[]{5}, ledger.snapshot().counts());
        var retry = ledger.snapshot();
        assertEquals(CommitResult.applied, ledger.commit(retry, new int[]{7}));
        assertArrayEquals(new int[]{7}, ledger.snapshot().counts());
    }

    @Test void rejectsInvalidUpdatesWithoutPartiallyChangingAnyItem(){
        var ledger = new CaveInventoryLedger(10, new int[]{5, 4});
        var base = ledger.snapshot();
        assertEquals(CommitResult.invalid, ledger.commit(base, new int[]{6, 11}));
        assertEquals(CommitResult.invalid, ledger.commit(base, new int[]{6, -1}));
        assertEquals(CommitResult.invalid, ledger.commit(base, new int[]{6}));
        assertEquals(CommitResult.invalid, ledger.commit(base, null));
        assertFalse(ledger.adjust(0, -6));
        assertFalse(ledger.adjust(1, Integer.MAX_VALUE));
        assertFalse(ledger.setCapacity(4));
        assertArrayEquals(new int[]{5, 4}, ledger.snapshot().counts());
        assertEquals(base.revision(), ledger.snapshot().revision());
        assertEquals(CommitResult.applied, ledger.commit(base, new int[]{6, 3}));
    }

    @Test void capacityChangesAndForeignSnapshotsInvalidatePendingSteps(){
        var ledger = new CaveInventoryLedger(10, new int[]{4});
        var pending = ledger.snapshot();
        assertTrue(ledger.setCapacity(6));
        assertEquals(CommitResult.conflict, ledger.commit(pending, new int[]{5}));
        var foreign = new CaveInventoryLedger(6, new int[]{4});
        assertEquals(CommitResult.conflict, ledger.commit(foreign.snapshot(), new int[]{5}));
        assertEquals(CommitResult.invalid, ledger.commit(ledger.snapshot(), new int[]{7}));
        assertArrayEquals(new int[]{4}, ledger.snapshot().counts());
    }

    @Test void concurrentWorkersCannotCommitTheSameRevisionTwice() throws Exception{
        var ledger = new CaveInventoryLedger(10, new int[]{1});
        var base = ledger.snapshot();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try{
            var first = pool.submit(() -> { start.await(); return ledger.commit(base, new int[]{2}); });
            var second = pool.submit(() -> { start.await(); return ledger.commit(base, new int[]{3}); });
            start.countDown();
            CommitResult a = first.get(5, TimeUnit.SECONDS), b = second.get(5, TimeUnit.SECONDS);
            assertTrue(a == CommitResult.applied && b == CommitResult.conflict ||
                b == CommitResult.applied && a == CommitResult.conflict);
            int value = ledger.snapshot().counts()[0];
            assertTrue(value == 2 || value == 3);
        }finally{
            pool.shutdownNow();
        }
    }

    @Test void preventsOverflowOfItemModuleTotal(){
        var ledger = new CaveInventoryLedger(Integer.MAX_VALUE, new int[]{Integer.MAX_VALUE, 0});
        assertFalse(ledger.adjust(1, 1));
        assertEquals(CommitResult.invalid, ledger.commit(ledger.snapshot(), new int[]{Integer.MAX_VALUE, 1}));
        assertThrows(IllegalArgumentException.class, () -> new CaveInventoryLedger(0, new int[]{0}));
        assertThrows(IllegalArgumentException.class, () -> new CaveInventoryLedger(10, new int[]{-1}));
    }
}
