package org.dreeam.leaf.world.evd;

import ca.spottedleaf.moonrise.common.util.WorldUtil;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel;
import ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.antixray.ChunkPacketBlockController;
import io.papermc.paper.configuration.type.EngineMode;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.dreeam.leaf.config.modules.misc.AntiFreecam;
import org.dreeam.leaf.config.modules.opt.ExtendedViewDistance;
import org.dreeam.leaf.world.antifreecam.EvdSurface;
import org.dreeam.leaf.world.antifreecam.HiddenSectionWriter;
import org.jspecify.annotations.Nullable;

public final class FarChunkPacketBuilder {

    private static final int BLOCK_ENTITY_LIMIT = Integer.getInteger("Paper.excessiveTELimit", 750);

    // Bounded, immutable snapshots; weak keys do not retain unloaded worlds or old configurations.
    private static volatile Classification[] classifications = new Classification[0];
    private static int nextClassification;

    private FarChunkPacketBuilder() {}

    private static Classification classification(ServerLevel world) {
        var antiXray = world.paperConfig().anticheat.antiXray;
        for (Classification cached : classifications) {
            if (cached.configuration.get() == antiXray) {
                return cached;
            }
        }
        synchronized (Classification.class) {
            Classification[] current = classifications;
            for (Classification cached : current) {
                if (cached.configuration.get() == antiXray) {
                    return cached;
                }
            }
            Classification created = new Classification(antiXray, antiXray.hiddenBlocks, antiXray.replacementBlocks, antiXray.engineMode, antiXray.lavaObscures);
            Classification[] updated;
            if (current.length < 8) {
                updated = java.util.Arrays.copyOf(current, current.length + 1);
                updated[current.length] = created;
            } else {
                updated = current.clone();
                updated[nextClassification] = created;
                nextClassification = (nextClassification + 1) % updated.length;
            }
            classifications = updated;
            return created;
        }
    }

    private static final class Classification {
        private final WeakReference<Object> configuration;
        private final @Nullable Set<Block> hiddenBlocks;
        private final @Nullable Set<Block> replacementBlocks;
        private final boolean lavaObscures;
        private final Predicate<BlockState> stone;
        private final Predicate<BlockState> deepslate;
        private final Predicate<BlockState> netherrack;
        private final Predicate<BlockState> endStone;

        private Classification(Object configuration, @Nullable List<Block> hiddenBlocks, @Nullable List<Block> replacementBlocks, EngineMode engineMode, boolean lavaObscures) {
            this.configuration = new WeakReference<>(configuration);
            this.hiddenBlocks = hiddenBlocks != null && !hiddenBlocks.isEmpty() ? new ReferenceOpenHashSet<>(hiddenBlocks) : null;
            boolean obfuscate = engineMode == EngineMode.OBFUSCATE || engineMode == EngineMode.OBFUSCATE_LAYER;
            this.replacementBlocks = obfuscate && replacementBlocks != null && !replacementBlocks.isEmpty() ? new ReferenceOpenHashSet<>(replacementBlocks) : null;
            this.lavaObscures = lavaObscures;
            this.stone = new PaletteCheck(this, Blocks.STONE.defaultBlockState());
            this.deepslate = new PaletteCheck(this, Blocks.DEEPSLATE.defaultBlockState());
            this.netherrack = new PaletteCheck(this, Blocks.NETHERRACK.defaultBlockState());
            this.endStone = new PaletteCheck(this, Blocks.END_STONE.defaultBlockState());
        }

        private Predicate<BlockState> paletteCheck(BlockState filler) {
            if (filler == Blocks.STONE.defaultBlockState()) {
                return this.stone;
            }
            if (filler == Blocks.DEEPSLATE.defaultBlockState()) {
                return this.deepslate;
            }
            return filler == Blocks.NETHERRACK.defaultBlockState() ? this.netherrack : this.endStone;
        }
    }

    private static final class PaletteCheck implements Predicate<BlockState> {
        private final Classification classification;
        private final BlockState filler;

        private PaletteCheck(Classification classification, BlockState filler) {
            this.classification = classification;
            this.filler = filler;
        }

        @Override
        public boolean test(BlockState state) {
            if (state == this.filler) {
                return false;
            }
            Block block = state.getBlock();
            return (this.classification.hiddenBlocks != null && this.classification.hiddenBlocks.contains(block))
                || (this.classification.replacementBlocks != null && this.classification.replacementBlocks.contains(block));
        }
    }

