package mindustry.game.caves;

/**
 * Prototype optimistic inventory transaction for an isolated cave worker.
 * Not connected to gameplay: callers would still have to roll back the entire
 * simulated cave step when a commit fails, and atomically persist both worlds.
 * Counts are per item, matching Mindustry's per-item core capacity.
 */
public final class CaveInventoryLedger{
    public enum CommitResult { applied, conflict, invalid }

    private int[] counts;
    private int capacity;
    private long revision;

    public CaveInventoryLedger(int capacity, int[] counts){
        if(!valid(capacity, counts)) throw new IllegalArgumentException("Invalid core inventory");
        this.capacity = capacity;
        this.counts = counts.clone();
    }

    /** Immutable snapshot, bound to this ledger and its current revision. */
    public static final class Snapshot{
        private final CaveInventoryLedger owner;
        private final long revision;
        private final int capacity;
        private final int[] counts;

        private Snapshot(CaveInventoryLedger owner, long revision, int capacity, int[] counts){
            this.owner = owner;
            this.revision = revision;
            this.capacity = capacity;
            this.counts = counts;
        }

        public long revision(){ return revision; }
        public int capacity(){ return capacity; }
        public int[] counts(){ return counts.clone(); }
    }

    public synchronized Snapshot snapshot(){
        return new Snapshot(this, revision, capacity, counts.clone());
    }

    /**
     * Replace the complete inventory after a cave step. Validation happens
     * before any write; a stale or foreign snapshot never changes the ledger.
     */
    public synchronized CommitResult commit(Snapshot base, int[] result){
        if(base == null || base.owner != this || base.revision != revision) return CommitResult.conflict;
        if(result == null || result.length != counts.length || !valid(capacity, result)) return CommitResult.invalid;
        int[] copy = result.clone();
        advanceRevision();
        counts = copy;
        return CommitResult.applied;
    }

    /** Apply a surface-side item change without ever exceeding capacity. */
    public synchronized boolean adjust(int item, int change){
        if(item < 0 || item >= counts.length) return false;
        long updated = (long)counts[item] + change;
        if(updated < 0 || updated > capacity) return false;
        long total = total(counts) + change;
        if(total > Integer.MAX_VALUE) return false;
        advanceRevision();
        counts[item] = (int)updated;
        return true;
    }

    /** A decrease is refused rather than silently deleting existing items. */
    public synchronized boolean setCapacity(int capacity){
        if(!valid(capacity, counts)) return false;
        advanceRevision();
        this.capacity = capacity;
        return true;
    }

    private void advanceRevision(){
        if(revision == Long.MAX_VALUE) throw new IllegalStateException("Inventory revision exhausted");
        revision++;
    }

    private static boolean valid(int capacity, int[] counts){
        if(capacity < 1 || counts == null) return false;
        for(int count : counts) if(count < 0 || count > capacity) return false;
        return total(counts) <= Integer.MAX_VALUE;
    }

    private static long total(int[] counts){
        long total = 0;
        for(int count : counts) total += count;
        return total;
    }
}
