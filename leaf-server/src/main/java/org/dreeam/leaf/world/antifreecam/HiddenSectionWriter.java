package org.dreeam.leaf.world.antifreecam;

import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.Strategy;
import org.bukkit.World;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class HiddenSectionWriter {

    private static final ConcurrentHashMap<BlockState, byte[]> CACHED_BLOCK_BYTES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceKey<Biome>, byte[]> CACHED_DIMENSION_BIOME_BYTES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Holder<Biome>, byte[]> CACHED_BIOME_BYTES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceKey<Biome>, Holder<Biome>> CACHED_UNIFORM_BIOMES = new ConcurrentHashMap<>();

    private HiddenSectionWriter() {}

    /**
     * Determines the filler block state for the given level and section Y.
     * Nether -> NETHERRACK, End -> END_STONE, otherwise sectionY < 0 -> DEEPSLATE, else STONE.
     */
    public static BlockState filler(Level level, int sectionY) {
        if (level != null) {
            if (level.dimension() == Level.NETHER || (level.getWorld() != null && level.getWorld().getEnvironment() == World.Environment.NETHER)) {
                return Blocks.NETHERRACK.defaultBlockState();
            }
            if (level.dimension() == Level.END || (level.getWorld() != null && level.getWorld().getEnvironment() == World.Environment.THE_END)) {
                return Blocks.END_STONE.defaultBlockState();
            }
        }
        return sectionY < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    /**
     * Determines the uniform biome key for the given level.
     * Nether -> Biomes.NETHER_WASTES, End -> Biomes.THE_END, otherwise -> Biomes.PLAINS.
     */
    public static ResourceKey<Biome> biomeKey(Level level) {
        if (level != null) {
            if (level.dimension() == Level.NETHER || (level.getWorld() != null && level.getWorld().getEnvironment() == World.Environment.NETHER)) {
                return Biomes.NETHER_WASTES;
            }
            if (level.dimension() == Level.END || (level.getWorld() != null && level.getWorld().getEnvironment() == World.Environment.THE_END)) {
                return Biomes.THE_END;
            }
        }
        return Biomes.PLAINS;
    }

    /**
     * The uniform biome of the dimension, the one write() sends for hidden sections. Never null. Safe from any thread.
     * @throws IllegalStateException if neither the requested biome nor a factory fallback can be resolved
     */
    public static Holder<Biome> uniformBiome(Level level) {
        ResourceKey<Biome> key = biomeKey(level);
        Holder<Biome> holder = CACHED_UNIFORM_BIOMES.get(key);
        if (holder == null) {
            holder = resolveBiomeHolder(level, key);
            if (holder == null) {
                throw new IllegalStateException("Could not resolve biome holder for " + key);
            }
            Holder<Biome> existing = CACHED_UNIFORM_BIOMES.putIfAbsent(key, holder);
            return existing != null ? existing : holder;
        }
        return holder;
    }

    private static byte[] getBlockBytes(Level level, BlockState filler) {
        byte[] bytes = CACHED_BLOCK_BYTES.get(filler);
        if (bytes == null) {
            bytes = serializeContainer(level, filler);
            byte[] existing = CACHED_BLOCK_BYTES.putIfAbsent(filler, bytes);
            return existing != null ? existing : bytes;
        }
        return bytes;
    }

    private static byte[] serializeContainer(Level level, BlockState filler) {
        Strategy<BlockState> strategy = level != null
            ? level.palettedContainerFactory().blockStatesStrategy()
            : Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        PalettedContainer<BlockState> container = new PalettedContainer<>(filler, strategy, null);
        FriendlyByteBuf tempBuf = new FriendlyByteBuf(Unpooled.buffer(container.getSerializedSize()));
        try {
            container.write(tempBuf, null, 0);
            byte[] bytes = new byte[tempBuf.readableBytes()];
            tempBuf.readBytes(bytes);
            return bytes;
        } finally {
            tempBuf.release();
        }
    }

    private static Holder<Biome> resolveBiomeHolder(Level level, ResourceKey<Biome> key) {
        if (level != null) {
            try {
                Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
                Optional<Holder.Reference<Biome>> holder = registry.get(key);
                if (holder.isPresent()) {
                    return holder.get();
                }
            } catch (Exception ignored) {
            }
            PalettedContainerFactory factory = level.palettedContainerFactory();
            if (factory != null) {
                Holder<Biome> defaultBiome = factory.defaultBiome();
                if (defaultBiome != null) {
                    return defaultBiome;
                }
                PalettedContainer<Holder<Biome>> defaultContainer = factory.createForBiomes();
                if (defaultContainer != null) {
                    return defaultContainer.get(0, 0, 0);
                }
            }
        }
        return null;
    }

    private static byte[] serializeBiomeContainer(Level level, Holder<Biome> holder) {
        Strategy<Holder<Biome>> strategy = level != null
            ? level.palettedContainerFactory().biomeStrategy()
            : null;
        if (strategy == null) {
            throw new IllegalStateException("Cannot serialize hidden section biomes without level context");
        }
        PalettedContainer<Holder<Biome>> container = new PalettedContainer<>(holder, strategy, null);
        FriendlyByteBuf tempBuf = new FriendlyByteBuf(Unpooled.buffer(container.getSerializedSize()));
        try {
            container.write(tempBuf, null, 0);
            byte[] bytes = new byte[tempBuf.readableBytes()];
            tempBuf.readBytes(bytes);
            return bytes;
        } finally {
            tempBuf.release();
        }
    }

    private static byte[] getBiomeBytes(Level level) {
        ResourceKey<Biome> key = biomeKey(level);
        byte[] bytes = CACHED_DIMENSION_BIOME_BYTES.get(key);
        if (bytes == null) {
            Holder<Biome> holder = resolveBiomeHolder(level, key);
            if (holder == null) {
                throw new IllegalStateException("Could not resolve biome holder for " + key);
            }
            bytes = CACHED_BIOME_BYTES.get(holder);
            if (bytes == null) {
                bytes = serializeBiomeContainer(level, holder);
                byte[] existing = CACHED_BIOME_BYTES.putIfAbsent(holder, bytes);
                if (existing != null) {
                    bytes = existing;
                }
            }
            byte[] existingDim = CACHED_DIMENSION_BIOME_BYTES.putIfAbsent(key, bytes);
            return existingDim != null ? existingDim : bytes;
        }
        return bytes;
    }

    /**
     * Exact number of bytes written by {@link #write(FriendlyByteBuf, Level, LevelChunkSection, int)}.
     */
    public static int serializedSize(Level level, LevelChunkSection realSection, int sectionY) {
        BlockState filler = filler(level, sectionY);
        byte[] blockBytes = getBlockBytes(level, filler);
        byte[] biomeBytes = getBiomeBytes(level);
        return 4 + blockBytes.length + biomeBytes.length;
    }

    /**
     * Writes a hidden section in the Minecraft network format:
     * short nonEmptyBlockCount (=4096), short fluidCount (=0),
     * a single-value Block-PalettedContainer (filler block),
     * and a uniform per-dimension biome instead of the real biomes, so cave biomes do not leak.
     */
    public static void write(FriendlyByteBuf buffer, Level level, LevelChunkSection realSection, int sectionY) {
        BlockState filler = filler(level, sectionY);
        byte[] blockBytes = getBlockBytes(level, filler);
        byte[] biomeBytes = getBiomeBytes(level);

        buffer.writeShort((short) 4096);
        buffer.writeShort((short) 0);
        buffer.writeBytes(blockBytes);
        buffer.writeBytes(biomeBytes);
    }
}
