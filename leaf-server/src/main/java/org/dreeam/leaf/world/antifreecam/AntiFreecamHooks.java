package org.dreeam.leaf.world.antifreecam;

import java.util.BitSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.dreeam.leaf.config.modules.misc.AntiFreecam;
import org.jspecify.annotations.Nullable;

/**
 * Entry points used by the Minecraft sources. Every method is a no-op when anti-freecam is disabled for the world.
 * <p>
 * All methods except {@link #isEntityHidden} must be called on the tick thread of the world.
 */
public final class AntiFreecamHooks {

    private AntiFreecamHooks() {}

    /**
     * Returns the manager of the world, creating or dropping it when the config enabled or disabled the world.
     * A manager that stays enabled is kept, it re-evaluates its players with the new config on its next tick.
     */
    public static @Nullable AntiFreecamManager manager(final ServerLevel level) {
        final int generation = AntiFreecam.generation;
        if (level.leaf$antiFreecamGeneration != generation) {
            level.leaf$antiFreecamGeneration = generation;
            final boolean enabled = AntiFreecam.isEnabled(level);
            final AntiFreecamManager current = level.leaf$antiFreecam;
            if (enabled && current == null) {
                final AntiFreecamManager created = new AntiFreecamManager(level);
                level.leaf$antiFreecam = created;
                adoptSentChunks(level, created);
            } else if (!enabled && current != null) {
                // cleared first, so the restoring resends are built without hidden sections
                level.leaf$antiFreecam = null;
                current.restoreAll();
                if (level.leaf$partialCulling != null) {
                    level.leaf$partialCulling.clear();
                }
            }
        }
        return level.leaf$antiFreecam;
    }

    /**
     * Registers the chunks players already have, so they are hidden like any other chunk once the delay expires.
     */
    private static void adoptSentChunks(final ServerLevel level, final AntiFreecamManager manager) {
        for (final ServerPlayer player : level.players()) {
            final ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader =
                ((ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer)player).moonrise$getChunkLoader();
            if (loader == null || player.level() != level) {
                continue;
            }
            for (final it.unimi.dsi.fastutil.longs.LongIterator iterator = loader.getSentChunksRaw().iterator(); iterator.hasNext();) {
                final long key = iterator.nextLong();
                manager.adoptChunk(player, ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(key), ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(key));
            }
        }
    }

    public static void tick(final ServerLevel level) {
        final AntiFreecamManager manager = manager(level);
        if (manager != null) {
            manager.tick();
            final PartialCulling partial = level.leaf$partialCulling;
            if (partial != null && partial.hasPendingChanges()) {
                resendChangedPartials(level, manager, partial);
            }
        }
    }

