package org.dreeam.leaf.world.evd;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.util.ParallelSearchRadiusIteration;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.dreeam.leaf.config.modules.opt.ExtendedViewDistance;
import org.jspecify.annotations.Nullable;

/**
 * Per-player state for the extended view distance.
 * <p>
 * Chunks inside the regular send distance are handled by the Moonrise player chunk loader as usual. This tracker
 * covers the ring between the send distance and the far distance: those chunks are sent once as snapshots and are
 * never loaded, ticked or tracked. A chunk leaving the regular send area but staying inside the ring is kept on the
 * client instead of being forgotten, and a ring chunk entering the send area is replaced once the loader sends it.
 * <p>
 * Missing chunk candidates are iterated lazily via a cursor into the precomputed distance-ordered offset array
 * instead of rebuilding an intermediate candidate queue on every loader update.
 * <p>
 * All methods must be called on the tick thread of the player.
 */
public final class FarChunkTracker {

    private final ServerPlayer player;
    private final FarChunkManager manager;
    // chunks sent by the regular loader, owned by the loader
    private final LongOpenHashSet realSent;

    // snapshots currently on the client
    private final LongOpenHashSet farSent = new LongOpenHashSet();
    // snapshots requested from the manager and not yet delivered
    private final LongOpenHashSet requested = new LongOpenHashSet();
    // snapshots requested and dropped (e.g. ungenerated or unreadable) during the current update
    private final LongOpenHashSet droppedThisUpdate = new LongOpenHashSet();
    // true while synchronously executing manager.request(), so deliver() knows if a miss was synchronous
    private boolean inRequest;
    // precomputed relative chunk offsets ordered by euclidean distance, updated on loader update
    private long @Nullable [] iteration;
    // scan cursor into iteration, advanced lazily in tick()
    private int cursor;

    private int centerX = Integer.MIN_VALUE;
    private int centerZ = Integer.MIN_VALUE;
    private int sendDistance = -1;
    // 0 means inactive
    private int farDistance = 0;
    private boolean removed;

    public FarChunkTracker(final ServerPlayer player, final FarChunkManager manager, final LongOpenHashSet realSent) {
        this.player = player;
        this.manager = manager;
        this.realSent = realSent;
    }

    /**
     * @param clientViewDistance the view distance requested by the client, or -1 if unknown
     * @return the far distance for the player, or 0 if the extended view distance does not apply
     */
    public static int computeFarDistance(final ServerLevel world, final int clientViewDistance, final int sendViewDistance) {
        if (!ExtendedViewDistance.enabled || !FarChunkManager.isSupported(world)) {
            return 0;
        }
        // same +1 as Moonrise uses for the send distance
        final int target = clientViewDistance < 0
            ? ExtendedViewDistance.maxDistance
            : Math.min(ExtendedViewDistance.maxDistance, clientViewDistance + 1);
        return target > sendViewDistance ? target : 0;
    }

    /**
     * @return the chunk radius to tell the client, it must cover the ring or the client drops the snapshots
     */
    public static int clientRadius(final int sendViewDistance, final int farDistance) {
        return Math.max(sendViewDistance, farDistance);
    }

    public int getFarDistance() {
        return this.farDistance;
    }

    private boolean isWithinFar(final int chunkX, final int chunkZ) {
        return this.farDistance > 0 && ChunkTrackingView.isInViewDistance(this.centerX, this.centerZ, this.farDistance, chunkX, chunkZ);
    }

    // must match PlayerChunkLoaderData#wantChunkSent
    private boolean isRealWanted(final int chunkX, final int chunkZ) {
        final int dx = this.centerX - chunkX;
        final int dz = this.centerZ - chunkZ;
        return Math.max(Math.abs(dx), Math.abs(dz)) <= (this.sendDistance + 1)
            && ChunkTrackingView.isWithinDistance(this.centerX, this.centerZ, this.sendDistance, chunkX, chunkZ, true);
    }

