package mindustry.game.caves;

import java.util.Arrays;

/**
 * Prototype in-memory atomic cave snapshot/inventory commit. Not connected to
 * the live world or save system; callers must still coordinate player actions,
 * process failure, and durable commits of both the surface and cave worlds.
 */
public final class CaveSimulationCoordinator{
    private final int team;
    private final String[] names;
    private final CaveInventoryLedger ledger;
    private byte[] caveSnapshot;

    public CaveSimulationCoordinator(int team, int capacity, String[] names, int[] counts, byte[] caveSnapshot){
        // Reuse the exchange validation, including names, capacity and count bounds.
        new CaveSimulationExchange(CaveSimulationExchange.request, 0, team, capacity, names, counts,
            CaveSimulationExchange.digest(caveSnapshot));
        this.team = team;
        this.names = names.clone();
        this.ledger = new CaveInventoryLedger(capacity, counts);
        this.caveSnapshot = caveSnapshot.clone();
    }

    public static final class Ticket{
        private final CaveSimulationCoordinator owner;
        private final CaveInventoryLedger.Snapshot inventory;
        private final byte[] caveSnapshot;
        private Ticket(CaveSimulationCoordinator owner, CaveInventoryLedger.Snapshot inventory, byte[] caveSnapshot){
            this.owner = owner; this.inventory = inventory; this.caveSnapshot = caveSnapshot;
        }
        public byte[] caveSnapshot(){ return caveSnapshot.clone(); }
        public int[] counts(){ return inventory.counts(); }
        public long revision(){ return inventory.revision(); }
    }

    public synchronized Ticket begin(){
        return new Ticket(this, ledger.snapshot(), caveSnapshot.clone());
    }

    public synchronized CaveSimulationExchange request(Ticket ticket){
        if(ticket == null || ticket.owner != this) throw new IllegalArgumentException("Foreign cave ticket");
        return new CaveSimulationExchange(CaveSimulationExchange.request, ticket.revision(), team, ticket.inventory.capacity(),
            names, ticket.counts(), CaveSimulationExchange.digest(ticket.caveSnapshot));
    }

    public synchronized boolean adjustSurface(int item, int change){ return ledger.adjust(item, change); }
    public synchronized boolean setCapacity(int capacity){ return ledger.setCapacity(capacity); }

    /** Either publish both pieces in memory or leave both pieces unchanged. */
    public synchronized CaveInventoryLedger.CommitResult commit(Ticket ticket, CaveSimulationExchange result, byte[] snapshot){
        if(ticket == null || ticket.owner != this || ticket.revision() != ledger.snapshot().revision()){
            return CaveInventoryLedger.CommitResult.conflict;
        }
        if(result == null || result.kind() != CaveSimulationExchange.result || result.revision() != ticket.revision() ||
            result.team() != team || result.capacity() != ticket.inventory.capacity() || !Arrays.equals(names, result.names())){
            return CaveInventoryLedger.CommitResult.invalid;
        }
        if(snapshot == null || snapshot.length == 0 || snapshot.length > CaveArchive.maxSnapshotBytes){
            return CaveInventoryLedger.CommitResult.invalid;
        }
        // Hash the actual private copy that would be committed, not a mutable caller buffer.
        // Allocate before committing, so allocation failure cannot publish only one side.
        byte[] copy = snapshot.clone();
        if(!Arrays.equals(result.snapshotHash(), CaveSimulationExchange.digest(copy))) return CaveInventoryLedger.CommitResult.invalid;
        var committed = ledger.commit(ticket.inventory, result.counts());
        if(committed == CaveInventoryLedger.CommitResult.applied) caveSnapshot = copy;
        return committed;
    }
}
