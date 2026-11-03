package org.dreeam.leaf.world.antifreecam;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;

public final class EvdSurface {

    private EvdSurface() {}

    public record Result(
        @Nullable BitSet hiddenSections,
        @Nullable BitSet partialSections,
        int @Nullable [] tileLimits,
        long @Nullable [][] partialMasks
    ) {
        public boolean isCellFilled(int blockX, int blockY, int blockZ) {
            if (this.tileLimits == null) {
                return false;
            }
            int localX = blockX & 15;
            int localZ = blockZ & 15;
            int tileIndex = ((localZ >> 2) << 2) | (localX >> 2);
            return blockY < this.tileLimits[tileIndex];
        }
    }

    /**
     * Preserved for backwards compatibility with existing callers.
     */
    public static @Nullable BitSet hiddenSections(LevelChunk chunk) {
        Result res = compute(chunk);
        return res != null ? res.hiddenSections() : null;
    }

    /**
     * Preserved for backwards compatibility with existing callers.
     */
    public static @Nullable BitSet hiddenSections(ServerLevel level, Map<Heightmap.Types, long[]> rawHeightmaps) {
        Result res = compute(level, rawHeightmaps);
        return res != null ? res.hiddenSections() : null;
    }

    /**
     * Computes surface tile limits, fully hidden sections, and partially filled sections with cell masks for a live chunk.
     */
    public static @Nullable Result compute(LevelChunk chunk) {
        if (chunk == null || chunk.getLevel().dimensionType().hasCeiling()) {
            return null;
        }

        int[] surfaceHeights = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                // without leaves: WORLD_SURFACE counts the crown of a big tree as ground and would fill the air and soil below it
                surfaceHeights[(z << 4) | x] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            }
        }

        return compute(chunk.getSectionsCount(), chunk.getMinSectionY(), surfaceHeights);
    }

    /**
     * Computes surface tile limits, fully hidden sections, and partially filled sections with cell masks from raw NBT heightmaps.
     */
    public static @Nullable Result compute(ServerLevel level, Map<Heightmap.Types, long[]> rawHeightmaps) {
        if (level == null || rawHeightmaps == null || level.dimensionType().hasCeiling()) {
            return null;
        }

        // same height source as the live path, WORLD_SURFACE only as a fallback for data without it
        long[] rawData = rawHeightmaps.get(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        if (rawData == null) {
            rawData = rawHeightmaps.get(Heightmap.Types.WORLD_SURFACE);
        }
        if (rawData == null) {
            return null;
        }

        int heightBits = Mth.ceillog2(level.getHeight() + 1);
        SimpleBitStorage storage;
        try {
            storage = new SimpleBitStorage(heightBits, 256, rawData);
        } catch (Throwable ignored) {
            return null;
        }

        int minY = level.getMinY();
        int[] surfaceHeights = new int[256];
        for (int i = 0; i < 256; i++) {
            // storage holds firstAvailableY - minY; ChunkAccess#getHeight (live path) returns firstAvailableY - 1
            surfaceHeights[i] = storage.get(i) + minY - 1;
        }

        return compute(level.getSectionsCount(), level.getMinSectionY(), surfaceHeights);
    }

    /**
     * Pure function: calculates 4x4 tile boundaries (min WORLD_SURFACE of 16 columns - 16 blocks safety buffer)
     * and decides which sections are fully hidden or partially filled.
     */
    public static @Nullable Result compute(int sectionCount, int minSectionY, int[] surfaceHeights) {
        if (surfaceHeights == null || surfaceHeights.length != 256) {
            return null;
        }

        int[] tileLimits = new int[16];
        Arrays.fill(tileLimits, Integer.MAX_VALUE);

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = surfaceHeights[(z << 4) | x];
                int t = ((z >> 2) << 2) | (x >> 2);
                if (h < tileLimits[t]) {
                    tileLimits[t] = h;
                }
            }
        }

        int minTileLimit = Integer.MAX_VALUE;
        int maxTileLimit = Integer.MIN_VALUE;
        for (int t = 0; t < 16; t++) {
            tileLimits[t] -= 16;
            if (tileLimits[t] < minTileLimit) {
                minTileLimit = tileLimits[t];
            }
            if (tileLimits[t] > maxTileLimit) {
                maxTileLimit = tileLimits[t];
            }
        }

        BitSet hiddenSections = new BitSet(sectionCount);
        BitSet partialSections = new BitSet(sectionCount);
        long[][] partialMasks = null;

        for (int i = 0; i < sectionCount; i++) {
            int sectionY = minSectionY + i;
            int bottomBlockY = sectionY << 4;
            int topBlockY = bottomBlockY + 15;

            if (topBlockY < minTileLimit) {
                hiddenSections.set(i);
            } else if (bottomBlockY < maxTileLimit) {
                boolean hasCut = false;
                for (int t = 0; t < 16; t++) {
                    if (tileLimits[t] > bottomBlockY) {
                        hasCut = true;
                        break;
                    }
                }
                if (hasCut) {
                    partialSections.set(i);
                    if (partialMasks == null) {
                        partialMasks = new long[sectionCount][];
                    }
                    long[] mask = new long[64];
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int t = ((z >> 2) << 2) | (x >> 2);
                            int cutoffY = tileLimits[t] - bottomBlockY;
                            if (cutoffY <= 0) {
                                continue;
                            }
                            int maxLocalY = Math.min(16, cutoffY);
                            for (int y = 0; y < maxLocalY; y++) {
                                int index = (y << 8) | (z << 4) | x;
                                mask[index >>> 6] |= (1L << (index & 63));
                            }
                        }
                    }
                    partialMasks[i] = mask;
                }
            }
        }

        BitSet finalHidden = hiddenSections.isEmpty() ? null : hiddenSections;
        BitSet finalPartial = partialSections.isEmpty() ? null : partialSections;

        if (finalHidden == null && finalPartial == null) {
            return null;
        }

        return new Result(finalHidden, finalPartial, tileLimits, partialMasks);
    }
}
