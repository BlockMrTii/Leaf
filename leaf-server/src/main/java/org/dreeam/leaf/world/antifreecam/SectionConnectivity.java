package org.dreeam.leaf.world.antifreecam;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import java.util.Arrays;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.level.block.state.BlockState;

/** Face order is Direction.ordinal(): down, up, north, south, west, east. */
public final class SectionConnectivity {
    public static final short ALL = 0x7fff;
    public static final int MAX_COMPONENTS = 15;
    // Components are constructed on the tick thread only; zero denotes an unvisited player slot.
    private static int nextComponentId;

    /** Immutable after construction; may be read from other threads once published. */
    public static final class Components {
        private final int id;
        private final int count;
        private final byte @Nullable [] labels;
        private final long @Nullable [] open;
        private final long[] portals;
        // Only this bounded memoization changes after publication; masks are never mutated.
        private final int[] cachedBits = {-1, -1, -1, -1};
        private final long[][] cachedCells = new long[4][];
        private int cacheCursor;

        private Components(final int count, final byte @Nullable [] labels, final long @Nullable [] open, final long[] portals) {
            // 0 marks an unvisited slot in PlayerVisibility, skip it if the counter ever wraps around
            if (++nextComponentId == 0) ++nextComponentId;
            this.id = nextComponentId;
            this.count = count;
            this.labels = labels;
            this.open = open;
            this.portals = portals;
        }
        public int id() { return this.id; }
        public int count() { return this.count; }
        public int allBits() { return (1 << this.count) - 1; }
        public int componentAt(final int cell) {
            if (this.count == 0) return -1;
            if (this.labels != null) return this.labels[cell] - 1;
            return this.open == null || (this.open[cell >>> 6] & 1L << cell) != 0 ? 0 : -1;
        }
        /** The returned cell mask is shared and must never be mutated. */
        public synchronized long @Nullable [] culledCells(final int reachedBits) {
            final int reached = reachedBits & this.allBits();
            if (reached == this.allBits()) return null;
            for (int i = 0; i < this.cachedBits.length; ++i) {
                if (this.cachedBits[i] == reached) return this.cachedCells[i];
            }
            final long[] cells = new long[64];
            if (this.labels == null) {
                if (this.open == null) Arrays.fill(cells, -1L);
                else System.arraycopy(this.open, 0, cells, 0, 64);
            } else {
                for (int cell = 0; cell < 4096; ++cell) {
                    final int component = this.labels[cell] - 1;
                    if (component >= 0 && (reached & 1 << component) == 0) cells[cell >>> 6] |= 1L << cell;
                }
            }
            final int slot = this.cacheCursor++ & 3;
            this.cachedCells[slot] = cells;
            this.cachedBits[slot] = reached;
            return cells;
        }
        boolean hasPortal(final int component, final int face) {
            final int offset = (component * 6 + face) * 4;
            return (this.portals[offset] | this.portals[offset + 1] | this.portals[offset + 2] | this.portals[offset + 3]) != 0;
        }
        boolean overlaps(final int component, final int face, final Components other, final int nextComponent) {
            final int offset = (component * 6 + face) * 4;
            final int next = (nextComponent * 6 + (face ^ 1)) * 4;
            for (int i = 0; i < 4; ++i) if ((this.portals[offset + i] & other.portals[next + i]) != 0) return true;
            return false;
        }
    }

    private static final Components EMPTY = new Components(0, null, null, new long[0]);
    private static final Components OPEN = openComponents();
    private static Components openComponents() {
        final long[] portals = new long[24];
        Arrays.fill(portals, -1L);
        return new Components(1, null, null, portals);
    }

