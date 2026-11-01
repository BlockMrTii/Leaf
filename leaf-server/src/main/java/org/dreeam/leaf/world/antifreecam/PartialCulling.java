package org.dreeam.leaf.world.antifreecam;

import io.netty.buffer.Unpooled;
import java.util.Arrays;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.jspecify.annotations.Nullable;

/**
 * Per-cell culling of sections that are unreachable for a player but intersect the surface, so they cannot be sent
 * as a full filler section without leaving stone cubes above the ground.
 * <p>
 * A cell is culled when neither it nor any of its six neighbours receives sky light: enclosed caves, base interiors
 * and ores in rock become filler, while the surface skin and everything lit by the sky stays real. The result only
 * depends on the chunk, so it is computed once per section and shared by all players.
 * <p>
 * The cell mask that was used for a packet is kept and used by every later filter (block entities, block updates,
 * effects, block light), so the filters always agree with what the client holds. When sky light changes and the mask
 * changes with it, the players holding the section are sent the chunk again.
 * <p>
 * Tick thread only.
 */
public final class PartialCulling {

    // only the serialized bytes are bounded; masks stay while their chunk is loaded, players may still hold them
    private static final int MAX_SERIALIZED = 512;
    // safety net for light changes that were not broadcast (no player tracked the chunk at that time)
    private static final long MAX_AGE_TICKS = 200L;
    private static final int MASK_WORDS = 4096 / 64;

    public interface ChangeListener {
        /**
         * The culled cells of the section changed, players holding it partially culled must get the chunk again.
         */
        void onPartialSectionChanged(int chunkX, int sectionY, int chunkZ);
    }

    private final ServerLevel level;
    private final LayerLightEventListener skyLight;
    // changes are collected and handed out once per tick, never while a packet is being built
    private final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet pendingChanges = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    private final ChangeListener listener = (chunkX, sectionY, chunkZ) -> this.pendingChanges.add(SectionPos.asLong(chunkX, sectionY, chunkZ));
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Entry> entries = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    // sections with serialized bytes, least recently used first
    private final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet serialized = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    // dimensions without sky light have no light to tell caves from the surface, nothing is culled per cell there
    private final boolean hasSkyLight;
    // reused per computation: the section itself, then down, up, north, south, west, east
    private final SectionLight[] lights = new SectionLight[7];

    private static final class Entry {
        // null if no cell is culled
        long @Nullable [] mask;
        // serialized section with the culled cells replaced, created lazily and dropped on block changes
        byte @Nullable [] data;
        long computedTick;
    }

    /**
     * Sky light of one section following Starlight's semantics: a missing nibble takes the bottom row of the nearest
     * initialised section above it, and full sky light if there is none.
     */
    private static final class SectionLight {
        @Nullable DataLayer layer;
        boolean fromAbove;
        boolean full;
        // unknown terrain (neighbour chunk not loaded)
        boolean dark;

        int get(final int x, final int y, final int z) {
            if (this.dark) {
                return 0;
            }
            if (this.full) {
                return 15;
            }
            final DataLayer layer = this.layer;
            if (layer == null) {
                return 15;
            }
            return layer.get(x, this.fromAbove ? 0 : y, z);
        }
    }

    public PartialCulling(final ServerLevel level) {
        this.level = level;
        this.skyLight = level.getLightEngine().getLayerListener(LightLayer.SKY);
        this.hasSkyLight = level.dimensionType().hasSkyLight();
        for (int i = 0; i < this.lights.length; ++i) {
            this.lights[i] = new SectionLight();
        }
    }

    private void resolve(final SectionLight light, final int chunkX, final int sectionY, final int chunkZ) {
        light.layer = null;
        light.fromAbove = false;
        light.full = false;
        light.dark = false;
        final int maxSection = this.level.getMaxSectionY();
        if (sectionY > maxSection) {
            light.full = true;
            return;
        }
        if (this.level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
            // unknown terrain counts as dark: border cells only stay real if lit from inside the chunk, so the mask
            // never opens up because a neighbour unloaded; a neighbour that loads later is picked up by the next recompute
            light.dark = true;
            return;
        }
        DataLayer layer = this.skyLight.getDataLayerData(SectionPos.of(chunkX, sectionY, chunkZ));
        if (layer != null) {
            light.layer = layer;
            return;
        }
        for (int above = sectionY + 1; above <= maxSection + 1; ++above) {
            layer = this.skyLight.getDataLayerData(SectionPos.of(chunkX, above, chunkZ));
            if (layer != null) {
                light.layer = layer;
                light.fromAbove = true;
                return;
            }
        }
        light.full = true;
    }

    private void loadLights(final int chunkX, final int sectionY, final int chunkZ) {
        this.resolve(this.lights[0], chunkX, sectionY, chunkZ);
        this.resolve(this.lights[1], chunkX, sectionY - 1, chunkZ);
        this.resolve(this.lights[2], chunkX, sectionY + 1, chunkZ);
        this.resolve(this.lights[3], chunkX, sectionY, chunkZ - 1);
        this.resolve(this.lights[4], chunkX, sectionY, chunkZ + 1);
        this.resolve(this.lights[5], chunkX - 1, sectionY, chunkZ);
        this.resolve(this.lights[6], chunkX + 1, sectionY, chunkZ);
    }

