package org.dreeam.leaf.world.evd;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemLevelChunk;
import io.papermc.paper.antixray.ChunkPacketBlockController;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dreeam.leaf.config.modules.misc.AntiFreecam;
import org.dreeam.leaf.config.modules.opt.ExtendedViewDistance;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-world state for the extended view distance: a snapshot cache of chunk packets built from disk,
 * the set of disk reads currently in flight and the hand-off of finished packets back to the tick thread.
 * <p>
 * Every method except the IO/worker callbacks must be called on the tick thread.
 */
public final class FarChunkManager {

    private static final Logger LOGGER = LogManager.getLogger("Leaf/ExtendedViewDistance");

    public static final int DELIVERED = 0;
    public static final int PENDING = 1;
    public static final int BUSY = 2;

    private static volatile @Nullable ExecutorService executor;

    private final ServerLevel world;
    // access ordered: least recently used first
    private final Long2ObjectLinkedOpenHashMap<CacheEntry> cache = new Long2ObjectLinkedOpenHashMap<>();
    private final Long2ObjectOpenHashMap<InFlight> inFlight = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Completed> completed = new ConcurrentLinkedQueue<>();
    // snapshots of loaded chunks, shared between players for a short time; insertion ordered, oldest first
    private final Long2ObjectLinkedOpenHashMap<LiveEntry> liveCache = new Long2ObjectLinkedOpenHashMap<>();
    private long currentTick;
    private int liveBuildsThisTick;

    private static final int LIVE_CACHE_MAX_SIZE = 1024;
    private static final int EXPIRE_CHECKS_PER_TICK = 16;

    private record LiveEntry(Packet<?> packet, long createdTick, int generation, boolean antiFreecamEnabled) {}

    private record CacheEntry(@Nullable Packet<?> packet, long createdNanos, int generation, boolean antiFreecamEnabled) {}

    private record Completed(long key, @Nullable Packet<?> packet, boolean cacheable, int generation, boolean antiFreecamEnabled) {}

    private static final class InFlight {
        private final ReferenceArrayList<FarChunkTracker> waiters = new ReferenceArrayList<>(2);
        private boolean stale;
    }

    public FarChunkManager(final ServerLevel world) {
        this.world = world;
    }

    /**
     * Anti-Xray only obfuscates packets of loaded chunks, so snapshots from disk would leak ores
     * unless Anti-Freecam is enabled to hide subterranean sections and obfuscate ores.
     */
    public static boolean isSupported(final ServerLevel world) {
        return world.chunkPacketBlockController == ChunkPacketBlockController.NO_OPERATION_INSTANCE
            || AntiFreecam.isEnabled(world);
    }

    /**
     * Requests the snapshot packet for the given chunk on behalf of the tracker.
     *
     * @return {@link #DELIVERED} if {@link FarChunkTracker#deliver} was already invoked, {@link #PENDING} if it
     * will be invoked from a later {@link #tick()}, {@link #BUSY} if the read limit is reached and nothing was done
     */
    int request(final FarChunkTracker tracker, final long key) {
        final int chunkX = CoordinateUtils.getChunkX(key);
        final int chunkZ = CoordinateUtils.getChunkZ(key);

        // loaded chunks are newer than their saved data, so build from the live chunk
        final LevelChunk live = ((ChunkSystemLevel)this.world).moonrise$getFullChunkIfLoaded(chunkX, chunkZ);
        if (live != null && ((ChunkSystemLevelChunk)live).moonrise$isPostProcessingDone()) {
            final boolean antiFreecamEnabled = AntiFreecam.isEnabled(this.world);
            final int generation = AntiFreecam.generation;
            // a snapshot is never updated once sent, so one built a moment ago for another player is equivalent
            if (ExtendedViewDistance.liveCacheTicks > 0) {
                final LiveEntry entry = this.liveCache.get(key);
                if (entry != null) {
                    if (entry.generation() == generation && entry.antiFreecamEnabled() == antiFreecamEnabled
                            && this.currentTick - entry.createdTick() <= ExtendedViewDistance.liveCacheTicks) {
                        tracker.deliver(key, entry.packet());
                        return DELIVERED;
                    }
                    this.liveCache.remove(key);
                }
            }

            // building happens on the tick thread, bound the work per tick
            if (this.liveBuildsThisTick >= ExtendedViewDistance.liveBuildsPerTick) {
                return BUSY;
            }
            ++this.liveBuildsThisTick;

            final ClientboundLevelChunkWithLightPacket packet;
            if (antiFreecamEnabled) {
                packet = FarChunkPacketBuilder.buildLive(this.world, live);
            } else {
                packet = new ClientboundLevelChunkWithLightPacket(live, this.world.getLightEngine(), null, null, true);
            }
            if (packet != null) {
                if (ExtendedViewDistance.liveCacheTicks > 0) {
                    this.liveCache.putAndMoveToLast(key, new LiveEntry(packet, this.currentTick, generation, antiFreecamEnabled));
                    while (this.liveCache.size() > LIVE_CACHE_MAX_SIZE) {
                        this.liveCache.removeFirst();
                    }
                }
                tracker.deliver(key, packet);
                return DELIVERED;
            }
        }

        if (ExtendedViewDistance.cacheSize > 0) {
            final CacheEntry entry = this.cache.getAndMoveToLast(key);
            if (entry != null) {
                final int currentGen = AntiFreecam.generation;
                final boolean currentEnabled = AntiFreecam.isEnabled(this.world);
                if (entry.generation() == currentGen && entry.antiFreecamEnabled() == currentEnabled
                        && System.nanoTime() - entry.createdNanos() <= TimeUnit.SECONDS.toNanos(ExtendedViewDistance.cacheExpireSeconds)) {
                    tracker.deliver(key, entry.packet());
                    return DELIVERED;
                }
                this.cache.remove(key);
            }
        }

        InFlight pending = this.inFlight.get(key);
        if (pending != null) {
            // a tracker can re-request a key after dropping it, it must be delivered once
            if (!pending.waiters.contains(tracker)) {
                pending.waiters.add(tracker);
            }
            return PENDING;
        }

        if (this.inFlight.size() >= ExtendedViewDistance.maxConcurrentReads) {
            return BUSY;
        }

        pending = new InFlight();
        pending.waiters.add(tracker);
        this.inFlight.put(key, pending);
        this.submitRead(key);
        return PENDING;
    }

