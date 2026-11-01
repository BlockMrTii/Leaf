package org.dreeam.leaf.world.antifreecam;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.Map;

public final class EvdSurface {

    private EvdSurface() {}

    /**
     * Determines sections whose top block level is strictly below the lowest WORLD_SURFACE heightmap
     * value across all 256 columns of the chunk, minus an additional one-section safety buffer.
     * <p>
     * Rationale for the safety buffer:
     * Leaving the section directly beneath the lowest surface point intact prevents visual artifacts
     * (e.g. cut-offs, solid stone ceiling showing through valleys, overhangs, ravines or shallow caves
     * that reach just below the surface) when distant chunks are rendered from elevated angles, while
     * keeping deep underground caves and player bases fully concealed.
     *
     * @param chunk the live level chunk
     * @return BitSet of section indices (0..sectionCount - 1) to hide, or null if none
     */
    public static @Nullable BitSet hiddenSections(LevelChunk chunk) {
        // the highest non-air block of a dimension with a ceiling is its roof, the whole world would count as underground
        if (chunk == null || chunk.getLevel().dimensionType().hasCeiling()) {
            return null;
        }

        int minSurfaceHeight = Integer.MAX_VALUE;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                if (h < minSurfaceHeight) {
                    minSurfaceHeight = h;
                }
            }
        }

        return computeHiddenSections(chunk.getSectionsCount(), chunk.getMinSectionY(), minSurfaceHeight);
    }

    /**
     * Determines sections to hide using raw, parsed heightmap data (e.g. from SerializableChunkData.heightmaps()).
     * <p>
     * If WORLD_SURFACE data is missing or corrupted, returns null conservatively without guessing
     * or modifying sections.
     *
     * @param level the server level
     * @param rawHeightmaps raw heightmap arrays mapped by heightmap type
     * @return BitSet of section indices (0..sectionCount - 1) to hide, or null if none
     */
    public static @Nullable BitSet hiddenSections(ServerLevel level, Map<Heightmap.Types, long[]> rawHeightmaps) {
        // see above, a ceiling (nether roof) would hide the whole dimension
        if (level == null || rawHeightmaps == null || level.dimensionType().hasCeiling()) {
            return null;
        }

        long[] rawData = rawHeightmaps.get(Heightmap.Types.WORLD_SURFACE);
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
        int minSurfaceHeight = Integer.MAX_VALUE;
        for (int i = 0; i < 256; i++) {
            // Heightmap values in storage represent (firstAvailableY - minY)
            // The highest non-air block Y is therefore (value + minY - 1)
            int surfaceY = storage.get(i) + minY - 1;
            if (surfaceY < minSurfaceHeight) {
                minSurfaceHeight = surfaceY;
            }
        }

        return computeHiddenSections(level.getSectionsCount(), level.getMinSectionY(), minSurfaceHeight);
    }

    private static @Nullable BitSet computeHiddenSections(int sectionCount, int minSectionY, int minSurfaceHeight) {
        BitSet bitSet = new BitSet(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            int sectionY = minSectionY + i;
            int topBlockY = (sectionY << 4) + 15;
            // The top block of the section must be below the lowest surface height,
            // with a 1-section (16-block) safety margin buffer below the lowest surface section.
            if (topBlockY + 16 < minSurfaceHeight) {
                bitSet.set(i);
            }
        }
        return bitSet.isEmpty() ? null : bitSet;
    }
}
