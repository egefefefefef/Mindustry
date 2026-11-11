<<<<<<<
     public void afterPickedUp(){
         if(power != null){
             //TODO can lead to ghost graphs?
             power.graph = new PowerGraph();
             power.links.clear();
             if(block.consPower != null && !block.consPower.buffered){
                 power.status = 0f;
             }
         }
     }
=======
    public void afterPickedUp(){
        if(power != null){
            // A picked-up block is still physically removed from the world, so its previous graph must be torn down
            // before creating a fresh one. Otherwise stale links/ghost graphs can remain and corrupt power propagation.
            powerGraphRemoved();
            power.graph = new PowerGraph();
            power.links.clear();
            power.init = false;
            if(block.consPower != null && !block.consPower.buffered){
                power.status = 0f;
            }
        }
    }
>>>>>>>