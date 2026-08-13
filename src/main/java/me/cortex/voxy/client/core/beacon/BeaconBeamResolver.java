package me.cortex.voxy.client.core.beacon;

import java.util.ArrayList;
import java.util.List;

/** Pure beacon validation and colour-segment reconstruction. */
public final class BeaconBeamResolver {
    public static final BlockSample AIR = new BlockSample(0, false, null, false);

    private BeaconBeamResolver() {}

    @FunctionalInterface
    public interface BlockAccess {
        /** Returns null when the exact Voxy section is absent. */
        BlockSample get(int x, int y, int z);
    }

    public record BlockSample(int opacity, boolean bedrock, Integer beamColour, boolean beaconBase) {}
    public record Segment(int colour, int height) {
        public Segment {
            if (height < 1) throw new IllegalArgumentException("Segment height must be positive");
        }
    }
    public record ResolvedBeam(List<Segment> segments, int baseLevels) {
        public ResolvedBeam {
            segments = List.copyOf(segments);
            if (segments.isEmpty()) throw new IllegalArgumentException("Beam must have a segment");
            if (baseLevels < 1 || baseLevels > 4) throw new IllegalArgumentException("Invalid beacon base level");
        }
    }

    public static ResolvedBeam resolve(BlockAccess blocks, int beaconX, int beaconY, int beaconZ, int maxWorldY) {
        int baseLevels = getBaseLevels(blocks, beaconX, beaconY, beaconZ);
        if (baseLevels == 0) return null;

        var segments = new ArrayList<MutableSegment>();
        MutableSegment current = null;
        for (int y = beaconY; y < maxWorldY; y++) {
            BlockSample sample = blocks.get(beaconX, y, beaconZ);
            if (sample == null) sample = AIR; // Sparse Voxy air sections are intentionally absent.

            if (sample.beamColour() != null) {
                int colour = sample.beamColour();
                if (segments.size() <= 1) {
                    current = new MutableSegment(colour);
                    segments.add(current);
                } else if (current.colour == colour) {
                    current.height++;
                } else {
                    current = new MutableSegment(averageArgb(current.colour, colour));
                    segments.add(current);
                }
            } else if (current != null) {
                if (sample.opacity() >= 15 && !sample.bedrock()) return null;
                current.height++;
            }
        }

        if (segments.isEmpty()) return null;
        return new ResolvedBeam(segments.stream().map(s -> new Segment(s.colour, s.height)).toList(), baseLevels);
    }

    private static int getBaseLevels(BlockAccess blocks, int x, int y, int z) {
        int levels = 0;
        for (int level = 1; level <= 4; level++) {
            int layerY = y - level;
            boolean valid = true;
            for (int bx = x - level; bx <= x + level && valid; bx++) {
                for (int bz = z - level; bz <= z + level; bz++) {
                    BlockSample sample = blocks.get(bx, layerY, bz);
                    if (sample == null || !sample.beaconBase()) {
                        valid = false;
                        break;
                    }
                }
            }
            if (!valid) break;
            levels = level;
        }
        return levels;
    }

    static int averageArgb(int first, int second) {
        int a = (((first >>> 24) & 0xff) + ((second >>> 24) & 0xff)) / 2;
        int r = (((first >>> 16) & 0xff) + ((second >>> 16) & 0xff)) / 2;
        int g = (((first >>> 8) & 0xff) + ((second >>> 8) & 0xff)) / 2;
        int b = ((first & 0xff) + (second & 0xff)) / 2;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static final class MutableSegment {
        private final int colour;
        private int height = 1;
        private MutableSegment(int colour) { this.colour = colour; }
    }
}
