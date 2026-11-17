import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.content.Items;
import mindustry.world.modules.ItemModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;

import static mindustry.Vars.content;
import static org.junit.jupiter.api.Assertions.*;

class CaveItemRevisionTests{
    @BeforeAll static void init(){ ApplicationTests.launchApplication(); }

    @Test void detectsRestoredCountsAndAllMutationPaths(){
        var items = new ItemModule();
        long revision = items.revision();
        items.set(Items.copper, 5);
        assertNotEquals(revision, items.revision());
        revision = items.revision();
        items.set(Items.copper, 5);
        assertEquals(revision, items.revision(), "Unchanged single-item set is not a mutation");
        items.remove(Items.copper, 1);
        items.add(Items.copper, 1);
        assertNotEquals(revision, items.revision());
        assertEquals(5, items.get(Items.copper));
        revision = items.revision();
        assertNotNull(items.take());
        assertNotEquals(revision, items.revision());
        revision = items.revision();
        items.set(new ItemModule());
        assertNotEquals(revision, items.revision());
        revision = items.revision();
        items.clear();
        assertNotEquals(revision, items.revision());
        revision = items.revision();
        items.checkArrayCapacity(content.items().size + 1);
        assertNotEquals(revision, items.revision());
    }

    @Test void deserializationAlsoInvalidatesEarlierObservation() throws Exception{
        var source = new ItemModule();
        source.set(Items.lead, 7);
        var buffer = new ByteArrayOutputStream();
        source.write(new Writes(new DataOutputStream(buffer)));
        var target = new ItemModule();
        long revision = target.revision();
        target.read(new Reads(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()))), false);
        assertNotEquals(revision, target.revision());
        assertEquals(7, target.get(Items.lead));
    }
}