    private void submitRead(final long key) {
        final int chunkX = CoordinateUtils.getChunkX(key);
        final int chunkZ = CoordinateUtils.getChunkZ(key);
        final ServerLevel world = this.world;
        final ConcurrentLinkedQueue<Completed> completed = this.completed;
        try {
            MoonriseRegionFileIO.loadDataAsync(world, chunkX, chunkZ, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA,
                (final CompoundTag tag, final Throwable throwable) -> {
                    final int gen = AntiFreecam.generation;
                    final boolean enabled = AntiFreecam.isEnabled(world);
                    if (throwable != null) {
                        LOGGER.debug("Failed to read chunk ({}, {}) for extended view distance", chunkX, chunkZ, throwable);
                        completed.add(new Completed(key, null, false, gen, enabled));
                        return;
                    }
                    if (tag == null) {
                        // not generated yet
                        completed.add(new Completed(key, null, true, gen, enabled));
                        return;
                    }
                    try {
                        executor().execute(() -> {
                            Packet<?> packet = null;
                            boolean cacheable = true;
                            final int workerGen = AntiFreecam.generation;
                            final boolean workerEnabled = AntiFreecam.isEnabled(world);
                            try {
                                packet = FarChunkPacketBuilder.build(world, tag, chunkX, chunkZ);
                            } catch (final Throwable thr) {
                                // do not remember unexpected failures, the next request retries
                                cacheable = false;
                                LOGGER.debug("Failed to build chunk ({}, {}) for extended view distance", chunkX, chunkZ, thr);
                            }
                            completed.add(new Completed(key, packet, cacheable, workerGen, workerEnabled));
                        });
                    } catch (final RejectedExecutionException ex) {
                        completed.add(new Completed(key, null, false, gen, enabled));
                    }
                }, false, Priority.LOW);
        } catch (final Throwable thr) {
            LOGGER.debug("Failed to schedule read of chunk ({}, {}) for extended view distance", chunkX, chunkZ, thr);
            completed.add(new Completed(key, null, false, AntiFreecam.generation, AntiFreecam.isEnabled(world)));
        }
    }

    /**
     * Hands finished packets to the waiting trackers. Called once per tick before the players are ticked.
     */
    public void tick() {
        TickThread.ensureTickThread("Cannot tick far chunk manager async");
        ++this.currentTick;
        this.liveBuildsThisTick = 0;
        if (!ExtendedViewDistance.enabled && this.inFlight.isEmpty()) {
            if (!this.cache.isEmpty()) {
                this.cache.clear();
                this.cache.trim();
            }
            if (!this.liveCache.isEmpty()) {
                this.liveCache.clear();
                this.liveCache.trim();
            }
            return;
        }

        this.expireEntries();

        Completed done;
        while ((done = this.completed.poll()) != null) {
            final long key = done.key();
            final InFlight pending = this.inFlight.remove(key);
            if (pending == null) {
                continue;
            }

            if (pending.stale) {
                // the chunk was unloaded (and its save queued) while we were reading, read the new data
                final InFlight retry = new InFlight();
                retry.waiters.addAll(pending.waiters);
                this.inFlight.put(key, retry);
                this.submitRead(key);
                continue;
            }

            final int currentGen = AntiFreecam.generation;
            final boolean currentEnabled = AntiFreecam.isEnabled(this.world);
            if (done.generation() != currentGen || done.antiFreecamEnabled() != currentEnabled) {
                // Config changed while building: discard and re-request for waiting trackers
                final InFlight retry = new InFlight();
                retry.waiters.addAll(pending.waiters);
                this.inFlight.put(key, retry);
                this.submitRead(key);
                continue;
            }

            if (done.cacheable() && ExtendedViewDistance.cacheSize > 0) {
                this.cache.putAndMoveToLast(key, new CacheEntry(done.packet(), System.nanoTime(), done.generation(), done.antiFreecamEnabled()));
                while (this.cache.size() > ExtendedViewDistance.cacheSize) {
                    this.cache.removeFirst();
                }
            }

            final ReferenceArrayList<FarChunkTracker> waiters = pending.waiters;
            for (int i = 0, len = waiters.size(); i < len; ++i) {
                waiters.get(i).deliver(key, done.packet());
            }
        }
    }