    private void releaseLights() {
        for (final SectionLight light : this.lights) {
            light.layer = null;
        }
    }

    private boolean culled(final int x, final int y, final int z) {
        final SectionLight[] l = this.lights;
        if (l[0].get(x, y, z) != 0) {
            return false;
        }
        return (y == 0 ? l[1].get(x, 15, z) : l[0].get(x, y - 1, z)) == 0
            && (y == 15 ? l[2].get(x, 0, z) : l[0].get(x, y + 1, z)) == 0
            && (z == 0 ? l[3].get(x, y, 15) : l[0].get(x, y, z - 1)) == 0
            && (z == 15 ? l[4].get(x, y, 0) : l[0].get(x, y, z + 1)) == 0
            && (x == 0 ? l[5].get(15, y, z) : l[0].get(x - 1, y, z)) == 0
            && (x == 15 ? l[6].get(0, y, z) : l[0].get(x + 1, y, z)) == 0;
    }

    private long @Nullable [] computeMask(final int chunkX, final int sectionY, final int chunkZ) {
        if (!this.hasSkyLight) {
            return null;
        }
        this.loadLights(chunkX, sectionY, chunkZ);
        try {
            if (this.lights[0].full) {
                return null;
            }
            long[] mask = null;
            for (int index = 0; index < 4096; ++index) {
                if (this.culled(index & 15, index >>> 8, (index >>> 4) & 15)) {
                    if (mask == null) {
                        mask = new long[MASK_WORDS];
                    }
                    mask[index >>> 6] |= 1L << index;
                }
            }
            return mask;
        } finally {
            this.releaseLights();
        }
    }

    private static boolean get(final long @Nullable [] mask, final int index) {
        return mask != null && (mask[index >>> 6] & 1L << index) != 0;
    }

    private @Nullable Entry entry(final int chunkX, final int sectionY, final int chunkZ, final long tick, final boolean create) {
        final long key = SectionPos.asLong(chunkX, sectionY, chunkZ);
        Entry entry = this.entries.get(key);
        if (entry != null) {
            if (tick - entry.computedTick > MAX_AGE_TICKS) {
                final long[] mask = this.computeMask(chunkX, sectionY, chunkZ);
                entry.computedTick = tick;
                if (!Arrays.equals(mask, entry.mask)) {
                    entry.mask = mask;
                    this.dropData(key, entry);
                    this.listener.onPartialSectionChanged(chunkX, sectionY, chunkZ);
                }
            }
            return entry;
        }
        if (!create) {
            return null;
        }
        // entries are only removed when their chunk unloads, which also forgets the chunk for every player,
        // so a new entry is never compared against a mask a player still holds
        entry = new Entry();
        entry.mask = this.computeMask(chunkX, sectionY, chunkZ);
        entry.computedTick = tick;
        this.entries.put(key, entry);
        return entry;
    }

    /**
     * @return the culled cells of the section (index {@code y << 8 | z << 4 | x}), or {@code null} if none
     */
    public long @Nullable [] mask(final LevelChunk chunk, final int sectionIndex, final long tick) {
        final Entry entry = this.entry(chunk.getPos().x(), chunk.getSectionYFromSectionIndex(sectionIndex), chunk.getPos().z(), tick, true);
        return entry == null ? null : entry.mask;
    }

    /**
     * @return {@code true} if the block is sent as filler in a partially culled section
     */
    public boolean isCulled(final int blockX, final int blockY, final int blockZ) {
        final Entry entry = this.entry(blockX >> 4, blockY >> 4, blockZ >> 4, this.level.getServer().getTickCount(), true);
        return entry != null && get(entry.mask, (blockY & 15) << 8 | (blockZ & 15) << 4 | (blockX & 15));
    }

    /**
     * @return the network serialization of the section with its culled cells replaced by filler, or {@code null} if
     * no cell is culled and the real section must be sent
     */
    public byte @Nullable [] serialize(final LevelChunk chunk, final int sectionIndex, final long tick) {
        final int chunkX = chunk.getPos().x();
        final int chunkZ = chunk.getPos().z();
        final int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
        final Entry entry = this.entry(chunkX, sectionY, chunkZ, tick, true);
        if (entry == null || entry.mask == null) {
            return null;
        }
        final long key = SectionPos.asLong(chunkX, sectionY, chunkZ);
        if (entry.data == null) {
            entry.data = serialize(chunk.getSections()[sectionIndex], entry.mask, HiddenSectionWriter.filler(this.level, sectionY), sectionIndex);
            this.serialized.addAndMoveToLast(key);
            while (this.serialized.size() > MAX_SERIALIZED) {
                final Entry oldest = this.entries.get(this.serialized.removeFirstLong());
                if (oldest != null) {
                    // only the bytes are dropped, the mask the players hold stays
                    oldest.data = null;
                }
            }
        } else {
            this.serialized.addAndMoveToLast(key);
        }
        return entry.data;
    }

    private void dropData(final long key, final Entry entry) {
        if (entry.data != null) {
            entry.data = null;
            this.serialized.remove(key);
        }
    }

