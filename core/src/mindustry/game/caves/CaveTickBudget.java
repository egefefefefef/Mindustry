package mindustry.game.caves;

/**
 * Experimental game-thread tick accounting; not connected to saves or gameplay.
 * Uncommitted work remains in the budget until its matching reservation is
 * committed. A saved snapshot includes a pending reservation so it can be
 * retried after a restart. This does not itself schedule or advance a world.
 */
public final class CaveTickBudget{
    /** Reject implausible input rather than silently dropping time on overflow. */
    public static final double maxDelta = 60d * 60d * 60d;
    private final Thread owner = Thread.currentThread();
    private long ticks;
    private double fraction;
    private Ticket outstanding;

    public record Snapshot(long ticks, double fraction){
        public Snapshot{
            if(ticks < 0 || !Double.isFinite(fraction) || fraction < 0 || fraction >= 1){
                throw new IllegalArgumentException("Invalid cave tick budget");
            }
        }
    }

    public static final class Ticket{
        private final CaveTickBudget owner;
        private final int ticks;
        private Ticket(CaveTickBudget owner, int ticks){ this.owner = owner; this.ticks = ticks; }
        public int ticks(){ return ticks; }
    }

    public CaveTickBudget(){ this(new Snapshot(0, 0)); }

    public CaveTickBudget(Snapshot snapshot){
        if(snapshot == null) throw new IllegalArgumentException("Missing cave tick budget");
        ticks = snapshot.ticks();
        fraction = snapshot.fraction();
    }

    /** Add elapsed game ticks only while playing; input is bounded and finite. */
    public void advance(double delta, boolean playing){
        checkThread();
        if(!Double.isFinite(delta) || delta < 0 || delta > maxDelta) throw new IllegalArgumentException("Invalid cave tick delta");
        if(!playing) return;
        double accumulated = fraction + delta;
        long whole = (long)Math.floor(accumulated);
        if(whole > Long.MAX_VALUE - ticks) throw new IllegalStateException("Cave tick budget overflow");
        ticks += whole;
        fraction = accumulated - whole;
    }

    /** Reserve at most the protocol limit; reservation does not remove any ticks. */
    public Ticket reserve(int maximum){
        checkThread();
        if(maximum < 1 || maximum > CavePersistentProtocol.maxStepTicks) throw new IllegalArgumentException("Invalid step limit");
        if(outstanding != null) throw new IllegalStateException("A cave step is already reserved");
        if(ticks == 0) return null;
        return outstanding = new Ticket(this, (int)Math.min(ticks, maximum));
    }

    /** Subtract ticks only after both cave snapshot and inventory have been published. */
    public void commit(Ticket ticket){
        checkTicket(ticket);
        ticks -= ticket.ticks;
        outstanding = null;
    }

    /** A failed, cancelled or conflicting step leaves all ticks available for retry. */
    public void release(Ticket ticket){
        checkTicket(ticket);
        outstanding = null;
    }

    public Snapshot snapshot(){
        checkThread();
        return new Snapshot(ticks, fraction);
    }

    private void checkTicket(Ticket ticket){
        checkThread();
        if(ticket == null || ticket.owner != this || ticket != outstanding) throw new IllegalArgumentException("Stale or foreign cave tick reservation");
    }

    private void checkThread(){
        if(Thread.currentThread() != owner) throw new IllegalStateException("Cave tick budget must run on its owning game thread");
    }
}
