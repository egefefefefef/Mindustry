package mindustry.game.caves;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory, server-owned prototype for independent player transfers between two worlds.
 *
 * This is not connected to networking or gameplay. Player identities must be authenticated
 * by the caller; tickets are private server-side capabilities, not network credentials.
 * A caller must quiesce the source, prepare the destination, and coordinate entity and
 * connection handoff before committing. Crash recovery, persistence, timeout handling,
 * actual world isolation, and shared-inventory transactions are outside this class.
 */
public final class CavePlayerTransfers{
    public enum World { surface, cave }
    public enum Status { applied, stale }

    private static final class PlayerState{
        World world;
        Ticket pending;
        PlayerState(World world){ this.world = world; }
    }

    /** Opaque capability for one transfer, created only by this registry. */
    public static final class Ticket{
        private final CavePlayerTransfers owner;
        private final String player;
        private final World source, destination;

        private Ticket(CavePlayerTransfers owner, String player, World source, World destination){
            this.owner = owner;
            this.player = player;
            this.source = source;
            this.destination = destination;
        }

        public String player(){ return player; }
        public World source(){ return source; }
        public World destination(){ return destination; }
    }

    private final Map<String, PlayerState> players = new HashMap<>();

    /** Register an authenticated, currently connected player in its authoritative world. */
    public synchronized void connect(String player, World world){
        validatePlayer(player);
        if(world == null) throw new IllegalArgumentException("Missing world");
        if(players.containsKey(player)) throw new IllegalStateException("Player is already connected");
        players.put(player, new PlayerState(world));
    }

    /** Remove a connection and invalidate its in-flight handoff. */
    public synchronized boolean disconnect(String player){
        validatePlayer(player);
        return players.remove(player) != null;
    }

    /** Null means the player has no registered connection. */
    public synchronized World world(String player){
        validatePlayer(player);
        PlayerState state = players.get(player);
        return state == null ? null : state.world;
    }

    /** A player can have at most one in-flight handoff; others remain independent. */
    public synchronized Ticket begin(String player, World destination){
        validatePlayer(player);
        if(destination == null) throw new IllegalArgumentException("Missing destination");
        PlayerState state = players.get(player);
        if(state == null) throw new IllegalStateException("Player is not connected");
        if(state.pending != null) throw new IllegalStateException("Player is already transferring");
        if(state.world == destination) throw new IllegalArgumentException("Player is already in destination world");
        return state.pending = new Ticket(this, player, state.world, destination);
    }

    /** Publish ownership only after the external handoff has been safely prepared. */
    public synchronized Status commit(Ticket ticket){
        PlayerState state = pending(ticket);
        if(state == null) return Status.stale;
        state.world = ticket.destination;
        state.pending = null;
        return Status.applied;
    }

    /** Abort leaves ownership in the source world. */
    public synchronized Status abort(Ticket ticket){
        PlayerState state = pending(ticket);
        if(state == null) return Status.stale;
        state.pending = null;
        return Status.applied;
    }

    private PlayerState pending(Ticket ticket){
        if(ticket == null || ticket.owner != this) return null;
        PlayerState state = players.get(ticket.player);
        return state != null && state.pending == ticket && state.world == ticket.source ? state : null;
    }

    private static void validatePlayer(String player){
        if(player == null || player.isEmpty() || player.length() > 128) throw new IllegalArgumentException("Invalid player identity");
    }
}
