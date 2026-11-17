package mindustry.world.blocks.caves;

import mindustry.world.blocks.storage.CoreBlock;

import static mindustry.Vars.*;

/** Internal cave core; its capacity is constrained by the paused surface core. */
public class CaveCore extends CoreBlock{
    public CaveCore(String name){
        super(name);
        // A large base prevents CoreBlock's proximity routine from discarding
        // items before the saved surface capacity is applied below.
        itemCapacity = 1_000_000_000;
        size = 3;
        health = 1100;
        isFirstTier = true;
        buildType = CaveCoreBuild::new;
    }

    public class CaveCoreBuild extends CoreBuild{
        @Override
        public void onProximityUpdate(){
            super.onProximityUpdate();
            storageCapacity = Math.max(1, state.rules.caveStorageCapacity);
        }
    }
}
