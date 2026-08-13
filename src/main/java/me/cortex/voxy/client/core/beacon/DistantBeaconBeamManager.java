package me.cortex.voxy.client.core.beacon;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.state.BeaconRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public final class DistantBeaconBeamManager {
    private static final int MAX_RENDERED_BEAMS = 4096;
    private static final long PROGRESS_INTERVAL_NANOS = 10_000_000_000L;
    private static final long SCAN_INTERVAL_NANOS = 50_000_000L;
    private static final int MAX_DIRTY_SECTIONS_PER_PASS = 32;

    private final WorldEngine world;
    private final long worldHash;
    private final Path indexPath;
    private final int beaconBlockId;
    private final int maxWorldY;
    private final Set<Long> candidates = ConcurrentHashMap.newKeySet();
    private final Map<Long, BeaconBeamResolver.ResolvedBeam> activeBeams = new ConcurrentHashMap<>();
    private final Set<Long> dirtySections = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<Long> scanBacklog = new ArrayDeque<>();
    private final Thread worker;
    private final AtomicInteger scannedSections = new AtomicInteger();
    private volatile boolean live = true;
    private volatile boolean scanComplete;
    private volatile long scanCursor = BeaconIndexStore.NO_SCAN_CURSOR;
    private volatile int lastSubmitted;

    public DistantBeaconBeamManager(WorldEngine world, long worldHash, Path worldStoragePath, int maxWorldY) {
        this.world = world;
        this.worldHash = worldHash;
        this.indexPath = worldStoragePath.resolve("beacon-index-v1.bin");
        this.maxWorldY = maxWorldY;
        this.beaconBlockId = world.getMapper().getIdForBlockState(Blocks.BEACON.defaultBlockState());
        this.loadIndex();

        this.worker = new Thread(this::runWorker, "Voxy beacon indexer");
        this.worker.setDaemon(true);
        this.worker.setPriority(Thread.MIN_PRIORITY);
        this.worker.start();
    }

    public void worldEvent(WorldSection section, int updateFlags, int neighborMask) {
        if (section.lvl != 0 || (updateFlags & WorldEngine.UPDATE_TYPE_BLOCK_BIT) == 0) return;
        this.dirtySections.add(section.key);
        LockSupport.unpark(this.worker);
    }

    public void appendRenderStates(LevelRenderState renderState, float partialTick) {
        if (!VoxyConfig.CONFIG.renderBeaconBeams || this.activeBeams.isEmpty()) {
            this.lastSubmitted = 0;
            return;
        }

        var camera = renderState.cameraRenderState.pos;
        double horizon = Math.max(1.0, VoxyConfig.CONFIG.sectionRenderDistance) * 512.0;
        double horizonSquared = horizon * horizon;

        var vanillaPositions = new HashSet<Long>();
        for (BlockEntityRenderState state : renderState.blockEntityRenderStates) {
            if (state.blockEntityType == BlockEntityTypes.BEACON && state.blockPos != null) {
                vanillaPositions.add(state.blockPos.asLong());
            }
        }

        var nearest = new PriorityQueue<VisibleBeam>(Comparator.comparingDouble(VisibleBeam::distanceSquared).reversed());
        for (var entry : this.activeBeams.entrySet()) {
            long position = entry.getKey();
            if (vanillaPositions.contains(position)) continue;
            double dx = BlockPos.getX(position) + 0.5 - camera.x;
            double dz = BlockPos.getZ(position) + 0.5 - camera.z;
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared > horizonSquared) continue;
            var visible = new VisibleBeam(position, entry.getValue(), distanceSquared);
            if (nearest.size() < MAX_RENDERED_BEAMS) {
                nearest.add(visible);
            } else if (distanceSquared < nearest.peek().distanceSquared()) {
                nearest.poll();
                nearest.add(visible);
            }
        }

        int submitted = 0;
        for (VisibleBeam visible : nearest) {
            var state = new BeaconRenderState();
            state.blockPos = BlockPos.of(visible.position());
            state.blockEntityType = BlockEntityTypes.BEACON;
            state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
            state.animationTime = Math.floorMod(renderState.gameTime, 40L) + partialTick;
            double horizontalDistance = Math.sqrt(visible.distanceSquared());
            var player = Minecraft.getInstance().player;
            state.beamRadiusScale = player != null && player.isScoping()
                    ? 1.0f
                    : Math.max(1.0f, (float)(horizontalDistance / 96.0));
            state.sections = visible.beam().segments().stream()
                    .map(segment -> new BeaconRenderState.Section(segment.colour(), segment.height()))
                    .toList();
            renderState.blockEntityRenderStates.add(state);
            submitted++;
        }
        this.lastSubmitted = submitted;
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("Beacon index [complete/sections/candidates/active/submitted]: "
                + this.scanComplete + "/" + this.scannedSections.get() + "/" + this.candidates.size()
                + "/" + this.activeBeams.size() + "/" + this.lastSubmitted);
    }

    public void shutdown() {
        this.live = false;
        this.worker.interrupt();
        LockSupport.unpark(this.worker);
        try {
            this.worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.saveIndex();
    }

    private void loadIndex() {
        if (!Files.isRegularFile(this.indexPath)) return;
        try {
            var data = BeaconIndexStore.load(this.indexPath, this.worldHash);
            this.candidates.addAll(data.candidates());
            for (var stored : data.activeBeams()) this.activeBeams.put(stored.position(), stored.beam());
            this.scanComplete = data.complete();
            this.scanCursor = data.scanCursor();
            Logger.info("Loaded Voxy beacon index with " + this.candidates.size() + " candidates and "
                    + this.activeBeams.size() + " active beams");
        } catch (Exception e) {
            Logger.warn("Discarding invalid Voxy beacon index at " + this.indexPath + ": " + e.getMessage());
            this.candidates.clear();
            this.activeBeams.clear();
            this.scanComplete = false;
            this.scanCursor = BeaconIndexStore.NO_SCAN_CURSOR;
        }
    }

    private void runWorker() {
        try {
            if (!this.scanComplete) this.prepareScanBacklog();
            long nextScan = System.nanoTime();
            long nextProgress = nextScan + PROGRESS_INTERVAL_NANOS;
            while (this.live) {
                boolean changed = false;
                int dirtyCount = 0;
                Long dirty;
                while (this.live && dirtyCount++ < MAX_DIRTY_SECTIONS_PER_PASS
                        && (dirty = this.takeDirtySection()) != null) {
                    this.processDirtySection(dirty);
                    changed = true;
                }

                long now = System.nanoTime();
                if (this.live && VoxyConfig.CONFIG.renderBeaconBeams && !this.scanComplete
                        && now >= nextScan) {
                    Long sectionPosition = this.scanBacklog.pollFirst();
                    if (sectionPosition == null) {
                        this.scanComplete = true;
                        changed = true;
                        Logger.info("Voxy beacon index complete: " + this.scannedSections.get() + " sections, "
                                + this.candidates.size() + " candidates, " + this.activeBeams.size() + " active beams");
                    } else {
                        this.scanSection(sectionPosition);
                        nextScan = now + SCAN_INTERVAL_NANOS;
                    }
                }

                now = System.nanoTime();
                if (now >= nextProgress) {
                    nextProgress = now + PROGRESS_INTERVAL_NANOS;
                    if (!this.scanComplete && VoxyConfig.CONFIG.renderBeaconBeams) {
                        Logger.info("Voxy beacon index progress: " + this.scannedSections.get() + " sections, "
                                + this.scanBacklog.size() + " queued, " + this.candidates.size() + " candidates");
                    }
                    changed = true;
                }
                if (changed) this.saveIndex();
                if (this.live) {
                    long wait = VoxyConfig.CONFIG.renderBeaconBeams && !this.scanComplete
                            ? Math.max(1_000_000L, nextScan - System.nanoTime())
                            : 250_000_000L;
                    LockSupport.parkNanos(Math.min(wait, 250_000_000L));
                }
            }
        } catch (Exception e) {
            if (this.live) Logger.error("Voxy beacon indexer failed", e);
        }
    }

    private void prepareScanBacklog() {
        Logger.info("Preparing throttled Voxy beacon index from stored level-0 sections");
        var storedPositions = new ArrayList<Long>();
        this.world.storage.iteratePositions(0, sectionPosition -> {
            if (!this.live) throw CancelledScan.INSTANCE;
            storedPositions.add(sectionPosition);
        });
        int start = 0;
        if (this.scanCursor != BeaconIndexStore.NO_SCAN_CURSOR) {
            int cursorIndex = storedPositions.indexOf(this.scanCursor);
            if (cursorIndex >= 0) start = cursorIndex + 1;
            else Logger.warn("Voxy beacon scan cursor is no longer present; restarting the throttled scan");
        }
        for (int i = start; i < storedPositions.size(); i++) this.scanBacklog.addLast(storedPositions.get(i));
        if (this.live) Logger.info("Queued " + this.scanBacklog.size()
                + " Voxy sections for beacon indexing at no more than 20 sections/second");
    }

    private void scanSection(long sectionPosition) {
        WorldSection section = this.world.loadSectionSnapshot(sectionPosition);
        if (section != null) {
            try {
                for (long candidate : this.findBeacons(section)) {
                    this.candidates.add(candidate);
                    this.resolveCandidate(candidate);
                }
            } finally {
                section.release();
            }
        }
        this.scannedSections.incrementAndGet();
        this.scanCursor = sectionPosition;
    }

    private @Nullable Long takeDirtySection() {
        var iterator = this.dirtySections.iterator();
        if (!iterator.hasNext()) return null;
        Long position = iterator.next();
        this.dirtySections.remove(position);
        return position;
    }

    private void processDirtySection(long sectionPosition) {
        int sectionX = WorldEngine.getX(sectionPosition);
        int sectionY = WorldEngine.getY(sectionPosition);
        int sectionZ = WorldEngine.getZ(sectionPosition);

        var affected = new HashSet<Long>();
        for (long candidate : this.candidates) {
            if (isAffectedBySection(candidate, sectionX, sectionY, sectionZ)) affected.add(candidate);
        }

        this.candidates.removeIf(position -> isInsideSection(position, sectionX, sectionY, sectionZ));
        this.activeBeams.keySet().removeIf(position -> isInsideSection(position, sectionX, sectionY, sectionZ));

        WorldSection section = this.world.acquireIfExists(sectionPosition);
        if (section != null) {
            try {
                var found = this.findBeacons(section);
                this.candidates.addAll(found);
                affected.addAll(found);
            } finally {
                section.release();
            }
        }

        for (long candidate : affected) {
            if (this.candidates.contains(candidate)) this.resolveCandidate(candidate);
            else this.activeBeams.remove(candidate);
        }
    }

    private List<Long> findBeacons(WorldSection section) {
        var found = new ArrayList<Long>();
        long[] data = section._unsafeGetRawDataArray();
        for (int index = 0; index < data.length; index++) {
            if (Mapper.getBlockId(data[index]) != this.beaconBlockId) continue;
            int localX = index & 31;
            int localZ = (index >>> 5) & 31;
            int localY = (index >>> 10) & 31;
            found.add(BlockPos.asLong((section.x << 5) + localX, (section.y << 5) + localY, (section.z << 5) + localZ));
        }
        return found;
    }

    private void resolveCandidate(long position) {
        int x = BlockPos.getX(position);
        int y = BlockPos.getY(position);
        int z = BlockPos.getZ(position);
        try (var lookup = new WorldBlockAccess(this.world)) {
            var resolved = BeaconBeamResolver.resolve(lookup, x, y, z, this.maxWorldY);
            if (resolved == null) this.activeBeams.remove(position);
            else this.activeBeams.put(position, resolved);
        }
    }

    private void saveIndex() {
        try {
            BeaconIndexStore.save(this.indexPath, this.worldHash, this.scanComplete, this.scanCursor,
                    this.candidates, this.activeBeams);
        } catch (IOException e) {
            Logger.error("Failed to save Voxy beacon index", e);
        }
    }

    private static boolean isInsideSection(long position, int sx, int sy, int sz) {
        return (BlockPos.getX(position) >> 5) == sx
                && (BlockPos.getY(position) >> 5) == sy
                && (BlockPos.getZ(position) >> 5) == sz;
    }

    private static boolean isAffectedBySection(long position, int sx, int sy, int sz) {
        int x = BlockPos.getX(position);
        int y = BlockPos.getY(position);
        int z = BlockPos.getZ(position);
        int minX = sx << 5;
        int minY = sy << 5;
        int minZ = sz << 5;
        int maxX = minX + 31;
        int maxY = minY + 31;
        int maxZ = minZ + 31;

        boolean beamColumn = x >= minX && x <= maxX && z >= minZ && z <= maxZ && maxY >= y;
        boolean baseFootprint = maxX >= x - 4 && minX <= x + 4
                && maxZ >= z - 4 && minZ <= z + 4
                && maxY >= y - 4 && minY <= y - 1;
        return beamColumn || baseFootprint;
    }

    private record VisibleBeam(long position, BeaconBeamResolver.ResolvedBeam beam, double distanceSquared) {}

    private static final class CancelledScan extends RuntimeException {
        private static final CancelledScan INSTANCE = new CancelledScan();
        private CancelledScan() { super(null, null, false, false); }
    }

    private static final class WorldBlockAccess implements BeaconBeamResolver.BlockAccess, AutoCloseable {
        private final WorldEngine world;
        private final Map<Long, WorldSection> sections = new HashMap<>();
        private final Set<Long> missing = new HashSet<>();

        private WorldBlockAccess(WorldEngine world) { this.world = world; }

        @Override
        public BeaconBeamResolver.BlockSample get(int x, int y, int z) {
            long sectionPosition = WorldEngine.getWorldSectionId(0, x >> 5, y >> 5, z >> 5);
            if (this.missing.contains(sectionPosition)) return null;
            WorldSection section = this.sections.get(sectionPosition);
            if (section == null) {
                section = this.world.loadSectionSnapshot(sectionPosition);
                if (section == null) {
                    this.missing.add(sectionPosition);
                    return null;
                }
                this.sections.put(sectionPosition, section);
            }

            int blockId = Mapper.getBlockId(section._unsafeGetRawDataArray()[WorldSection.getIndex(x & 31, y & 31, z & 31)]);
            if (blockId == 0) return BeaconBeamResolver.AIR;
            var state = this.world.getMapper().getBlockStateFromBlockId(blockId);
            Integer colour = null;
            if (state.getBlock() instanceof BeaconBeamBlock beamBlock) {
                colour = beamBlock.getColor().getTextureDiffuseColor();
            }
            return new BeaconBeamResolver.BlockSample(
                    state.getLightDampening(),
                    state.is(Blocks.BEDROCK),
                    colour,
                    state.is(BlockTags.BEACON_BASE_BLOCKS));
        }

        @Override
        public void close() {
            for (WorldSection section : this.sections.values()) section.release();
            this.sections.clear();
        }
    }
}