    /**
     * Off-main-thread safe. Returns null if the chunk is unusable (not FULL, light not correct,
     * coordinate mismatch, parse error). Never throws.
     */
    public static @Nullable ClientboundLevelChunkWithLightPacket build(ServerLevel world, CompoundTag chunkTag, int chunkX, int chunkZ) {
        if (world == null || chunkTag == null) {
            return null;
        }

        try {
            // 1. Upgrade chunk tag via datafixers
            CompoundTag converted = world.getChunkSource().chunkMap.upgradeChunkTag(chunkTag);
            if (converted == null) {
                return null;
            }

            // 2. Parse chunk data from NBT
            SerializableChunkData data = SerializableChunkData.parse(world, world.palettedContainerFactory(), converted);
            if (data == null) {
                return null;
            }

            // 3. Status, light correct, coordinate checks
            if (data.chunkStatus() != ChunkStatus.FULL || !data.lightCorrect()) {
                return null;
            }
            if (data.chunkPos().x() != chunkX || data.chunkPos().z() != chunkZ) {
                return null;
            }

            // 4. Sections
            int sectionCount = world.getSectionsCount();
            int minSectionY = world.getMinSectionY();
            LevelChunkSection[] sections = new LevelChunkSection[sectionCount];

            List<SerializableChunkData.SectionData> sectionDataList = data.sectionData();
            if (sectionDataList != null) {
                for (SerializableChunkData.SectionData sd : sectionDataList) {
                    int sectionIndex = sd.y() - minSectionY;
                    if (sectionIndex >= 0 && sectionIndex < sectionCount && sd.chunkSection() != null) {
                        sections[sectionIndex] = sd.chunkSection();
                    }
                }
            }

            for (int i = 0; i < sectionCount; i++) {
                if (sections[i] == null) {
                    sections[i] = new LevelChunkSection(world.palettedContainerFactory(), world, data.chunkPos(), world.getSectionYFromSectionIndex(i));
                }
            }

            boolean antiFreecamEnabled = AntiFreecam.isEnabled(world);
            Map<Heightmap.Types, long[]> rawHeightmaps = data.heightmaps();
            BitSet hiddenSections = antiFreecamEnabled ? EvdSurface.hiddenSections(world, rawHeightmaps) : null;

            // 5. Block Entities (only if configured, up to BLOCK_ENTITY_LIMIT)
            // Extracted before obfuscateNonHiddenSections so loadStatic receives the original block state (e.g. chest)
            // rather than the replaced filler block (stone/deepslate), avoiding incompatible block entity errors.
            List<ClientboundLevelChunkPacketData.BlockEntityInfo> blockEntitiesData;
            List<CompoundTag> rawBlockEntities = data.blockEntities();
            if (ExtendedViewDistance.sendBlockEntities && rawBlockEntities != null && !rawBlockEntities.isEmpty()) {
                blockEntitiesData = new ArrayList<>();
                Set<Block> hiddenBlocksSet = antiFreecamEnabled && world.chunkPacketBlockController != ChunkPacketBlockController.NO_OPERATION_INSTANCE
                    ? classification(world).hiddenBlocks : null;
                for (CompoundTag tag : rawBlockEntities) {
                    if (blockEntitiesData.size() >= BLOCK_ENTITY_LIMIT) {
                        break;
                    }
                    try {
                        BlockPos pos = BlockEntity.getPosFromTag(data.chunkPos(), tag);
                        if (world.isOutsideBuildHeight(pos)) {
                            continue;
                        }
                        int sectionIndex = world.getSectionIndex(pos.getY());
                        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
                            continue;
                        }
                        if (hiddenSections != null && hiddenSections.get(sectionIndex)) {
                            continue;
                        }
                        LevelChunkSection section = sections[sectionIndex];
                        BlockState state = section.getBlockState(
                            SectionPos.sectionRelative(pos.getX()),
                            SectionPos.sectionRelative(pos.getY()),
                            SectionPos.sectionRelative(pos.getZ())
                        );
                        if (hiddenBlocksSet != null && hiddenBlocksSet.contains(state.getBlock())) {
                            continue;
                        }

                        BlockEntity be = BlockEntity.loadStatic(pos, state, tag, world.registryAccess());
                        if (be != null) {
                            CompoundTag updateTag = be.getUpdateTag(world.registryAccess());
                            be.sanitizeSentNbt(updateTag);
                            blockEntitiesData.add(ClientboundLevelChunkPacketData.leaf$blockEntityInfo(pos, be.getType(), updateTag));
                        }
                    } catch (Throwable ignored) {
                        // Skip individual block entity failure
                    }
                }
                if (blockEntitiesData.isEmpty()) {
                    blockEntitiesData = List.of();
                }
            } else {
                blockEntitiesData = List.of();
            }

            if (antiFreecamEnabled) {
                // Disk path has no loaded neighbor chunk data available asynchronously;
                // chunk boundaries are treated as exposed (matching Paper's EMPTY_SECTION = transparent behavior).
                obfuscateNonHiddenSections(world, sections, sectionCount, hiddenSections, false, null, null, null, null);
            }

            byte[] buffer = serializeSections(world, sections, sectionCount, hiddenSections);
            if (buffer == null) {
                return null;
            }

            // 6. Heightmaps (only client-bound types, cloned arrays)
            Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
            if (rawHeightmaps != null) {
                for (Map.Entry<Heightmap.Types, long[]> entry : rawHeightmaps.entrySet()) {
                    if (entry.getKey().sendToClient() && entry.getValue() != null) {
                        heightmaps.put(entry.getKey(), entry.getValue().clone());
                    }
                }
            }

            ClientboundLevelChunkPacketData chunkData = ClientboundLevelChunkPacketData.leaf$fromRaw(
                heightmaps, buffer, blockEntitiesData
            );

            // 7. Light (reproducing Starlight SWMRNibbleArray.toVanillaNibble logic)
            int totalLightSections = WorldUtil.getTotalLightSections(world);
            int minLightSection = WorldUtil.getMinLightSection(world);
            int bitSetSize = ((totalLightSections + 63) >>> 6) << 6;

            BitSet skyYMask = new BitSet(bitSetSize);
            BitSet blockYMask = new BitSet(bitSetSize);
            BitSet emptySkyYMask = new BitSet(bitSetSize);
            BitSet emptyBlockYMask = new BitSet(bitSetSize);
            boolean hasSkyLight = world.dimensionType().hasSkyLight();
            List<byte[]> skyUpdates = hasSkyLight ? new ArrayList<>() : List.of();
            List<byte[]> blockUpdates = new ArrayList<>();

            SerializableChunkData.SectionData[] lightSections = new SerializableChunkData.SectionData[totalLightSections];
            if (sectionDataList != null) {
                for (SerializableChunkData.SectionData sd : sectionDataList) {
                    int lightSectionIndex = sd.y() - minLightSection;
                    if (lightSectionIndex >= 0 && lightSectionIndex < totalLightSections) {
                        lightSections[lightSectionIndex] = sd;
                    }
                }
            }

            for (int sectionIndex = 0; sectionIndex < totalLightSections; sectionIndex++) {
                SerializableChunkData.SectionData sd = lightSections[sectionIndex];
                if (sd == null) {
                    // Missing section: NULL state -> no mask bit, no updates
                    continue;
                }

                // Block Light
                int blockSectionIndex = sd.y() - minSectionY;
                boolean isHidden = hiddenSections != null && blockSectionIndex >= 0 && blockSectionIndex < sectionCount && hiddenSections.get(blockSectionIndex);

                if (isHidden) {
                    emptyBlockYMask.set(sectionIndex);
                } else {
                    int blockState = ((StarlightSectionData) (Object) sd).starlight$getBlockLightState();
                    if (blockState == 1) { // INIT_STATE_UNINIT
                        emptyBlockYMask.set(sectionIndex);
                    } else if (blockState == 2) { // INIT_STATE_INIT
                        DataLayer blockLight = sd.blockLight();
                        if (blockLight == null) {
                            return null; // Corrupted light data
                        }
                        blockYMask.set(sectionIndex);
                        blockUpdates.add(blockLight.getData().clone());
                    }
                }
                // blockState 0 (INIT_STATE_NULL) and 3 (INIT_STATE_HIDDEN): no entry

                // Sky Light
                if (hasSkyLight) {
                    int skyState = ((StarlightSectionData) (Object) sd).starlight$getSkyLightState();
                    if (skyState == 1) { // INIT_STATE_UNINIT
                        emptySkyYMask.set(sectionIndex);
                    } else if (skyState == 2) { // INIT_STATE_INIT
                        DataLayer skyLight = sd.skyLight();
                        if (skyLight == null) {
                            return null; // Corrupted light data
                        }
                        skyYMask.set(sectionIndex);
                        skyUpdates.add(skyLight.getData().clone());
                    }
                    // skyState 0 (INIT_STATE_NULL) and 3 (INIT_STATE_HIDDEN): no entry
                }
            }

            ClientboundLightUpdatePacketData lightData = new ClientboundLightUpdatePacketData(
                skyYMask, blockYMask, emptySkyYMask, emptyBlockYMask,
                skyUpdates.isEmpty() ? List.of() : skyUpdates,
                blockUpdates.isEmpty() ? List.of() : blockUpdates
            );

            // 8. Construct combined packet
            return new ClientboundLevelChunkWithLightPacket(chunkX, chunkZ, chunkData, lightData);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Builds a snapshot packet for a loaded chunk when Anti-Freecam is active.
     * Must be called on the tick thread. Never modifies the live chunk.
     */
    public static @Nullable ClientboundLevelChunkWithLightPacket buildLive(ServerLevel world, LevelChunk live) {
        if (world == null || live == null) {
            return null;
        }

        try {
            int sectionCount = world.getSectionsCount();
            int minSectionY = world.getMinSectionY();
            BitSet hiddenSections = EvdSurface.hiddenSections(live);
            LevelChunkSection[] rawSections = live.getSections();
            LevelChunkSection[] sections = new LevelChunkSection[sectionCount];

            for (int i = 0; i < sectionCount; i++) {
                LevelChunkSection raw = i < rawSections.length ? rawSections[i] : null;
                if (raw == null) {
                    sections[i] = new LevelChunkSection(world.palettedContainerFactory(), world, live.getPos(), world.getSectionYFromSectionIndex(i));
                } else {
                    // Borrow read-only until obfuscation needs to change this section.
                    sections[i] = raw;
                }
            }

            int chunkX = live.getPos().x();
            int chunkZ = live.getPos().z();
            ChunkSystemLevel chunkSystemLevel = (ChunkSystemLevel) world;
            LevelChunk westChunk = chunkSystemLevel.moonrise$getFullChunkIfLoaded(chunkX - 1, chunkZ);
            LevelChunk eastChunk = chunkSystemLevel.moonrise$getFullChunkIfLoaded(chunkX + 1, chunkZ);
            LevelChunk northChunk = chunkSystemLevel.moonrise$getFullChunkIfLoaded(chunkX, chunkZ - 1);
            LevelChunk southChunk = chunkSystemLevel.moonrise$getFullChunkIfLoaded(chunkX, chunkZ + 1);

            LevelChunkSection[] westSections = westChunk != null ? westChunk.getSections() : null;
            LevelChunkSection[] eastSections = eastChunk != null ? eastChunk.getSections() : null;
            LevelChunkSection[] northSections = northChunk != null ? northChunk.getSections() : null;
            LevelChunkSection[] southSections = southChunk != null ? southChunk.getSections() : null;

            obfuscateNonHiddenSections(world, sections, sectionCount, hiddenSections, true, westSections, eastSections, northSections, southSections);

            byte[] buffer = serializeSections(world, sections, sectionCount, hiddenSections);
            if (buffer == null) {
                return null;
            }

            // Heightmaps (only client-bound types, cloned arrays)
            Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
            for (Map.Entry<Heightmap.Types, Heightmap> entry : live.getHeightmaps()) {
                if (entry.getKey().sendToClient()) {
                    heightmaps.put(entry.getKey(), entry.getValue().getRawData().clone());
                }
            }

            // Block Entities (only if configured, up to BLOCK_ENTITY_LIMIT)
            List<ClientboundLevelChunkPacketData.BlockEntityInfo> blockEntitiesData;
            if (ExtendedViewDistance.sendBlockEntities && !live.getBlockEntities().isEmpty()) {
                blockEntitiesData = new ArrayList<>();
                Set<Block> hiddenBlocksSet = world.chunkPacketBlockController != ChunkPacketBlockController.NO_OPERATION_INSTANCE
                    ? classification(world).hiddenBlocks : null;

                for (BlockEntity be : live.getBlockEntities().values()) {
                    if (blockEntitiesData.size() >= BLOCK_ENTITY_LIMIT) {
                        break;
                    }
                    BlockPos pos = be.getBlockPos();
                    if (world.isOutsideBuildHeight(pos)) {
                        continue;
                    }
                    int sectionIndex = world.getSectionIndex(pos.getY());
                    if (sectionIndex < 0 || sectionIndex >= sectionCount) {
                        continue;
                    }
                    if (hiddenSections != null && hiddenSections.get(sectionIndex)) {
                        continue;
                    }
                    if (hiddenBlocksSet != null && hiddenBlocksSet.contains(be.getBlockState().getBlock())) {
                        continue;
                    }

                    CompoundTag updateTag = be.getUpdateTag(world.registryAccess());
                    be.sanitizeSentNbt(updateTag);
                    blockEntitiesData.add(ClientboundLevelChunkPacketData.leaf$blockEntityInfo(pos, be.getType(), updateTag));
                }
                if (blockEntitiesData.isEmpty()) {
                    blockEntitiesData = List.of();
                }
            } else {
                blockEntitiesData = List.of();
            }

            ClientboundLevelChunkPacketData chunkData = ClientboundLevelChunkPacketData.leaf$fromRaw(
                heightmaps, buffer, blockEntitiesData
            );

            // Light from light engine with hidden sections block light sent as empty
            ClientboundLightUpdatePacketData lightData = new ClientboundLightUpdatePacketData(
                live.getPos(), world.getLightEngine(), null, null, hiddenSections
            );

            return new ClientboundLevelChunkWithLightPacket(live.getPos().x(), live.getPos().z(), chunkData, lightData);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte @Nullable [] serializeSections(
        ServerLevel world,
        LevelChunkSection[] sections,
        int sectionCount,
        @Nullable BitSet hiddenSections
    ) {
        int totalSize = 0;
        for (int i = 0; i < sectionCount; i++) {
            if (hiddenSections != null && hiddenSections.get(i)) {
                totalSize += HiddenSectionWriter.serializedSize(world, sections[i], world.getSectionYFromSectionIndex(i));
            } else {
                totalSize += sections[i].getSerializedSize();
            }
        }

        byte[] buffer = new byte[totalSize];
        ByteBuf writeBuffer = Unpooled.wrappedBuffer(buffer);
        writeBuffer.writerIndex(0);
        FriendlyByteBuf byteBuf = new FriendlyByteBuf(writeBuffer);

        for (int i = 0; i < sectionCount; i++) {
            if (hiddenSections != null && hiddenSections.get(i)) {
                HiddenSectionWriter.write(byteBuf, world, sections[i], world.getSectionYFromSectionIndex(i));
            } else {
                sections[i].write(byteBuf, null, i);
            }
        }

        if (byteBuf.writerIndex() != totalSize) {
            return null;
        }
        return buffer;
    }

    private static void obfuscateNonHiddenSections(
        ServerLevel world,
        LevelChunkSection[] sections,
        int sectionCount,
        @Nullable BitSet hiddenSections,
        boolean copyOnWrite,
        @Nullable LevelChunkSection[] westSections,
        @Nullable LevelChunkSection[] eastSections,
        @Nullable LevelChunkSection[] northSections,
        @Nullable LevelChunkSection[] southSections
    ) {
        if (world.chunkPacketBlockController == ChunkPacketBlockController.NO_OPERATION_INSTANCE) {
            return;
        }

        Classification classification = classification(world);
        Set<Block> hiddenBlocksSet = classification.hiddenBlocks;
        Set<Block> replacementBlocksSet = classification.replacementBlocks;
        if (hiddenBlocksSet == null && replacementBlocksSet == null) {
            return;
        }
        boolean lavaObscures = classification.lavaObscures;

        for (int i = 0; i < sectionCount; i++) {
            if (hiddenSections != null && hiddenSections.get(i)) {
                continue;
            }
            LevelChunkSection section = sections[i];
            BlockState filler = HiddenSectionWriter.filler(world, world.getSectionYFromSectionIndex(i));
            if (section == null || !section.maybeHas(classification.paletteCheck(filler))) {
                continue;
            }

            boolean needsCopy = copyOnWrite;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state == filler) {
                            continue;
                        }
                        Block block = state.getBlock();
                        boolean replace = hiddenBlocksSet != null && hiddenBlocksSet.contains(block);
                        if (!replace && replacementBlocksSet != null && replacementBlocksSet.contains(block)) {
                            replace = isEnclosed(sections, i, sectionCount, x, y, z, lavaObscures, westSections, eastSections, northSections, southSections);
                        }
                        if (replace) {
                            if (needsCopy) {
                                // Publish the copy before mutation so subsequent enclosure checks see it.
                                section = section.copy();
                                sections[i] = section;
                                needsCopy = false;
                            }
                            section.setBlockState(x, y, z, filler, false);
                        }
                    }
                }
            }
        }
    }

    private static boolean isEnclosed(
        LevelChunkSection[] sections,
        int sectionIndex,
        int sectionCount,
        int x,
        int y,
        int z,
        boolean lavaObscures,
        @Nullable LevelChunkSection[] westSections,
        @Nullable LevelChunkSection[] eastSections,
        @Nullable LevelChunkSection[] northSections,
        @Nullable LevelChunkSection[] southSections
    ) {
        // Check X - 1 (West)
        BlockState westState;
        if (x > 0) {
            westState = sections[sectionIndex].getBlockState(x - 1, y, z);
        } else if (westSections != null && sectionIndex < westSections.length && westSections[sectionIndex] != null) {
            westState = westSections[sectionIndex].getBlockState(15, y, z);
        } else {
            return false; // Neighbor chunk not loaded / disk path without neighbor data -> treated as exposed
        }
        if (isTransparent(westState, lavaObscures)) {
            return false;
        }

        // Check X + 1 (East)
        BlockState eastState;
        if (x < 15) {
            eastState = sections[sectionIndex].getBlockState(x + 1, y, z);
        } else if (eastSections != null && sectionIndex < eastSections.length && eastSections[sectionIndex] != null) {
            eastState = eastSections[sectionIndex].getBlockState(0, y, z);
        } else {
            return false;
        }
        if (isTransparent(eastState, lavaObscures)) {
            return false;
        }

        // Check Z - 1 (North)
        BlockState northState;
        if (z > 0) {
            northState = sections[sectionIndex].getBlockState(x, y, z - 1);
        } else if (northSections != null && sectionIndex < northSections.length && northSections[sectionIndex] != null) {
            northState = northSections[sectionIndex].getBlockState(x, y, 15);
        } else {
            return false;
        }
        if (isTransparent(northState, lavaObscures)) {
            return false;
        }

        // Check Z + 1 (South)
        BlockState southState;
        if (z < 15) {
            southState = sections[sectionIndex].getBlockState(x, y, z + 1);
        } else if (southSections != null && sectionIndex < southSections.length && southSections[sectionIndex] != null) {
            southState = southSections[sectionIndex].getBlockState(x, y, 0);
        } else {
            return false;
        }
        if (isTransparent(southState, lavaObscures)) {
            return false;
        }

        // Check Y - 1 (down)
        BlockState downState;
        if (y > 0) {
            downState = sections[sectionIndex].getBlockState(x, y - 1, z);
        } else if (sectionIndex > 0 && sections[sectionIndex - 1] != null) {
            downState = sections[sectionIndex - 1].getBlockState(x, 15, z);
        } else {
            return false; // Below world -> exposed
        }
        if (isTransparent(downState, lavaObscures)) {
            return false;
        }

        // Check Y + 1 (up)
        BlockState upState;
        if (y < 15) {
            upState = sections[sectionIndex].getBlockState(x, y + 1, z);
        } else if (sectionIndex < sectionCount - 1 && sections[sectionIndex + 1] != null) {
            upState = sections[sectionIndex + 1].getBlockState(x, 0, z);
        } else {
            return false; // Above world -> exposed
        }
        return !isTransparent(upState, lavaObscures);
    }

    private static boolean isTransparent(@Nullable BlockState state, boolean lavaObscures) {
        if (state == null || state.isAir()) {
            return true;
        }
        if (lavaObscures && state.is(Blocks.LAVA)) {
            return false;
        }
        return !state.canOcclude() || !state.isSolidRender()
            || state.is(Blocks.BARRIER) || state.is(Blocks.SPAWNER) || state.is(Blocks.SHULKER_BOX) || state.is(Blocks.SLIME_BLOCK);
    }
}
