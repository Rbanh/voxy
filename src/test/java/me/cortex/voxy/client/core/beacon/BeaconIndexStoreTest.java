package me.cortex.voxy.client.core.beacon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeaconIndexStoreTest {
    @TempDir Path temp;

    @Test
    void roundTripsCompletedIndexWithNegativeCoordinatePosition() throws Exception {
        Path file = temp.resolve("world/beacon-index-v1.bin");
        long position = -8_129_887_123L;
        var beam = new BeaconBeamResolver.ResolvedBeam(List.of(
                new BeaconBeamResolver.Segment(0xffffffff, 4),
                new BeaconBeamResolver.Segment(0xff00ff00, 20)), 3);

        BeaconIndexStore.save(file, 42L, true, List.of(position), Map.of(position, beam));
        var loaded = BeaconIndexStore.load(file, 42L);

        assertTrue(loaded.complete());
        assertEquals(List.of(position), loaded.candidates());
        assertEquals(beam, loaded.activeBeams().getFirst().beam());
    }

    @Test
    void preservesIncompleteCheckpoint() throws Exception {
        Path file = temp.resolve("beacon-index-v1.bin");
        BeaconIndexStore.save(file, 7L, false, List.of(1L, 2L), Map.of());
        assertFalse(BeaconIndexStore.load(file, 7L).complete());
    }

    @Test
    void rejectsWrongWorldAndCorruption() throws Exception {
        Path file = temp.resolve("beacon-index-v1.bin");
        BeaconIndexStore.save(file, 7L, true, List.of(), Map.of());
        assertThrows(IOException.class, () -> BeaconIndexStore.load(file, 8L));

        Files.write(file, new byte[] {1, 2, 3, 4});
        assertThrows(IOException.class, () -> BeaconIndexStore.load(file, 7L));
    }
}
