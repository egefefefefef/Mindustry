    /** Called right after this building is picked up. */
    public void afterPickedUp(){
        if(power != null){
            // A picked-up block is physically removed from the world, so its stale power graph must be torn down
            // before creating a fresh graph. Otherwise ghost links can stay behind and corrupt the power network.
            powerGraphRemoved();
            power.graph = new PowerGraph();
            power.links.clear();
            power.init = false;
            if(block.consPower != null && !block.consPower.buffered){
                power.status = 0f;
            }
        }
    }