    /**
     * Called by the loader before it updates its distance maps, so that unload callbacks see the new center.
     */
    public void beginUpdate(final int centerX, final int centerZ, final int sendDistance, final int farDistance) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.sendDistance = sendDistance;
        this.farDistance = farDistance;
    }

    /**
     * Called by the loader before it sends a forget packet for a chunk it no longer wants.
     *
     * @return {@code true} if the chunk stays on the client as a snapshot and the forget packet must not be sent
     */
    public boolean retainOnUnload(final int chunkX, final int chunkZ) {
        if (this.removed || !this.isWithinFar(chunkX, chunkZ) || this.isRealWanted(chunkX, chunkZ)) {
            return false;
        }
        this.farSent.add(CoordinateUtils.getChunkKey(chunkX, chunkZ));
        return true;
    }

    /**
     * Called by the loader after it sent a chunk, the live chunk replaces any snapshot on the client.
     */
    public void onRealChunkSent(final long key) {
        if (!this.farSent.isEmpty()) {
            this.farSent.remove(key);
        }
        if (!this.requested.isEmpty()) {
            this.requested.remove(key);
        }
    }

    /**
     * Called by the loader after it recalculated its own state and before it sends the new chunk center.
     */
    public void finishUpdate() {
        // forget snapshots that left the ring
        if (!this.farSent.isEmpty()) {
            for (final LongIterator iterator = this.farSent.iterator(); iterator.hasNext();) {
                final long key = iterator.nextLong();
                final int chunkX = CoordinateUtils.getChunkX(key);
                final int chunkZ = CoordinateUtils.getChunkZ(key);
                if (!this.isWithinFar(chunkX, chunkZ)) {
                    iterator.remove();
                    if (!this.realSent.contains(key)) {
                        this.sendForget(chunkX, chunkZ);
                    }
                }
            }
        }

        // late results for these are dropped in deliver
        if (!this.requested.isEmpty()) {
            for (final LongIterator iterator = this.requested.iterator(); iterator.hasNext();) {
                final long key = iterator.nextLong();
                final int chunkX = CoordinateUtils.getChunkX(key);
                final int chunkZ = CoordinateUtils.getChunkZ(key);
                if (!this.isWithinFar(chunkX, chunkZ) || this.isRealWanted(chunkX, chunkZ)) {
                    iterator.remove();
                }
            }
        }

        // keeps its capacity, so it stops allocating once warmed up; bounded by the ring size
        if (!this.droppedThisUpdate.isEmpty()) {
            this.droppedThisUpdate.clear();
        }

        this.cursor = 0;
        this.iteration = this.farDistance > 0 ? ParallelSearchRadiusIteration.getEuclideanIteration(this.farDistance) : null;
    }

    /**
     * Requests the next snapshots, limited by the per-player budget and the per-world read limit.
     */
    public void tick() {
        if (this.removed || this.farDistance <= 0 || this.iteration == null) {
            return;
        }

        final long[] iteration = this.iteration;
        int budget = ExtendedViewDistance.sendsPerTick;
        // cached misses (e.g. ungenerated chunks at the world edge) cost no budget, but bound the work per tick
        int misses = budget * 16;
        while (budget > 0 && this.cursor < iteration.length) {
            final long deltaChunk = iteration[this.cursor];
            final int chunkX = CoordinateUtils.getChunkX(deltaChunk) + this.centerX;
            final int chunkZ = CoordinateUtils.getChunkZ(deltaChunk) + this.centerZ;
            if (!this.isWithinFar(chunkX, chunkZ) || this.isRealWanted(chunkX, chunkZ)) {
                ++this.cursor;
                continue;
            }
            final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
            if (this.farSent.contains(key) || this.requested.contains(key) || this.realSent.contains(key) || this.droppedThisUpdate.contains(key)) {
                ++this.cursor;
                continue;
            }

            this.requested.add(key);
            final int result;
            this.inRequest = true;
            try {
                result = this.manager.request(this, key);
            } finally {
                this.inRequest = false;
            }
            if (result == FarChunkManager.BUSY) {
                this.requested.remove(key);
                break;
            }
            ++this.cursor;
            if (result == FarChunkManager.DELIVERED && !this.farSent.contains(key)) {
                if (--misses <= 0) {
                    break;
                }
                continue;
            }
            --budget;
        }
    }

    /**
     * Called by the manager with the snapshot, or {@code null} if the chunk cannot be sent (not generated, not
     * fully generated, unreadable).
     */
    void deliver(final long key, final @Nullable Packet<?> packet) {
        if (this.removed || !this.requested.remove(key)) {
            return;
        }
        if (packet == null) {
            if (!this.inRequest) {
                this.droppedThisUpdate.add(key);
            }
            return;
        }
        final int chunkX = CoordinateUtils.getChunkX(key);
        final int chunkZ = CoordinateUtils.getChunkZ(key);
        // the live chunk must never be overwritten by a snapshot
        if (this.realSent.contains(key) || !this.isWithinFar(chunkX, chunkZ) || this.isRealWanted(chunkX, chunkZ)) {
            return;
        }
        this.player.connection.send(packet);
        this.farSent.add(key);
    }

    /**
     * Called by the loader when it is removed. Forgets all snapshots, as the loader does for its own chunks.
     */
    public void remove() {
        if (this.removed) {
            return;
        }
        this.removed = true;
        for (final LongIterator iterator = this.farSent.iterator(); iterator.hasNext();) {
            final long key = iterator.nextLong();
            if (!this.realSent.contains(key)) {
                this.sendForget(CoordinateUtils.getChunkX(key), CoordinateUtils.getChunkZ(key));
            }
        }
        this.farSent.clear();
        this.requested.clear();
        this.droppedThisUpdate.clear();
        this.iteration = null;
        this.cursor = 0;
        this.inRequest = false;
    }

    private void sendForget(final int chunkX, final int chunkZ) {
        this.player.connection.send(new ClientboundForgetLevelChunkPacket(new ChunkPos(chunkX, chunkZ)));
    }
}
