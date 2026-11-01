package org.dreeam.leaf.world.antifreecam;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.bukkit.World;

import java.util.concurrent.ConcurrentHashMap;

public final class HiddenSectionWriter {

    private static final ConcurrentHashMap<BlockState, PalettedContainer<BlockState>> CONTAINERS = new ConcurrentHashMap<>();

    private HiddenSectionWriter() {}

    /**
     * Determines the filler block state for the given level and section Y.
     * Nether -> NETHERRACK, End -> END_STONE, otherwise sectionY < 0 -> DEEPSLATE, else STONE.
     */
    public static BlockState filler(Level level, int sectionY) {
        if (level.dimension() == Level.NETHER || (level.getWorld() != null && level.getWorld().getEnvironment() == World.Environment.NETHER)) {
            return Blocks.NETHERRACK.defaultBlockState();
        }
        if (level.dimension() == Level.END || (level.getWorld() != null && level.getWorld().getEnvironment() == World.Environment.THE_END)) {
            return Blocks.END_STONE.defaultBlockState();
        }
        return sectionY < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    private static PalettedContainer<BlockState> getContainer(Level level, BlockState filler) {
        PalettedContainer<BlockState> container = CONTAINERS.get(filler);
        if (container == null) {
            Strategy<BlockState> strategy = level != null
                ? level.palettedContainerFactory().blockStatesStrategy()
                : Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
            container = new PalettedContainer<>(filler, strategy, null);
            PalettedContainer<BlockState> existing = CONTAINERS.putIfAbsent(filler, container);
            return existing != null ? existing : container;
        }
        return container;
    }

    /**
     * Exact number of bytes written by {@link #write(FriendlyByteBuf, Level, LevelChunkSection, int)}.
     */
    public static int serializedSize(Level level, LevelChunkSection realSection, int sectionY) {
        BlockState filler = filler(level, sectionY);
        PalettedContainer<BlockState> container = getContainer(level, filler);
        return 4 + container.getSerializedSize() + realSection.getBiomes().getSerializedSize();
    }

    /**
     * Writes a hidden section in the Minecraft network format:
     * short nonEmptyBlockCount (=4096), short fluidCount (=0),
     * a single-value Block-PalettedContainer (filler block),
     * and the real biomes from realSection.
     */
    public static void write(FriendlyByteBuf buffer, Level level, LevelChunkSection realSection, int sectionY) {
        BlockState filler = filler(level, sectionY);
        PalettedContainer<BlockState> container = getContainer(level, filler);

        buffer.writeShort((short) 4096);
        buffer.writeShort((short) 0);
        container.write(buffer, null, sectionY);
        realSection.getBiomes().write(buffer, null, sectionY);
    }
}