    public static Components components(final LevelChunkSection section) {
        if (section.hasOnlyAir() || !section.maybeHas(SOLID)) return OPEN;
        if (!section.maybeHas(TRANSPARENT)) return EMPTY;
        final Workspace work = WORK.get();
        readBarriers(section, work);
        final byte[] cells = work.cells;
        // Zero is unvisited, -1 is a barrier, positive values are component id + 1.
        for (int cell = 0; cell < 4096; ++cell) cells[cell] = cells[cell] == 0 ? 0 : (byte)-1;
        int count = 0;
        boolean fallback = false;
        for (int seed = 0; seed < 4096; ++seed) {
            if (cells[seed] != 0) continue;
            if (count == MAX_COMPONENTS) { fallback = true; break; }
            final byte label = (byte)++count;
            int head = 0, tail = 1;
            work.queue[0] = seed;
            cells[seed] = label;
            while (head < tail) {
                final int cell = work.queue[head++];
                final int x = cell & 15, z = cell >> 4 & 15, y = cell >> 8;
                if (y > 0 && cells[cell - 256] == 0) { cells[cell - 256] = label; work.queue[tail++] = cell - 256; }
                if (y < 15 && cells[cell + 256] == 0) { cells[cell + 256] = label; work.queue[tail++] = cell + 256; }
                if (z > 0 && cells[cell - 16] == 0) { cells[cell - 16] = label; work.queue[tail++] = cell - 16; }
                if (z < 15 && cells[cell + 16] == 0) { cells[cell + 16] = label; work.queue[tail++] = cell + 16; }
                if (x > 0 && cells[cell - 1] == 0) { cells[cell - 1] = label; work.queue[tail++] = cell - 1; }
                if (x < 15 && cells[cell + 1] == 0) { cells[cell + 1] = label; work.queue[tail++] = cell + 1; }
            }
        }
        if (count == 0) return EMPTY;
        if (fallback) count = 1;
        final byte[] labels = count > 1 ? new byte[4096] : null;
        final long[] open = count == 1 ? new long[64] : null;
        final long[] portals = new long[count * 24];
        for (int cell = 0; cell < 4096; ++cell) {
            if (cells[cell] == -1) continue;
            final int component = fallback ? 0 : cells[cell] - 1;
            if (labels != null) labels[cell] = (byte)(component + 1);
            if (open != null) open[cell >>> 6] |= 1L << cell;
            final int x = cell & 15, z = cell >> 4 & 15, y = cell >> 8;
            if (y == 0) portal(portals, component, 0, z << 4 | x);
            if (y == 15) portal(portals, component, 1, z << 4 | x);
            if (z == 0) portal(portals, component, 2, y << 4 | x);
            if (z == 15) portal(portals, component, 3, y << 4 | x);
            if (x == 0) portal(portals, component, 4, y << 4 | z);
            if (x == 15) portal(portals, component, 5, y << 4 | z);
        }
        return new Components(count, labels, open, portals);
    }
    private static void portal(final long[] portals, final int component, final int face, final int cell) {
        portals[(component * 6 + face) * 4 + (cell >>> 6)] |= 1L << cell;
    }
    private static final Predicate<BlockState> TRANSPARENT = state -> !isBarrier(state);
    private static final Predicate<BlockState> SOLID = SectionConnectivity::isBarrier;
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
                ret[id] = (byte)(state != null && isBarrier(state) ? 1 : 0);
            }
            globalSolid = ret;
        }
        return ret;
    }
    private SectionConnectivity() {}

    // closed doors and trapdoors block sight and passage although they are not full blocks
    public static boolean isBarrier(final BlockState state) {
        if (state.isSolidRender()) return true;
        final Block block = state.getBlock();
        return (block instanceof DoorBlock || block instanceof TrapDoorBlock) && !state.getValue(BlockStateProperties.OPEN);
    }

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
        readBarriers(section, work);
        final byte[] cells = work.cells;
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

    private static void readBarriers(final LevelChunkSection section, final Workspace work) {
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
                solid[i] = (byte)(isBarrier(palette.valueFor(i)) ? 1 : 0);
            }
        }
        for (int i = 0; i < 4096; ++i) {
            cells[i] = solid[ids[i]];
        }
    }
}
