package me.cortex.voxy.client.core.beacon;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class BeaconIndexStore {
    private static final int MAGIC = 0x56425859; // VBXY
    private static final int VERSION = 2;
    public static final long NO_SCAN_CURSOR = Long.MIN_VALUE;
    private static final int MAX_RECORDS = 1_000_000;
    private static final int MAX_SEGMENTS = 512;

    private BeaconIndexStore() {}

    public record StoredBeam(long position, BeaconBeamResolver.ResolvedBeam beam) {}
    public record Data(long worldHash, boolean complete, long scanCursor, List<Long> candidates,
                       List<StoredBeam> activeBeams) {
        public Data {
            candidates = List.copyOf(candidates);
            activeBeams = List.copyOf(activeBeams);
        }
    }

    public static Data load(Path file, long expectedWorldHash) throws IOException {
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid beacon index magic");
            int version = input.readInt();
            if (version < 1 || version > VERSION) throw new IOException("Unsupported beacon index version");
            long worldHash = input.readLong();
            if (worldHash != expectedWorldHash) throw new IOException("Beacon index belongs to another world");
            boolean complete = input.readBoolean();
            long scanCursor = version >= 2 ? input.readLong() : NO_SCAN_CURSOR;

            int candidateCount = checkedCount(input.readInt(), MAX_RECORDS, "candidates");
            var candidates = new ArrayList<Long>(candidateCount);
            for (int i = 0; i < candidateCount; i++) candidates.add(input.readLong());

            int beamCount = checkedCount(input.readInt(), MAX_RECORDS, "beams");
            var beams = new ArrayList<StoredBeam>(beamCount);
            for (int i = 0; i < beamCount; i++) {
                long position = input.readLong();
                int baseLevels = input.readUnsignedByte();
                int segmentCount = checkedCount(input.readUnsignedShort(), MAX_SEGMENTS, "segments");
                var segments = new ArrayList<BeaconBeamResolver.Segment>(segmentCount);
                for (int j = 0; j < segmentCount; j++) {
                    segments.add(new BeaconBeamResolver.Segment(input.readInt(), input.readInt()));
                }
                beams.add(new StoredBeam(position, new BeaconBeamResolver.ResolvedBeam(segments, baseLevels)));
            }
            return new Data(worldHash, complete, scanCursor, candidates, beams);
        }
    }

    public static void save(Path file, long worldHash, boolean complete, long scanCursor,
                            Collection<Long> candidates,
                            Map<Long, BeaconBeamResolver.ResolvedBeam> activeBeams) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(worldHash);
            output.writeBoolean(complete);
            output.writeLong(scanCursor);
            output.writeInt(candidates.size());
            for (long candidate : candidates) output.writeLong(candidate);
            output.writeInt(activeBeams.size());
            for (var entry : activeBeams.entrySet()) {
                output.writeLong(entry.getKey());
                output.writeByte(entry.getValue().baseLevels());
                output.writeShort(entry.getValue().segments().size());
                for (var segment : entry.getValue().segments()) {
                    output.writeInt(segment.colour());
                    output.writeInt(segment.height());
                }
            }
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int checkedCount(int count, int maximum, String type) throws IOException {
        if (count < 0 || count > maximum) throw new IOException("Invalid beacon index " + type + " count: " + count);
        return count;
    }
}
