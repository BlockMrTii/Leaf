package org.dreeam.leaf.world.antifreecam;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class BiomeMasking {

    private BiomeMasking() {}

    /**
     * Sets the biome of every biome cell (4x4x4 blocks, container coordinates 0..3) of the section that holds at least one
     * culled block and no other visible block: all its remaining blocks must be opaque, so nothing in the cell shows
     * a biome (no air a player can see, no water, leaves or grass). A cell that is only partly covered by the mask, like
     * the rock around a sealed room, is still replaced. Cells with a block the client can see keep their biome.
     * <p>
     * Call it after the culled blocks were replaced by the filler, on a private copy of the section.
     * Mask index: y << 8 | z << 4 | x, 64 longs.
     *
     * @return true if at least one cell changed
     */
    public static boolean apply(LevelChunkSection section, long[] mask, Holder<Biome> uniform) {
        long combined = 0L;
        for (int i = 0; i < 64; i++) {
            combined |= mask[i];
        }
        if (combined == 0L) {
            return false;
        }

        boolean changed = false;
        for (int qy = 0; qy < 4; qy++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) {
                    // Four x bits in each of four z rows fit in one mask word per y.
                    long cellRows = 0x000F000F000F000FL << (qx << 2);
                    boolean any = false, all = true;
                    for (int y = qy << 2; y < (qy << 2) + 4; y++) {
                        long bits = mask[(y << 2) | qz] & cellRows;
                        any |= bits != 0L;
                        all &= bits == cellRows;
                    }
                    if (!any || section.getNoiseBiome(qx, qy, qz) == uniform) {
                        continue;
                    }
                    if (all || onlyOpaqueBesidesCulled(section, mask, qx, qy, qz)) {
                        section.setNoiseBiome(qx, qy, qz, uniform);
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private static boolean onlyOpaqueBesidesCulled(LevelChunkSection section, long[] mask, int qx, int qy, int qz) {
        for (int y = qy << 2; y < (qy << 2) + 4; y++) {
            for (int z = qz << 2; z < (qz << 2) + 4; z++) {
                for (int x = qx << 2; x < (qx << 2) + 4; x++) {
                    int index = y << 8 | z << 4 | x;
                    if ((mask[index >>> 6] & 1L << index) != 0L) {
                        continue;
                    }
                    BlockState state = section.getBlockState(x, y, z);
                    // opaque blocks and closed doors show no biome; the top of a grass block is tinted by it
                    if (!SectionConnectivity.isBarrier(state) || state.is(Blocks.GRASS_BLOCK)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
