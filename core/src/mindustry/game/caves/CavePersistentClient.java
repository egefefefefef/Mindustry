package mindustry.game.caves;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Internal prototype IPC client. Not connected to game state or the save system.
 * Calls are sequential and bounded by a timeout. A failed request invalidates
 * the whole process: a caller must never keep using its last known snapshot.
 */
public final class CavePersistentClient implements AutoCloseable{
    private final Process process;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final ExecutorService executor;
    private final long timeoutMillis;
    private final int team, itemCount;
    private final String[] itemNames;
    private int caveCapacity;
    private long nextId;
    private boolean closed;

    private CavePersistentClient(Process process, long timeoutMillis, int team, String[] itemNames){
        this.process = process;
        this.timeoutMillis = timeoutMillis;
        this.team = team;
        this.itemNames = itemNames.clone();
        this.itemCount = itemNames.length;
        input = new DataInputStream(new BufferedInputStream(process.getInputStream()));
        output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Cave-IPC-prototype");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Launch a caller-supplied worker command; check the complete content identity
     * and initial snapshot hash before allowing any simulation request.
     */
    public static CavePersistentClient launch(List<String> command, Path stderrLog, Path snapshot,
        String[] itemNames, int team, long timeoutMillis) throws IOException{
        if(command == null || command.isEmpty() || stderrLog == null || snapshot == null || itemNames == null ||
            itemNames.length == 0 || itemNames.length > CavePersistentProtocol.maxItems || team < 0 || team > 255 || timeoutMillis < 1){
            throw new IllegalArgumentException("Invalid cave client parameters");
        }
        String[] names = itemNames.clone();
        for(String name : names) if(name == null || name.isEmpty() || name.length() > 128) throw new IllegalArgumentException("Invalid item name");
        byte[] hash = CaveSimulationExchange.digest(snapshot);
        Process process = new ProcessBuilder(command).redirectError(stderrLog.toFile()).start();
        var client = new CavePersistentClient(process, timeoutMillis, team, names);
        try{
            client.call(() -> {
                if(client.input.readInt() != CavePersistentProtocol.magic || client.input.readInt() != CavePersistentProtocol.version){
                    throw new IOException("Invalid cave worker protocol");
                }
                client.caveCapacity = client.input.readInt();
                int count = client.input.readInt();
                if(client.caveCapacity < 1 || count != names.length) throw new IOException("Cave worker content count or capacity mismatch");
                for(String name : names) if(!name.equals(client.input.readUTF())) throw new IOException("Cave worker content order mismatch");
                byte[] actual = new byte[32]; client.input.readFully(actual);
                if(!Arrays.equals(hash, actual)) throw new IOException("Cave worker snapshot mismatch");
                return null;
            });
            return client;
        }catch(IOException | RuntimeException failure){
            client.abort();
            throw failure;
        }
    }

    public int caveCapacity(){ return caveCapacity; }

    boolean matchesIdentity(int team, String[] names){
        return this.team == team && Arrays.equals(itemNames, names);
    }

    /** Counts after one sequential real-world step. The input array is never mutated. */
    public synchronized int[] step(int ticks, int capacity, int[] counts) throws IOException{
        if(ticks < 1 || ticks > CavePersistentProtocol.maxStepTicks) throw new IllegalArgumentException("Invalid cave step size");
        return exchange(CavePersistentProtocol.step, ticks, capacity, counts).counts();
    }

    /** A snapshot and its core inventory from the same frozen worker boundary. */
    public synchronized Checkpoint checkpoint(int capacity, int[] counts) throws IOException{
        return exchange(CavePersistentProtocol.checkpoint, 0, capacity, counts);
    }

    public static final class Checkpoint{
        private final int[] counts;
        private final byte[] snapshot;
        private Checkpoint(int[] counts, byte[] snapshot){ this.counts = counts; this.snapshot = snapshot; }
        public int[] counts(){ return counts.clone(); }
        public byte[] snapshot(){ return snapshot == null ? null : snapshot.clone(); }
    }

    private Checkpoint exchange(int command, int ticks, int capacity, int[] source) throws IOException{
        if(closed) throw new IOException("Cave worker is closed");
        if(capacity < 1 || capacity > caveCapacity || source == null || source.length != itemCount){
            throw new IllegalArgumentException("Invalid shared cave inventory");
        }
        int[] counts = source.clone();
        validateCounts(counts, capacity);
        if(nextId == Long.MAX_VALUE) throw new IOException("Cave request sequence exhausted");
        long id = nextId++;
        return call(() -> {
            output.writeByte(command); output.writeLong(id); output.writeInt(team); output.writeInt(capacity); output.writeInt(itemCount);
            if(command == CavePersistentProtocol.step) output.writeInt(ticks);
            for(int count : counts) output.writeInt(count);
            output.flush();
            if(input.readUnsignedByte() != command || input.readLong() != id || input.readInt() != itemCount){
                throw new IOException("Cave response does not match request");
            }
            int[] result = new int[itemCount];
            for(int i = 0; i < itemCount; i++) result[i] = input.readInt();
            try{ validateCounts(result, capacity); }catch(IllegalArgumentException invalid){ throw new IOException("Invalid cave response inventory", invalid); }
            byte[] snapshot = null;
            if(command == CavePersistentProtocol.checkpoint){
                int length = input.readInt();
                if(length <= 0 || length > CaveArchive.maxSnapshotBytes) throw new IOException("Invalid cave checkpoint size");
                snapshot = new byte[length]; input.readFully(snapshot);
            }
            return new Checkpoint(result, snapshot);
        });
    }

    private static void validateCounts(int[] counts, int capacity){
        long total = 0;
        for(int count : counts){
            if(count < 0 || count > capacity) throw new IllegalArgumentException("Invalid cave item amount");
            total += count;
        }
        if(total > Integer.MAX_VALUE) throw new IllegalArgumentException("Cave inventory overflow");
    }

    private <T> T call(Callable<T> task) throws IOException{
        if(closed || !process.isAlive()){
            abort();
            throw new IOException("Cave worker is not running");
        }
        Future<T> future = executor.submit(task);
        try{
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            future.cancel(true); abort();
            throw new IOException("Interrupted while waiting for cave worker", e);
        }catch(TimeoutException e){
            future.cancel(true); abort();
            throw new IOException("Cave worker timed out; session discarded", e);
        }catch(ExecutionException e){
            abort();
            throw new IOException("Cave worker failed; session discarded", e.getCause());
        }
    }

    /** Immediately discard an uncommittable session without another protocol round trip. */
    synchronized void discard(){
        if(!closed) abort();
    }

    /** Close without publishing any further worker state. */
    private void abort(){
        closed = true;
        // Do not synchronously close buffered pipes here: another thread may be
        // blocked inside read/flush while holding their monitor. Killing the
        // process closes the OS pipes without extending the caller's timeout.
        process.destroyForcibly();
        executor.shutdownNow();
    }

    @Override public synchronized void close(){
        if(closed) return;
        try{
            call(() -> {
                output.writeByte(CavePersistentProtocol.quit); output.flush();
                if(input.readUnsignedByte() != CavePersistentProtocol.quit) throw new IOException("Invalid cave shutdown response");
                return null;
            });
        }catch(IOException ignored){
            // No state is committed by close; the session is discarded.
        }finally{
            abort();
        }
    }
}