    /**
     * Players holding a partially culled section whose culled cells changed get the chunk again, once per chunk.
     */
    private static void resendChangedPartials(final ServerLevel level, final AntiFreecamManager manager, final PartialCulling partial) {
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntArrayList> changed = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        partial.processChanges((chunkX, sectionY, chunkZ) ->
            changed.computeIfAbsent(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunkX, chunkZ), k -> new it.unimi.dsi.fastutil.ints.IntArrayList(2)).add(sectionY));
        if (changed.isEmpty()) {
            return;
        }
        final java.util.List<ServerPlayer> players = level.players();
        // shares the configured world budget, the rest is kept for the next ticks
        int budget = AntiFreecam.maxResendsPerTick;
        for (final it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<it.unimi.dsi.fastutil.ints.IntArrayList> entry : changed.long2ObjectEntrySet()) {
            final int chunkX = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(entry.getLongKey());
            final int chunkZ = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(entry.getLongKey());
            final it.unimi.dsi.fastutil.ints.IntArrayList sections = entry.getValue();
            if (budget <= 0) {
                for (int s = 0, slen = sections.size(); s < slen; ++s) {
                    partial.requeue(chunkX, sections.getInt(s), chunkZ);
                }
                continue;
            }
            final LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                continue;
            }
            for (int i = 0, len = players.size(); i < len; ++i) {
                final ServerPlayer player = players.get(i);
                for (int s = 0, slen = sections.size(); s < slen; ++s) {
                    if (manager.isSectionPartial(player, chunkX, sections.getInt(s), chunkZ)) {
                        // a chunk that started is finished for every holder, so no holder keeps an outdated mask longer than needed
                        ChunkResender.resend(player, chunk);
                        --budget;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Called on the tick thread when the sky light of a section changed and is broadcast.
     */
    public static void onSkyLightChanged(final ServerLevel level, final int chunkX, final int sectionY, final int chunkZ) {
        final PartialCulling partial = level.leaf$partialCulling;
        if (partial != null && level.leaf$antiFreecam != null) {
            partial.onSkyLightChanged(chunkX, sectionY, chunkZ, level.getServer().getTickCount());
        }
    }

    /**
     * @return the sections to hide fully or per cell in the chunk packet that is about to be built for the player,
     * or {@code null}
     */
    public static @Nullable SendMasks onChunkSend(final ServerPlayer player, final LevelChunk chunk) {
        final AntiFreecamManager manager = manager((ServerLevel)chunk.getLevel());
        return manager == null ? null : manager.onChunkSend(player, chunk);
    }

    public static @Nullable BitSet hidden(final @Nullable SendMasks masks) {
        return masks == null ? null : masks.hidden();
    }

    public static @Nullable BitSet partial(final @Nullable SendMasks masks) {
        return masks == null ? null : masks.partial();
    }

    /**
     * Per-cell culling state of the world, created on first use. Only used while anti-freecam is enabled.
     */
    public static PartialCulling partialCulling(final ServerLevel level) {
        PartialCulling ret = level.leaf$partialCulling;
        if (ret == null) {
            level.leaf$partialCulling = ret = new PartialCulling(level);
        }
        return ret;
    }

    /**
     * @return {@code true} if the client of the player got the block at the position as filler of a partially culled section
     */
    public static boolean isCulledForPlayer(final ServerPlayer player, final int x, final int y, final int z) {
        final AntiFreecamManager manager = player.level().leaf$antiFreecam;
        return manager != null && manager.isSectionPartial(player, x >> 4, y >> 4, z >> 4) && partialCulling(player.level()).isCulled(x, y, z);
    }

    /**
     * Culled cell masks per block section index for the partial sections of a chunk packet, entries are {@code null}
     * where nothing is culled. Uses the same entries as the section serialization of the same tick.
     */
    public static long @Nullable [][] partialMasks(final LevelChunk chunk, final @Nullable BitSet partialSections) {
        if (partialSections == null || partialSections.isEmpty()) {
            return null;
        }
        final ServerLevel level = (ServerLevel)chunk.getLevel();
        final PartialCulling culling = partialCulling(level);
        final long tick = level.getServer().getTickCount();
        final int sectionCount = chunk.getSectionsCount();
        long[][] ret = null;
        for (int i = partialSections.nextSetBit(0); i >= 0 && i < sectionCount; i = partialSections.nextSetBit(i + 1)) {
            final long[] mask = culling.mask(chunk, i, tick);
            if (mask != null) {
                if (ret == null) {
                    ret = new long[sectionCount][];
                }
                ret[i] = mask;
            }
        }
        return ret;
    }

    public static void onChunkForget(final ServerLevel level, final ServerPlayer player, final int chunkX, final int chunkZ) {
        final AntiFreecamManager manager = level.leaf$antiFreecam;
        if (manager != null) {
            manager.onChunkForget(player, chunkX, chunkZ);
        }
    }

    public static void onPlayerRemoved(final ServerLevel level, final ServerPlayer player) {
        final AntiFreecamManager manager = level.leaf$antiFreecam;
        if (manager != null) {
            manager.onPlayerRemoved(player);
        }
    }

    public static void onChunkUnload(final ServerLevel level, final int chunkX, final int chunkZ) {
        final AntiFreecamManager manager = level.leaf$antiFreecam;
        if (manager != null) {
            manager.onChunkUnload(chunkX, chunkZ);
        }
        final PartialCulling partial = level.leaf$partialCulling;
        if (partial != null) {
            partial.onChunkUnload(chunkX, chunkZ);
        }
    }

    // hot path, called for every successful block change
    public static void onBlockChanged(final ServerLevel level, final int x, final int y, final int z, final BlockState oldState, final BlockState newState) {
        final AntiFreecamManager manager = level.leaf$antiFreecam;
        if (manager != null) {
            manager.onBlockChanged(x, y, z, oldState, newState);
            final PartialCulling partial = level.leaf$partialCulling;
            if (partial != null) {
                partial.onBlockChanged(x, y, z);
            }
        }
    }

    /**
     * @return {@code true} if packets about the given block position must not reach the player
     */
    public static boolean isHidden(final ServerPlayer player, final double x, final double y, final double z) {
        final AntiFreecamManager manager = player.level().leaf$antiFreecam;
        if (manager == null) {
            return false;
        }
        final int blockX = net.minecraft.util.Mth.floor(x);
        final int blockY = net.minecraft.util.Mth.floor(y);
        final int blockZ = net.minecraft.util.Mth.floor(z);
        return manager.isHidden(player, blockX, blockY, blockZ)
            || (manager.isSectionPartial(player, blockX >> 4, blockY >> 4, blockZ >> 4) && partialCulling(player.level()).isCulled(blockX, blockY, blockZ));
    }

    /**
     * Like {@link #isHidden(ServerPlayer, double, double, double)}, but only if sounds and particles are filtered.
     */
    public static boolean isEffectHidden(final ServerPlayer player, final double x, final double y, final double z) {
        return AntiFreecam.filterSoundsAndParticles && isHidden(player, x, y, z);
    }

    public static boolean isSectionHidden(final ServerPlayer player, final int chunkX, final int sectionY, final int chunkZ) {
        final AntiFreecamManager manager = player.level().leaf$antiFreecam;
        return manager != null && manager.isSectionHidden(player, chunkX, sectionY, chunkZ);
    }

    /**
     * Safe to call from any thread, including the multithreaded tracker.
     */
    public static boolean isEntityHidden(final ServerLevel level, final ServerPlayer viewer, final Entity entity) {
        final AntiFreecamManager manager = level.leaf$antiFreecam;
        return manager != null && AntiFreecam.entityCulling && manager.isEntityHidden(viewer, entity);
    }
}
