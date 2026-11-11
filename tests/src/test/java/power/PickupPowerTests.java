package power;

import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PickupPowerTests extends PowerTestFixture{
    @Test
    void pickupResetsDirectConsumerPowerState(){
        Building building = createFakeTile(2, 3, createFakeDirectConsumer(1f)).build;
        PowerGraph previous = building.power.graph;
        building.power.status = 0.75f;
        assertTrue(building.power.init);

        building.afterPickedUp();

        assertNotSame(previous, building.power.graph);
        assertFalse(building.power.init);
        assertEquals(0, building.power.links.size);
        assertEquals(0f, building.power.status);
    }

    @Test
    void pickupPreservesBatteryCharge(){
        Building building = createFakeTile(3, 4, createFakeBattery(100f)).build;
        PowerGraph previous = building.power.graph;
        building.power.status = 0.75f;
        assertTrue(building.power.init);

        building.afterPickedUp();

        assertNotSame(previous, building.power.graph);
        assertFalse(building.power.init);
        assertEquals(0.75f, building.power.status);
    }
}