    /**
     * Drops expired entries so packets nobody requests again are not retained until the cache is full.
     */
    private void expireEntries() {
        // insertion ordered, the oldest entries come first
        if (!this.liveCache.isEmpty()) {
            final int maxAge = ExtendedViewDistance.liveCacheTicks;
            while (!this.liveCache.isEmpty() && this.currentTick - this.liveCache.get(this.liveCache.firstLongKey()).createdTick() > maxAge) {
                this.liveCache.removeFirst();
            }
        }

        // access ordered, so creation times are only roughly ascending: check a few of the least recently used
        if (!this.cache.isEmpty()) {
            final long now = System.nanoTime();
            final long maxAge = TimeUnit.SECONDS.toNanos(ExtendedViewDistance.cacheExpireSeconds);
            for (int i = 0; i < EXPIRE_CHECKS_PER_TICK && !this.cache.isEmpty(); ++i) {
                if (now - this.cache.get(this.cache.firstLongKey()).createdNanos() <= maxAge) {
                    break;
                }
                this.cache.removeFirst();
            }
        }
    }

    /**
     * Called when a block of a loaded chunk changes, so that players requesting the chunk afterwards never get a
     * snapshot built before the change. Chunk edges also invalidate the neighbor, its ore obfuscation reads them.
     */
    public static void onBlockChanged(final ServerLevel world, final int blockX, final int blockZ) {
        if (!TickThread.isTickThread()) {
            return;
        }
        final FarChunkManager manager = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getPlayerChunkLoader().leaf$farChunkManager;
        if (manager.liveCache.isEmpty()) {
            return;
        }
        final int chunkX = blockX >> 4;
        final int chunkZ = blockZ >> 4;
        manager.liveCache.remove(CoordinateUtils.getChunkKey(chunkX, chunkZ));
        final int localX = blockX & 15;
        final int localZ = blockZ & 15;
        if (localX == 0) {
            manager.liveCache.remove(CoordinateUtils.getChunkKey(chunkX - 1, chunkZ));
        } else if (localX == 15) {
            manager.liveCache.remove(CoordinateUtils.getChunkKey(chunkX + 1, chunkZ));
        }
        if (localZ == 0) {
            manager.liveCache.remove(CoordinateUtils.getChunkKey(chunkX, chunkZ - 1));
        } else if (localZ == 15) {
            manager.liveCache.remove(CoordinateUtils.getChunkKey(chunkX, chunkZ + 1));
        }
    }

    /**
     * Called when a loaded chunk is marked unsaved: block entity data and light changes on the tick thread.
     * Light computed off the tick thread is not seen here, a snapshot may miss it for at most the cache time.
     */
    public static void onChunkChanged(final ServerLevel world, final int chunkX, final int chunkZ) {
        if (!TickThread.isTickThread()) {
            return;
        }
        final FarChunkManager manager = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getPlayerChunkLoader().leaf$farChunkManager;
        if (!manager.liveCache.isEmpty()) {
            manager.liveCache.remove(CoordinateUtils.getChunkKey(chunkX, chunkZ));
        }
    }

    /**
     * Called when a full chunk unloads. Its save is queued at this point, so later reads see the new data.
     */
    public void onChunkUnload(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        if (!this.cache.isEmpty()) {
            this.cache.remove(key);
        }
        if (!this.liveCache.isEmpty()) {
            this.liveCache.remove(key);
        }
        if (!this.inFlight.isEmpty()) {
            final InFlight pending = this.inFlight.get(key);
            if (pending != null) {
                pending.stale = true;
            }
        }
    }

    private static ExecutorService executor() {
        ExecutorService ret = executor;
        if (ret == null) {
            synchronized (FarChunkManager.class) {
                ret = executor;
                if (ret == null) {
                    final AtomicInteger threadId = new AtomicInteger();
                    final ThreadFactory factory = (final Runnable run) -> {
                        final Thread thread = new Thread(run, "Leaf Far Chunk Worker #" + threadId.incrementAndGet());
                        thread.setDaemon(true);
                        thread.setPriority(Thread.NORM_PRIORITY - 1);
                        thread.setUncaughtExceptionHandler((final Thread t, final Throwable thr) ->
                            LOGGER.error("Uncaught exception in {}", t.getName(), thr));
                        return thread;
                    };
                    executor = ret = Executors.newFixedThreadPool(Math.max(1, ExtendedViewDistance.workerThreads), factory);
                }
            }
        }
        return ret;
    }
}
