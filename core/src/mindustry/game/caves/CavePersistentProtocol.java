package mindustry.game.caves;

/** Versioned internal protocol; not a multiplayer or public server API. */
public final class CavePersistentProtocol{
    public static final int magic = 0x43535631, version = 1;
    public static final int step = 1, checkpoint = 2, quit = 3;
    public static final int maxStepTicks = 60, maxItems = 4096;
    private CavePersistentProtocol(){}
}