    private static byte[] serialize(final LevelChunkSection real, final long[] mask, final BlockState filler, final int sectionIndex) {
        final LevelChunkSection copy = real.copy();
        for (int word = 0; word < MASK_WORDS; ++word) {
            long bits = mask[word];
            while (bits != 0L) {
                final int index = word << 6 | Long.numberOfTrailingZeros(bits);
                bits &= bits - 1L;
                copy.setBlockState(index & 15, index >>> 8, (index >>> 4) & 15, filler, false);
            }
        }
        final byte[] data = new byte[copy.getSerializedSize()];
        final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        buffer.writerIndex(0);
        // no chunk packet info, Anti-Xray skips this section like a fully hidden one
        copy.write(buffer, null, sectionIndex);
        if (buffer.writerIndex() != data.length) {
            throw new IllegalStateException("Partial section size mismatch: expected " + data.length + " bytes, got " + buffer.writerIndex());
        }
        return data;
    }

    /**
     * Copies the block light nibbles with every culled cell set to 0.
     */
    public static byte[] maskBlockLight(final byte[] nibbles, final long[] mask) {
        final byte[] ret = nibbles.clone();
        for (int word = 0; word < MASK_WORDS; ++word) {
            long bits = mask[word];
            while (bits != 0L) {
                final int index = word << 6 | Long.numberOfTrailingZeros(bits);
                bits &= bits - 1L;
                // DataLayer layout: index y << 8 | z << 4 | x, two cells per byte, low nibble first
                ret[index >> 1] &= (byte)((index & 1) == 0 ? 0xF0 : 0x0F);
            }
        }
        return ret;
    }

    /**
     * The serialized section is outdated, the mask only changes with the light (see {@link #onSkyLightChanged}).
     */
    public void onBlockChanged(final int blockX, final int blockY, final int blockZ) {
        if (this.entries.isEmpty()) {
            return;
        }
        final long key = SectionPos.asLong(blockX >> 4, blockY >> 4, blockZ >> 4);
        final Entry entry = this.entries.get(key);
        if (entry != null) {
            this.dropData(key, entry);
        }
    }

    /**
     * Sky light of the section changed, which also changes the culling of its neighbours.
     */
    public void onSkyLightChanged(final int chunkX, final int sectionY, final int chunkZ, final long tick) {
        this.recheck(chunkX, sectionY, chunkZ, tick);
        this.recheck(chunkX, sectionY - 1, chunkZ, tick);
        this.recheck(chunkX, sectionY + 1, chunkZ, tick);
        this.recheck(chunkX, sectionY, chunkZ - 1, tick);
        this.recheck(chunkX, sectionY, chunkZ + 1, tick);
        this.recheck(chunkX - 1, sectionY, chunkZ, tick);
        this.recheck(chunkX + 1, sectionY, chunkZ, tick);
    }

    private void recheck(final int chunkX, final int sectionY, final int chunkZ, final long tick) {
        if (sectionY < this.level.getMinSectionY() || sectionY > this.level.getMaxSectionY()) {
            return;
        }
        final long key = SectionPos.asLong(chunkX, sectionY, chunkZ);
        final Entry entry = this.entries.get(key);
        if (entry == null || entry.computedTick == tick) {
            // never sent partially culled since the chunk loaded, or already rechecked with this tick's light
            return;
        }
        final long[] mask = this.computeMask(chunkX, sectionY, chunkZ);
        entry.computedTick = tick;
        if (!Arrays.equals(mask, entry.mask)) {
            entry.mask = mask;
            this.dropData(key, entry);
            this.listener.onPartialSectionChanged(chunkX, sectionY, chunkZ);
        }
    }

    /**
     * Hands the sections whose culled cells changed since the last call to the consumer. Call once per tick, outside
     * of packet building.
     */
    /**
     * Keeps a change for the next tick, e.g. when the resend budget of this tick is used up.
     */
    public void requeue(final int chunkX, final int sectionY, final int chunkZ) {
        this.pendingChanges.add(SectionPos.asLong(chunkX, sectionY, chunkZ));
    }

    public boolean hasPendingChanges() {
        return !this.pendingChanges.isEmpty();
    }

    public void processChanges(final ChangeListener consumer) {
        while (!this.pendingChanges.isEmpty()) {
            final long key = this.pendingChanges.removeFirstLong();
            consumer.onPartialSectionChanged(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
        }
    }

    public void onChunkUnload(final int chunkX, final int chunkZ) {
        if (this.entries.isEmpty()) {
            return;
        }
        final int minSection = this.level.getMinSectionY();
        final int maxSection = this.level.getMaxSectionY();
        for (int sectionY = minSection; sectionY <= maxSection; ++sectionY) {
            final long key = SectionPos.asLong(chunkX, sectionY, chunkZ);
            this.entries.remove(key);
            this.serialized.remove(key);
        }
        // the neighbours keep their masks: the players hold exactly those, and every filter uses them
    }

    public void clear() {
        this.entries.clear();
        this.entries.trim();
        this.serialized.clear();
        this.pendingChanges.clear();
    }
}
