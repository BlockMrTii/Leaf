package org.dreeam.leaf.world.evd;

import ca.spottedleaf.moonrise.common.util.WorldUtil;
import ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.dreeam.leaf.config.modules.opt.ExtendedViewDistance;
import org.jspecify.annotations.Nullable;

public final class FarChunkPacketBuilder {

    private static final int BLOCK_ENTITY_LIMIT = Integer.getInteger("Paper.excessiveTELimit", 750);

    private FarChunkPacketBuilder() {}

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

            int totalSize = 0;
            for (int i = 0; i < sectionCount; i++) {
                if (sections[i] == null) {
                    sections[i] = new LevelChunkSection(world.palettedContainerFactory(), world, data.chunkPos(), world.getSectionYFromSectionIndex(i));
                }
                totalSize += sections[i].getSerializedSize();
            }

            byte[] buffer = new byte[totalSize];
            ByteBuf writeBuffer = Unpooled.wrappedBuffer(buffer);
            writeBuffer.writerIndex(0);
            FriendlyByteBuf byteBuf = new FriendlyByteBuf(writeBuffer);

            for (int i = 0; i < sectionCount; i++) {
                sections[i].write(byteBuf, null, i);
            }

            if (byteBuf.writerIndex() != totalSize) {
                return null;
            }

            // 5. Heightmaps (only client-bound types, cloned arrays)
            Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
            Map<Heightmap.Types, long[]> rawHeightmaps = data.heightmaps();
            if (rawHeightmaps != null) {
                for (Map.Entry<Heightmap.Types, long[]> entry : rawHeightmaps.entrySet()) {
                    if (entry.getKey().sendToClient() && entry.getValue() != null) {
                        heightmaps.put(entry.getKey(), entry.getValue().clone());
                    }
                }
            }

            // 6. Block Entities (only if configured, up to BLOCK_ENTITY_LIMIT)
            List<ClientboundLevelChunkPacketData.BlockEntityInfo> blockEntitiesData;
            List<CompoundTag> rawBlockEntities = data.blockEntities();
            if (ExtendedViewDistance.sendBlockEntities && rawBlockEntities != null && !rawBlockEntities.isEmpty()) {
                blockEntitiesData = new ArrayList<>();
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
                        LevelChunkSection section = sections[sectionIndex];
                        BlockState state = section.getBlockState(
                            SectionPos.sectionRelative(pos.getX()),
                            SectionPos.sectionRelative(pos.getY()),
                            SectionPos.sectionRelative(pos.getZ())
                        );

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
}
