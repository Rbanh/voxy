package me.cortex.voxy.client.core.beacon;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BeaconBeamResolverTest {
    private static final int WHITE = 0xffffffff;
    private static final int RED = 0xffff0000;
    private static final int BLUE = 0xff0000ff;
    private static final BeaconBeamResolver.BlockSample BASE = new BeaconBeamResolver.BlockSample(15, false, null, true);
    private static final BeaconBeamResolver.BlockSample SOLID = new BeaconBeamResolver.BlockSample(15, false, null, false);
    private static final BeaconBeamResolver.BlockSample BEDROCK = new BeaconBeamResolver.BlockSample(15, true, null, false);

    @Test
    void resolvesAllFourBaseLevels() {
        var blocks = new Blocks();
        blocks.put(0, 10, 0, beam(WHITE));
        addBase(blocks, 0, 10, 0, 4);

        var beam = BeaconBeamResolver.resolve(blocks, 0, 10, 0, 40);

        assertNotNull(beam);
        assertEquals(4, beam.baseLevels());
        assertEquals(1, beam.segments().size());
    }

    @Test
    void rejectsMissingOrIncompleteBase() {
        var blocks = new Blocks();
        blocks.put(0, 10, 0, beam(WHITE));
        addBase(blocks, 0, 10, 0, 1);
        blocks.values.remove(key(1, 9, 1));

        assertNull(BeaconBeamResolver.resolve(blocks, 0, 10, 0, 40));
    }

    @Test
    void rejectsOpaqueBlockButAllowsBedrock() {
        var blocked = validBeacon();
        blocked.put(0, 14, 0, SOLID);
        assertNull(BeaconBeamResolver.resolve(blocked, 0, 10, 0, 40));

        var bedrock = validBeacon();
        bedrock.put(0, 14, 0, BEDROCK);
        assertNotNull(BeaconBeamResolver.resolve(bedrock, 0, 10, 0, 40));
    }

    @Test
    void preservesRepeatedGlassAndAveragesTransitionsLikeVanilla() {
        var blocks = validBeacon();
        blocks.put(0, 11, 0, beam(RED));
        blocks.put(0, 12, 0, beam(RED));
        blocks.put(0, 13, 0, beam(BLUE));

        var result = BeaconBeamResolver.resolve(blocks, 0, 10, 0, 20);

        assertNotNull(result);
        assertEquals(3, result.segments().size());
        assertEquals(new BeaconBeamResolver.Segment(WHITE, 1), result.segments().get(0));
        assertEquals(new BeaconBeamResolver.Segment(RED, 2), result.segments().get(1));
        assertEquals(BeaconBeamResolver.averageArgb(RED, BLUE), result.segments().get(2).colour());
    }

    @Test
    void treatsAbsentSparseSectionsAboveBeaconAsAir() {
        var blocks = validBeacon();
        var result = BeaconBeamResolver.resolve(blocks, 0, 10, 0, 100);
        assertNotNull(result);
        assertEquals(90, result.segments().getFirst().height());
    }

    private static Blocks validBeacon() {
        var blocks = new Blocks();
        blocks.put(0, 10, 0, beam(WHITE));
        addBase(blocks, 0, 10, 0, 1);
        return blocks;
    }

    private static void addBase(Blocks blocks, int x, int y, int z, int levels) {
        for (int level = 1; level <= levels; level++) {
            for (int bx = x - level; bx <= x + level; bx++) {
                for (int bz = z - level; bz <= z + level; bz++) blocks.put(bx, y - level, bz, BASE);
            }
        }
    }

    private static BeaconBeamResolver.BlockSample beam(int colour) {
        return new BeaconBeamResolver.BlockSample(0, false, colour, false);
    }

    private static String key(int x, int y, int z) { return x + ":" + y + ":" + z; }

    private static final class Blocks implements BeaconBeamResolver.BlockAccess {
        private final Map<String, BeaconBeamResolver.BlockSample> values = new HashMap<>();
        private void put(int x, int y, int z, BeaconBeamResolver.BlockSample sample) { values.put(key(x, y, z), sample); }
        @Override public BeaconBeamResolver.BlockSample get(int x, int y, int z) { return values.get(key(x, y, z)); }
    }
}
