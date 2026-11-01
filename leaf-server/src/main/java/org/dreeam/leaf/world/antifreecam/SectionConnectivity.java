package org.dreeam.leaf.world.antifreecam;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import java.util.function.Predicate;
import net.minecraft.world.level.block.state.BlockState;

/** Face order is Direction.ordinal(): down, up, north, south, west, east. */
public final class SectionConnectivity {
    public static final short ALL = 0x7fff;
    private static final Predicate<BlockState> TRANSPARENT = state -> !state.isSolidRender();
    private static final Predicate<BlockState> SOLID = BlockState::isSolidRender;
    private static final ThreadLocal<Workspace> WORK = ThreadLocal.withInitial(Workspace::new);
    // solid flag per global block state id, for sections that use the global palette; block states are fixed after bootstrap
    private static volatile byte[] globalSolid;
    private static final class Workspace {
        final byte[] cells = new byte[4096];
        final int[] queue = new int[4096];
        final int[] ids = new int[4096];
        // non-global palettes hold at most 2^8 entries
        byte[] paletteSolid = new byte[256];
    }

    private static byte[] globalSolid() {
        byte[] ret = globalSolid;
        if (ret == null) {
            final int size = Block.BLOCK_STATE_REGISTRY.size();
            ret = new byte[size];
            for (int id = 0; id < size; ++id) {
                final BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
                ret[id] = (byte)(state != null && state.isSolidRender() ? 1 : 0);
            }
            globalSolid = ret;
        }
        return ret;
    }
    private SectionConnectivity() {}

    public static boolean isConnected(final short mask, int a, int b) {
        if (a == b) return true;
        if (a > b) { final int tmp = a; a = b; b = tmp; }
        return (mask & (1 << (a * (11 - a) / 2 + b - a - 1))) != 0;
    }

    public static short compute(final LevelChunkSection section) {
        if (section.hasOnlyAir()) return ALL;
        if (!section.maybeHas(TRANSPARENT)) return 0;
        // no solid block at all (air, water, glass, leaves...): every face reaches every other face
        if (!section.maybeHas(SOLID)) return ALL;
        final Workspace work = WORK.get();
        final byte[] cells = work.cells;
        // resolve the solid flag once per palette entry and read the packed ids directly, instead of a
        // getBlockState() palette lookup for each of the 4096 cells; storage index order is y << 8 | z << 4 | x
        final PalettedContainer.Data<BlockState> data = section.getStates().leaf$getDataAcquire();
        final Palette<BlockState> palette = data.palette();
        final BitStorage storage = data.storage();
        final int[] ids = work.ids;
        storage.unpack(ids);
        final byte[] solid;
        if (palette instanceof GlobalPalette<BlockState>) {
            solid = globalSolid();
        } else {
            final int size = palette.getSize();
            if (work.paletteSolid.length < size) {
                work.paletteSolid = new byte[size];
            }
            solid = work.paletteSolid;
            for (int i = 0; i < size; ++i) {
                solid[i] = (byte)(palette.valueFor(i).isSolidRender() ? 1 : 0);
            }
        }
        for (int i = 0; i < 4096; ++i) {
            cells[i] = solid[ids[i]];
        }
        int result = 0;
        for (int seed = 0; seed < 4096; ++seed) {
            if (cells[seed] != 0) continue;
            int head = 0, tail = 1, faces = 0;
            work.queue[0] = seed;
            cells[seed] = 1;
            while (head < tail) {
                final int cell = work.queue[head++];
                final int x = cell & 15, z = cell >> 4 & 15, y = cell >> 8;
                if (y == 0) faces |= 1; else if (cells[cell - 256] == 0) { cells[cell - 256] = 1; work.queue[tail++] = cell - 256; }
                if (y == 15) faces |= 2; else if (cells[cell + 256] == 0) { cells[cell + 256] = 1; work.queue[tail++] = cell + 256; }
                if (z == 0) faces |= 4; else if (cells[cell - 16] == 0) { cells[cell - 16] = 1; work.queue[tail++] = cell - 16; }
                if (z == 15) faces |= 8; else if (cells[cell + 16] == 0) { cells[cell + 16] = 1; work.queue[tail++] = cell + 16; }
                if (x == 0) faces |= 16; else if (cells[cell - 1] == 0) { cells[cell - 1] = 1; work.queue[tail++] = cell - 1; }
                if (x == 15) faces |= 32; else if (cells[cell + 1] == 0) { cells[cell + 1] = 1; work.queue[tail++] = cell + 1; }
            }
            for (int a = 0; a < 6; ++a) for (int b = a + 1; b < 6; ++b) {
                if ((faces & 1 << a) != 0 && (faces & 1 << b) != 0) result |= 1 << (a * (11 - a) / 2 + b - a - 1);
            }
            if (result == ALL) return ALL;
        }
        return (short)result;
    }
}
