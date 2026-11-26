import mindustry.game.caves.CavePlayerTransfers;
import mindustry.game.caves.CavePlayerTransfers.Status;
import mindustry.game.caves.CavePlayerTransfers.World;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class CavePlayerTransfersTests{
    @Test void playersTravelIndependentlyAndOnlyOnce(){
        var registry = new CavePlayerTransfers();
        registry.connect("one", World.surface);
        registry.connect("two", World.surface);
        var ticket = registry.begin("one", World.cave);
        assertEquals(World.surface, registry.world("one"));
        assertEquals(World.surface, registry.world("two"));
        assertThrows(IllegalStateException.class, () -> registry.begin("one", World.cave));
        assertEquals(Status.applied, registry.commit(ticket));
        assertEquals(Status.stale, registry.commit(ticket));
        assertEquals(Status.stale, registry.abort(ticket));
        assertEquals(World.cave, registry.world("one"));
        assertEquals(World.surface, registry.world("two"));
        assertEquals(Status.applied, registry.commit(registry.begin("one", World.surface)));
        assertEquals(World.surface, registry.world("one"));
    }

    @Test void abortDisconnectAndReconnectInvalidateOldTickets(){
        var registry = new CavePlayerTransfers();
        registry.connect("one", World.surface);
        var aborted = registry.begin("one", World.cave);
        assertEquals(Status.applied, registry.abort(aborted));
        assertEquals(World.surface, registry.world("one"));
        var oldConnection = registry.begin("one", World.cave);
        assertTrue(registry.disconnect("one"));
        assertFalse(registry.disconnect("one"));
        registry.connect("one", World.surface);
        var current = registry.begin("one", World.cave);
        assertEquals(Status.stale, registry.commit(oldConnection));
        assertEquals(Status.stale, registry.abort(aborted));
        assertEquals(Status.applied, registry.commit(current));
        assertEquals(World.cave, registry.world("one"));
    }

    @Test void foreignAndInvalidRequestsCannotChangeOwnership(){
        var first = new CavePlayerTransfers();
        var second = new CavePlayerTransfers();
        assertThrows(IllegalArgumentException.class, () -> first.connect("", World.surface));
        assertThrows(IllegalArgumentException.class, () -> first.connect("one", null));
        assertThrows(IllegalStateException.class, () -> first.begin("one", World.cave));
        first.connect("one", World.surface);
        second.connect("one", World.surface);
        assertThrows(IllegalStateException.class, () -> first.connect("one", World.cave));
        assertThrows(IllegalArgumentException.class, () -> first.begin("one", World.surface));
        var ticket = first.begin("one", World.cave);
        assertEquals(Status.stale, second.commit(ticket));
        assertEquals(Status.stale, first.commit(null));
        assertEquals(World.surface, second.world("one"));
        assertEquals(Status.applied, first.commit(ticket));
    }

    @Test void competingCompletionsCannotBothSucceed() throws Exception{
        var registry = new CavePlayerTransfers();
        registry.connect("one", World.surface);
        var ticket = registry.begin("one", World.cave);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try{
            var commit = pool.submit(() -> { start.await(); return registry.commit(ticket); });
            var abort = pool.submit(() -> { start.await(); return registry.abort(ticket); });
            start.countDown();
            Status a = commit.get(5, TimeUnit.SECONDS), b = abort.get(5, TimeUnit.SECONDS);
            assertTrue(a == Status.applied && b == Status.stale || a == Status.stale && b == Status.applied);
            assertEquals(a == Status.applied ? World.cave : World.surface, registry.world("one"));
        }finally{
            pool.shutdownNow();
        }
    }
}
