package org.dreeam.leaf.world.antifreecam;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.Arrays;

/** Tick-thread only. Missing chunks terminate traversal; they never bridge unloaded terrain. */
public final class ConnectivityCache {
    static final class Column {
        LevelChunk chunk;
        final short[] masks;
        int minimumSurface = Integer.MIN_VALUE;
        int invalidationFloor = Integer.MIN_VALUE;
        Column(final LevelChunk chunk, final int height) {
            this.chunk = chunk;
            this.masks = new short[height];
            Arrays.fill(this.masks, (short)-1);
        }
    }
    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<Column> chunks = new Long2ObjectOpenHashMap<>();
    private long pass = 1;
    public ConnectivityCache(final ServerLevel level) { this.level = level; }
    static long key(final int x, final int z) { return (x & 0xffffffffL) | (long)z << 32; }
    long pass() { return this.pass; }
    // A tick/synchronous send is one stable traversal slice. Negative results expire at the next slice.
    void beginPass() { ++this.pass; }
    LevelChunk loaded(final int x, final int z) {
        return ((ChunkSystemLevel)this.level).moonrise$getFullChunkIfLoaded(x, z);
    }
    Column resolve(final int x, final int z) {
        final LevelChunk chunk = this.loaded(x, z);
        return chunk == null ? null : this.resolve(chunk);
    }
    Column resolve(final LevelChunk chunk) {
        final long key = key(chunk.getPos().x(), chunk.getPos().z());
        Column column = this.chunks.get(key);
        if (column == null || column.chunk != chunk) {
            if (column != null) column.chunk = null;
            column = new Column(chunk, this.level.getSectionsCount());
            this.chunks.put(key, column);
        }
        return column;
    }
    short mask(final Column column, final int section) {
        if (column.masks[section] == -1) column.masks[section] = SectionConnectivity.compute(column.chunk.getSections()[section]);
        return column.masks[section];
    }
    int minimumSurface(final Column column) {
        if (column.minimumSurface != Integer.MIN_VALUE) return column.minimumSurface;
        int minimum = Integer.MAX_VALUE;
        for (int z = 0; z < 16; ++z) for (int x = 0; x < 16; ++x) {
            minimum = Math.min(minimum, column.chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        }
        column.minimumSurface = minimum;
        column.invalidationFloor = minimum - 1;
        return minimum;
    }
    /** Must run even for air/non-solid changes: WORLD_SURFACE is not a solidity heightmap. */
    boolean invalidateSurface(final int x, final int y, final int z) {
        final Column column = this.chunks.get(key(x, z));
        if (column == null || y < column.invalidationFloor) return false;
        column.minimumSurface = Integer.MIN_VALUE;
        return true;
    }
    void invalidate(final int x, final int section, final int z) {
        final Column column = this.chunks.get(key(x, z));
        if (column != null && section >= 0 && section < column.masks.length) column.masks[section] = -1;
    }
    void unload(final int x, final int z) {
        final Column column = this.chunks.remove(key(x, z));
        if (column != null) column.chunk = null; // Do not retain an unloaded chunk through player column arrays.
        this.beginPass();
    }
    void clear() {
        for (final Column column : this.chunks.values()) column.chunk = null;
        this.chunks.clear();
        this.beginPass();
    }
}
