package org.dreeam.leaf.world.redstone;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import org.dreeam.leaf.config.LeafConfig;
import org.dreeam.leaf.config.modules.opt.RedstoneLimiter;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Virtual chunk time, triggered by the tick time (MSPT) of the server. Per-world accounting on the world's tick thread; only
 * published statistics may be read from other threads.
 * <p>
 * As long as the smoothed MSPT stays below the threshold nothing is changed. Above it, all chunks with recent scheduled block ticks
 * (plus their neighbours) are grouped by connectivity, and every group gets its own clock. Scope "all": all groups run at one common
 * speed, lowered step by step while the MSPT is above the threshold and raised again once it is below the recovery limit. Scope
 * "hottest": every group has a speed of its own; the most expensive group is slowed first, down to min-speed, and only if the MSPT is
 * still above the threshold the next most expensive group is slowed, and so on. On recovery the groups are released in reverse order.
 * <p>
 * A chunk's own clock is the server tick minus its lag. Only chunks inside a group have a lag; a chunk that leaves its group is moved
 * back to the server clock at once (its pending ticks are shifted, the remaining delays stay the same), so nothing has to catch up.
 */
public final class RedstoneLimiterManager {

    // Weak references: the world owns its manager, an unloaded world must not be kept alive by this lookup table.
    private static final ConcurrentHashMap<String, WeakReference<RedstoneLimiterManager>> MANAGERS = new ConcurrentHashMap<>();

    // Tick duration of the whole server, published once per server tick by MinecraftServer (single writer: the main thread).
    // The sequence number is written last, a world that sees a new sequence also sees the matching (or a newer) duration.
    private static volatile long serverTickNanos;
    private static volatile long serverTickSeq;

    /** Called by the server at the end of every server tick with the duration of that tick (without the wait for the next tick). */
    public static void onServerTickEnd(long tickNanos) {
        serverTickNanos = tickNanos;
        serverTickSeq = serverTickSeq + 1L;
    }

    /**
     * The game time as seen by the clock of the chunk at {@code pos}: the server time minus the lag of its group. Things that are
     * stamped and compared in game time but belong to a block ticking on the chunk's clock must use this (e.g. redstone torch burnout).
     */
    public static long chunkTime(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        long time = level.getGameTime();
        final RedstoneLimiterManager limiter = level.redstoneBlockEntityLimiter;
        if (limiter != null && limiter.hasLag()) {
            time -= limiter.lagOf(ChunkPos.pack(pos));
        }
        return time;
    }

    public record Stats(
        boolean measuring,
        boolean throttling,
        double msptMs,
        double speed,
        double redstoneMs,
        int activeChunks,
        int throttledChunks,
        int groups,
        boolean hasHottest,
        int hottestChunkX,
        int hottestChunkZ,
        double hottestMs,
        int frozenChunks
    ) {
        public static final Stats EMPTY = new Stats(false, false, 0.0, 1.0, 0.0, 0, 0, 0, false, 0, 0, 0.0, 0);
    }

    private volatile Stats publishedStats = Stats.EMPTY;
    private long worldTickNanos;
    private double worldEmaMs;
    private int ticksSinceStats;

    private static final long ACTIVITY_WINDOW_TICKS = 100L; // a chunk counts as running redstone for this long after its last scheduled tick
    private static final long GROUP_REFRESH_TICKS = 5L; // the group set follows the activity, a moving machine must not outrun its group
    // The controller changes the speed slowly: the MSPT average lags behind the effect of a speed change, fast steps would oscillate.
    private static final double SPEED_STEP_DOWN = 0.004; // per tick, up to 5 times as much for a strong overload
    private static final double SPEED_STEP_UP = 0.002; // per tick
    private static final double MSPT_SAMPLE_LIMIT_FACTOR = 20.0; // only a huge outlier is cut: expensive executed ticks of a slowed group are real load
    private static final int MSPT_WARMUP_TICKS = 100;
    private static final int START_DELAY_TICKS = 20; // the smoothed MSPT must stay above the threshold this long, a single freeze (GC) must not start the slowdown
    private static final double SPEED_JITTER = 0.1; // +-10% on the speed of every group tick: an exactly periodic skip pattern would starve block entities that act on gameTime % N
    private final LongOpenHashSet remaining = new LongOpenHashSet();
    // Frozen zones: chunks whose group stands still completely until a redstone update reaches them (see updateFreeze)
    private final LongOpenHashSet frozenChunks = new LongOpenHashSet();
    private int playerActions; // nesting of player actions inside the level tick (digging in the player tick)
    private boolean inLevelTick; // between beginTick and endTick of the world: updates from machines, entities and clocks happen here
    private int freezeTimer; // consecutive ticks the smoothed MSPT stayed at or above the threshold while throttling
    private final LongArrayList queue = new LongArrayList();
    private Long2ObjectOpenHashMap<Group> memberSpare = new Long2ObjectOpenHashMap<>();
    private long lastGroupRebuild;

    private final String worldName;
    private final Long2ObjectOpenHashMap<ChunkState> chunks = new Long2ObjectOpenHashMap<>();
    private Long2ObjectOpenHashMap<Group> memberGroup = new Long2ObjectOpenHashMap<>();
    private final java.util.ArrayList<Group> groups = new java.util.ArrayList<>();
    // Intrusive pending list; only states touched in the current tick are queued.
    private ChunkState pending;
    private boolean groupsDirty;
    private int groupRadius = -1;
    private boolean wasVirtual;
    private boolean wasCascade; // scope of the last controller step, to carry the speed over when it is switched
    // MSPT controller
    private long lastServerSeq;
    private double msptEma;
    private int warmupTicks = MSPT_WARMUP_TICKS;
    private int overTicks; // consecutive ticks the smoothed MSPT was at or above the threshold while not throttling
    private boolean throttling; // the smoothed MSPT reached the threshold and has not recovered yet (also true in monitor-only)
    private double speed = 1.0; // common speed of all groups, 1.0 = real time
    private boolean loggedStart;
    private boolean tracking; // endTick has run with the limiter enabled, so there is something to clear when it is switched off
    private long jitterState = 0x9E3779B97F4A7C15L;
    // Virtual time: only chunks inside a group have a lag (server clock minus the chunk's own clock)
    private boolean hasLag;
    private boolean tickSlowBlockEntities; // slow-block-entities as of the start of this tick, so all decisions of one tick agree
    private final Long2LongOpenHashMap coupledUntil = new Long2LongOpenHashMap(); // chunks of a moved structure, kept as group members until then
    private Long2LongOpenHashMap pendingShift = new Long2LongOpenHashMap();
    private Long2LongOpenHashMap pendingFluidShift = new Long2LongOpenHashMap(); // the fluid tick scheduler is a second consumer of the same shifts
    private final LongArrayList shiftScratch = new LongArrayList();
    private boolean anySkipping;
    private long currentTick;
    private long lastSweep;
    private boolean hasSweep;
    private long lastLog;
    private boolean hasLog;

    public RedstoneLimiterManager(String worldName) {
        this.worldName = worldName;
        if (worldName != null) {
            MANAGERS.put(worldName, new WeakReference<>(this));
        }
    }

    public static @Nullable RedstoneLimiterManager forWorld(String worldName) {
        if (worldName == null) {
            return null;
        }
        final WeakReference<RedstoneLimiterManager> reference = MANAGERS.get(worldName);
        return reference == null ? null : reference.get();
    }

    public Stats stats() {
        return this.publishedStats;
    }

    /**
     * Start of a server tick of the world: decides which groups run in this tick (virtual time), so scheduled ticks, block events
     * and block entities of this tick all see the same decision and the same lag.
     */
    public void beginTick() {
        this.tickSlowBlockEntities = RedstoneLimiter.slowBlockEntities;
        this.inLevelTick = true;
        this.advanceVirtualClocks();
    }

    /** Trigger tick shifts (chunk key to delta) that follow a lag change of chunks, applied by the scheduled tick code. */
    public boolean hasPendingShifts() {
        return !this.pendingShift.isEmpty();
    }

    public boolean hasPendingFluidShifts() {
        return !this.pendingFluidShift.isEmpty();
    }

    private void addShift(long chunkKey, long delta) {
        this.pendingShift.addTo(chunkKey, delta);
        this.pendingFluidShift.addTo(chunkKey, delta);
    }

    public @Nullable Long2LongOpenHashMap takeFluidShifts() {
        if (this.pendingFluidShift.isEmpty()) {
            return null;
        }
        Long2LongOpenHashMap out = this.pendingFluidShift;
        this.pendingFluidShift = new Long2LongOpenHashMap();
        return out;
    }

    public @Nullable Long2LongOpenHashMap takeShifts() {
        if (this.pendingShift.isEmpty()) {
            return null;
        }
        Long2LongOpenHashMap out = this.pendingShift;
        this.pendingShift = new Long2LongOpenHashMap();
        return out;
    }

    /** True while the groups run on a slowed clock: the MSPT threshold is reached and the limiter is not in monitor-only mode. */
    private boolean isVirtual() {
        return RedstoneLimiter.enabled && !RedstoneLimiter.monitorOnly && this.throttling;
    }

    /** True while any chunk is behind real time, the scheduled tick code then converts times through {@link #lagOf}. */
    public boolean hasLag() {
        return this.hasLag;
    }

    /** Ticks the chunk's own clock is behind the server clock (0 for chunks outside of a group). */
    public long lagOf(long chunkKey) {
        Group group = this.memberGroup.get(chunkKey);
        return group != null ? group.lag : 0L;
    }

    /** True while some group skips the current server tick (virtual time). */
    public boolean isSkippingChunks() {
        return this.anySkipping;
    }

    public boolean skipsBlockEntity(net.minecraft.core.BlockPos pos) {
        if (pos == null || !this.anySkipping || !this.tickSlowBlockEntities) { // some tickers have no position
            return false;
        }
        Group group = this.memberGroup.get(net.minecraft.world.level.ChunkPos.pack(pos));
        return group != null && group.skip;
    }

    /**
     * True while some group skips this server tick, so the scheduled tick code has to ask for admission. The decision is taken at the
     * start of the tick and does not change within it, whatever happens to the config.
     */
    public boolean isLocalActive() {
        return this.anySkipping;
    }

    public boolean admitScheduledTick(long chunkKey, long gameTime) {
        if (!this.anySkipping) {
            return true;
        }
        Group group = this.memberGroup.get(chunkKey);
        if (group != null && group.skip) {
            this.markHeldBack(chunkKey, gameTime);
            return false;
        }
        return true;
    }

    public boolean mayRunBlockEvents(long chunkKey, long gameTime) {
        if (!this.anySkipping) {
            return true;
        }
        Group group = this.memberGroup.get(chunkKey);
        if (group != null && group.skip) {
            this.markHeldBack(chunkKey, gameTime);
            return false;
        }
        return true;
    }

    /**
     * A chunk that still has work but is held back by the limiter stays active. Otherwise it would drop out of its group after the
     * activity window and let its pending work run on the server clock. Only called on rejection, so the lookup and the rare state
     * creation stay off the admitted fast path.
     */
    private void markHeldBack(long chunkKey, long gameTime) {
        ChunkState held = this.chunks.get(chunkKey);
        if (held == null) {
            held = new ChunkState(chunkKey);
            this.chunks.put(chunkKey, held);
        }
        held.lastActivity = gameTime;
    }

    public void record(long chunkKey, long measuredNanos) {
        long nanos = Math.max(1L, measuredNanos); // a measurement below the timer resolution still counts as activity
        if (!RedstoneLimiter.enabled) {
            return;
        }
        this.worldTickNanos += Math.min(nanos, Long.MAX_VALUE - this.worldTickNanos);
        ChunkState state = this.chunks.get(chunkKey);
        if (state == null) {
            // first contact: one small allocation per chunk, only for chunks that actually ran scheduled ticks
            state = new ChunkState(chunkKey);
            this.chunks.put(chunkKey, state);
        }
        state.tickNanos += Math.min(nanos, Long.MAX_VALUE - state.tickNanos);
        this.enqueue(state);
    }

    public void endTick(long gameTime) {
        RedstoneBarTask.ensureStarted();
        this.inLevelTick = false;
        this.currentTick = gameTime == Long.MAX_VALUE ? Long.MAX_VALUE : gameTime + 1;
        if (!RedstoneLimiter.enabled) {
            this.clearTracking();
            return;
        }
        this.tracking = true;
        this.updateMspt();
        this.updateController(gameTime);
        this.updateFreeze(gameTime);
        boolean virtual = this.isVirtual();
        // virtual time runs a group in 1 of N ticks, the cost per server tick is bursty (full tick, then nothing) with a period of up
        // to 1/minSpeed ticks, so the displayed load needs a window that covers several periods
        this.worldEmaMs += (!this.groups.isEmpty() && virtual ? 0.03 : 0.2) * (this.worldTickNanos / 1_000_000.0 - this.worldEmaMs);
        this.worldTickNanos = 0;
        int window = Math.max(1, RedstoneLimiter.emaWindowTicks);
        double alpha = 2.0 / (window + 1.0);
        double decay = 1.0 - alpha;
        int radius = Math.clamp(RedstoneLimiter.neighborRadius, 0, 2);
        if (virtual != this.wasVirtual || radius != this.groupRadius) {
            this.groupsDirty = true;
        }
        this.wasVirtual = virtual;
        this.groupRadius = radius;

        ChunkState state = this.pending;
        while (state != null) {
            ChunkState next = state.next;
            // virtual time: a tick the chunk's group skips has no cost by design, so it must not enter the average
            Group chunkGroup = virtual || !this.frozenChunks.isEmpty() ? this.memberGroup.get(state.chunkKey) : null;
            if (chunkGroup != null && chunkGroup.skip) {
                state.tickNanos = 0;
                state.lastUpdate = gameTime;
                state.hasUpdate = true;
            } else {
                boolean active = state.tickNanos > 0;
                long chunkElapsed = state.hasUpdate ? elapsed(gameTime, state.lastUpdate) : 1;
                state.emaMs = updateEma(state.emaMs, state.tickNanos, chunkElapsed, alpha, decay);
                state.tickNanos = 0;
                state.lastUpdate = gameTime;
                state.hasUpdate = true;
                if (active) {
                    state.lastActivity = gameTime;
                }
            }
            // the state stays in the map (not queued) so its activity can still count for the groups, the sweep removes it later
            this.unlink(state);
            state = next;
        }
        // chunk activity changes continuously, so the groups follow it regularly while the clocks are slowed
        if (virtual && elapsed(gameTime, this.lastGroupRebuild) >= GROUP_REFRESH_TICKS) {
            this.groupsDirty = true;
        }
        if (this.groupsDirty) {
            this.rebuildGroups(gameTime, virtual);
        }
        Stats previousStats = this.publishedStats;
        if (++this.ticksSinceStats >= 10 || !previousStats.measuring() || previousStats.throttling() != this.throttling) {
            this.publishStats(gameTime);
            this.ticksSinceStats = 0;
        }
        if (!this.hasSweep || elapsed(gameTime, this.lastSweep) >= 1200) {
            this.lastSweep = gameTime;
            this.hasSweep = true;
            // keep chunk data at least as long as the longest activity window (slowest speed) plus a group refresh, otherwise the groups lose their data
            long retention = Math.max((long) window * 4, (long) Math.ceil(ACTIVITY_WINDOW_TICKS / minSpeed()) + GROUP_REFRESH_TICKS + 1);
            var iterator = this.chunks.long2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                ChunkState candidate = iterator.next().getValue();
                if (!candidate.queued && elapsed(gameTime, candidate.lastActivity) >= retention && !this.frozenChunks.contains(candidate.chunkKey)) {
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Smoothed tick time of the SERVER (what /mspt shows, all worlds and the work around them): one sample per finished server tick.
     * A world that is ticked once per server tick sees every new sample once, the sample is the tick before the current one.
     */
    private void updateMspt() {
        final long seq = serverTickSeq;
        if (seq == this.lastServerSeq) {
            return; // no new server tick since the last sample (e.g. frozen ticks)
        }
        this.lastServerSeq = seq;
        if (this.warmupTicks > 0) {
            --this.warmupTicks; // world load and the first ticks after enabling are no sample
            return;
        }
        // Only a huge outlier is cut: the expensive executed ticks of a slowed group are real load and must stay in the signal
        double limit = RedstoneLimiter.msptThreshold * MSPT_SAMPLE_LIMIT_FACTOR;
        double sample = Math.min(serverTickNanos / 1_000_000.0, limit);
        double alpha = 2.0 / (Math.max(1, RedstoneLimiter.emaWindowTicks) + 1.0);
        this.msptEma += alpha * (sample - this.msptEma);
    }

    /**
     * Starts the slowdown when the smoothed MSPT reaches the threshold. While it is above, the speed falls (faster the larger the
     * overload; scope "all": the common speed, scope "hottest": the speed of the most expensive group that is not at min-speed yet),
     * between the recovery limit and the threshold it is held, below the recovery limit it rises until real time is reached and the
     * groups are released.
     */
    /** Carries the speed over when the scope was switched (config reload); called before the first clock decision with the new scope. */
    private void syncScope() {
        final boolean cascade = RedstoneLimiter.hottestScope;
        if (cascade != this.wasCascade) {
            this.wasCascade = cascade;
            if (cascade) {
                // "all" -> "hottest": the groups take over the common speed, otherwise they would all run at full speed at once
                final double common = Math.clamp(this.speed, minSpeed(), 1.0);
                for (int i = 0; i < this.groups.size(); ++i) {
                    this.groups.get(i).speed = common;
                }
            } else {
                this.updateLowestSpeed(); // "hottest" -> "all": the slowest group sets the common speed
            }
        }
    }

    private void updateController(long gameTime) {
        this.syncScope();
        double threshold = RedstoneLimiter.msptThreshold;
        double recovery = Double.isFinite(RedstoneLimiter.msptRecoveryRatio) ? Math.clamp(RedstoneLimiter.msptRecoveryRatio, 0.5, 0.99) : 0.9;
        if (minSpeed() >= 1.0) {
            // a minimum speed of 1.0 means no slowdown is possible: no groups that skip nothing
            this.overTicks = 0;
            if (this.throttling) {
                this.release();
            }
            return;
        }
        if (this.msptEma >= threshold) {
            if (!this.throttling) {
                if (++this.overTicks < START_DELAY_TICKS) {
                    return; // only a sustained overload starts the slowdown, not a single freeze
                }
                this.throttling = true;
                this.groupsDirty = true;
                this.logStart(gameTime);
            }
            double overload = Math.min(1.0, this.msptEma / threshold - 1.0);
            double step = SPEED_STEP_DOWN * (1.0 + 4.0 * overload);
            if (RedstoneLimiter.hottestScope) {
                this.cascadeDown(step);
            } else {
                this.speed = Math.max(minSpeed(), this.speed - step);
            }
        } else {
            this.overTicks = 0;
            if (this.throttling) {
                if (RedstoneLimiter.hottestScope) {
                    if (this.cascadeUp(this.msptEma < threshold * recovery)) {
                        this.release();
                    }
                    return;
                }
                this.speed = Math.max(minSpeed(), this.speed); // a raised min-speed (config reload) takes effect right away
                if (this.msptEma < threshold * recovery) {
                    this.speed = Math.min(1.0, this.speed + SPEED_STEP_UP);
                    if (this.speed >= 1.0) {
                        this.release();
                    }
                }
            }
        }
    }

    /**
     * Scope "hottest", MSPT above the threshold: slows the most expensive group that is not at min-speed yet by one step. A group that
     * is already on its way down continues first (the slowest of them), whatever its smoothed cost does meanwhile, because the costs
     * of slowed and unslowed groups are not comparable. Only when it has reached min-speed the next most expensive group starts, which
     * is the threshold between the regions. Frozen groups are left alone.
     */
    private void cascadeDown(double step) {
        final double min = minSpeed();
        Group target = null;
        boolean targetSlowed = false;
        double best = 0.0; // a group without measured cost is not worth slowing
        for (int i = 0; i < this.groups.size(); ++i) {
            Group group = this.groups.get(i);
            if (group.frozen) {
                continue;
            }
            group.speed = Math.clamp(group.speed, min, 1.0); // a raised min-speed (config reload) takes effect right away
            if (group.speed <= min) {
                continue;
            }
            if (group.speed < 1.0) {
                // a group that is already on its way down continues first (the slowest of them), whatever its smoothed cost does in the meantime
                if (!targetSlowed || group.speed < target.speed) {
                    target = group;
                    targetSlowed = true;
                }
            } else if (!targetSlowed) {
                double rank = group.cost;
                if (rank > best) {
                    best = rank;
                    target = group;
                }
            }
        }
        if (target != null) {
            target.speed = Math.max(min, target.speed - step);
        }
        this.updateLowestSpeed();
    }

    /**
     * Scope "hottest", MSPT below the threshold: while it is below the recovery limit the cheapest slowed group is raised by one step,
     * so the groups are released in the reverse order of the slowdown. Returns true once no group is slowed any more (end of the slowdown).
     */
    private boolean cascadeUp(boolean recovering) {
        final double min = minSpeed();
        Group target = null;
        double bestSpeed = -1.0;
        double bestCost = Double.MAX_VALUE;
        for (int i = 0; i < this.groups.size(); ++i) {
            Group group = this.groups.get(i);
            if (group.frozen) {
                continue;
            }
            group.speed = Math.clamp(group.speed, min, 1.0);
            if (group.speed >= 1.0) {
                continue;
            }
            // the group that is on its way up continues first (the fastest slowed one), otherwise the cheapest one at min-speed starts
            double cost = group.cost / group.speed;
            if (group.speed > bestSpeed || (group.speed == bestSpeed && cost < bestCost)) {
                bestSpeed = group.speed;
                bestCost = cost;
                target = group;
            }
        }
        if (target == null) {
            return true; // nothing is slowed, so nothing is held: the slowdown ends even if the MSPT is still in the band below the threshold
        }
        if (recovering) {
            target.speed = Math.min(1.0, target.speed + SPEED_STEP_UP);
        }
        this.updateLowestSpeed();
        return false;
    }

    /** Scope "hottest": the common speed field shows the slowest group (statistics, activity window). */
    private void updateLowestSpeed() {
        final double min = minSpeed();
        double lowest = 1.0;
        for (int i = 0; i < this.groups.size(); ++i) {
            Group group = this.groups.get(i);
            if (!group.frozen) {
                lowest = Math.min(lowest, Math.clamp(group.speed, min, 1.0));
            }
        }
        this.speed = lowest;
    }

    /** End of the slowdown: the groups are dissolved by the next rebuild and every chunk goes back to the server clock. */
    private void release() {
        this.speed = 1.0;
        this.throttling = false;
        this.groupsDirty = true;
        this.logEnd();
    }

    /**
     * How long a chunk counts as running redstone after its last scheduled tick. Measured in server ticks, but a slowed chunk executes
     * a scheduled tick only every 1/speed server ticks, so the window grows with the slowdown (otherwise a slow machine would drop out
     * of its group between two of its own ticks).
     */
    private long activityWindow() {
        if (!this.isVirtual()) {
            return ACTIVITY_WINDOW_TICKS;
        }
        return (long) Math.ceil(ACTIVITY_WINDOW_TICKS / Math.clamp(this.speed, minSpeed(), 1.0));
    }

    /** Random factor around 1.0 (xorshift, deterministic, no allocation). */
    private double nextJitter() {
        long x = this.jitterState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        this.jitterState = x;
        return 1.0 - SPEED_JITTER + 2.0 * SPEED_JITTER * ((x >>> 11) * 0x1.0p-53);
    }

    private static double minSpeed() {
        return Double.isFinite(RedstoneLimiter.minSpeed) ? Math.clamp(RedstoneLimiter.minSpeed, 0.02, 1.0) : 0.1;
    }

    private void publishStats(long gameTime) {
        int active = 0;
        boolean hasHottest = false;
        long hottestChunk = 0;
        double hottestMs = 0.0;
        final long window = this.activityWindow();
        var iterator = this.chunks.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            ChunkState candidate = iterator.next().getValue();
            if (elapsed(gameTime, candidate.lastActivity) <= window && !this.frozenChunks.contains(candidate.chunkKey)) { // a frozen chunk is not running, it is shown as FRZ
                ++active;
                if (candidate.emaMs > hottestMs) {
                    hasHottest = true;
                    hottestChunk = candidate.chunkKey;
                    hottestMs = candidate.emaMs;
                }
            }
        }
        int throttled = this.memberGroup.size();
        if (RedstoneLimiter.hottestScope) {
            // scope "hottest": only the chunks of groups that are really slowed (the others are just grouped and run at full speed)
            throttled = 0;
            var members = this.memberGroup.values().iterator();
            while (members.hasNext()) {
                Group member = members.next();
                if (member.speed < 1.0 && !member.frozen) {
                    ++throttled;
                }
            }
        }
        this.publishedStats = new Stats(true, this.throttling, this.msptEma, this.speed, this.worldEmaMs, active, throttled,
            this.groups.size(), hasHottest, ChunkPos.getX(hottestChunk), ChunkPos.getZ(hottestChunk), hottestMs, this.frozenChunks.size());
    }

    private static boolean freezeActive() {
        return RedstoneLimiter.enabled && !RedstoneLimiter.monitorOnly && RedstoneLimiter.freezeEnabled && minSpeed() < 1.0;
    }

    /**
     * Freezing: when the slowdown cannot relieve the server (the smoothed MSPT stays at or above the threshold for freeze-after-seconds
     * while throttling), the hottest group is stopped completely. The timer then starts again, so the next zone is only frozen if the
     * server is still overloaded after another period. A frozen zone stays frozen even after the slowdown ended, until a redstone
     * update reaches one of its chunks ({@link #onNeighborUpdate}).
     */
    private void updateFreeze(long gameTime) {
        if (!freezeActive()) {
            this.freezeTimer = 0;
            if (!this.frozenChunks.isEmpty()) {
                this.frozenChunks.clear(); // switched off (or limiter off): the next rebuild lets the zones run on their normal clock
                this.groupsDirty = true;
            }
            return;
        }
        if (!this.throttling) {
            this.freezeTimer = 0;
            return;
        }
        if (this.msptEma >= RedstoneLimiter.msptThreshold) {
            if (++this.freezeTimer >= Math.max(1, RedstoneLimiter.freezeAfterSeconds) * 20) {
                this.freezeTimer = 0;
                this.freezeHottestGroup(gameTime);
            }
        } else {
            this.freezeTimer = 0; // only an uninterrupted overload freezes a zone
        }
    }

    private void freezeHottestGroup(long gameTime) {
        if (this.groups.isEmpty()) {
            return;
        }
        // cost of a group = sum of the smoothed cost of its chunks (those that ran scheduled ticks lately)
        var costs = new java.util.IdentityHashMap<Group, double[]>();
        var members = this.memberGroup.long2ObjectEntrySet().fastIterator();
        while (members.hasNext()) {
            var entry = members.next();
            Group group = entry.getValue();
            if (group.frozen) {
                continue;
            }
            ChunkState state = this.chunks.get(entry.getLongKey());
            if (state != null) {
                costs.computeIfAbsent(group, ignored -> new double[1])[0] += state.emaMs;
            }
        }
        Group hottest = null;
        double hottestCost = 0.0;
        final boolean cascade = RedstoneLimiter.hottestScope;
        for (var cost : costs.entrySet()) {
            Group candidate = cost.getKey();
            double candidateCost = cost.getValue()[0];
            // scope "hottest": the cascade slowed the most expensive group first, so the slowest one is the hottest (the smoothed costs of
            // slowed and unslowed groups are not comparable, a slowed group runs only every n-th tick); ties go to the higher cost
            boolean better = cascade
                ? hottest == null || candidate.speed < hottest.speed || (candidate.speed == hottest.speed && candidateCost > hottestCost)
                : candidateCost > hottestCost;
            if (better) {
                hottest = candidate;
                hottestCost = candidateCost;
            }
        }
        if (hottest == null) {
            return;
        }
        int count = 0;
        long center = 0L;
        double centerMs = -1.0;
        members = this.memberGroup.long2ObjectEntrySet().fastIterator();
        while (members.hasNext()) {
            var entry = members.next();
            if (entry.getValue() == hottest) {
                this.frozenChunks.add(entry.getLongKey());
                ++count;
                ChunkState state = this.chunks.get(entry.getLongKey());
                if (state != null && state.emaMs > centerMs) {
                    centerMs = state.emaMs;
                    center = entry.getLongKey();
                }
            }
        }
        hottest.frozen = true;
        if (RedstoneLimiter.logWarnings) {
            LeafConfig.LOGGER.warn("Redstone limiter: world={}, froze a zone of {} chunks around chunk [{}, {}] ({} ms), it runs again after a redstone update",
                this.worldName, count, ChunkPos.getX(center), ChunkPos.getZ(center), hottestCost);
        }
    }

    /**
     * A redstone update (a block that is a signal source changed next to {@code pos}) thaws the frozen zone the position belongs to:
     * a player flipping a lever or placing a redstone block, an observer or the like. The zone then runs again like every other
     * group and can be frozen again after the next freeze period. Cheap while nothing is frozen.
     */
    public void onNeighborUpdate(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block source) {
        // Only updates from outside the world tick thaw: a player's click on a lever or button, a block placed or broken, a command or a
        // plugin. Updates during the tick come from clocks, minecarts or mobs and must not thaw a zone again right after it was frozen.
        if ((this.inLevelTick && this.playerActions == 0) || this.frozenChunks.isEmpty() || !source.defaultBlockState().isSignalSource()) {
            return;
        }
        // the update also reaches the neighbours of pos, which can lie in the next chunk
        this.thaw(ChunkPos.pack(pos));
        if (this.frozenChunks.isEmpty()) {
            return;
        }
        final int x = pos.getX();
        final int z = pos.getZ();
        if ((x & 15) == 0) {
            this.thaw(ChunkPos.pack((x - 1) >> 4, z >> 4));
        } else if ((x & 15) == 15) {
            this.thaw(ChunkPos.pack((x + 1) >> 4, z >> 4));
        }
        if ((z & 15) == 0) {
            this.thaw(ChunkPos.pack(x >> 4, (z - 1) >> 4));
        } else if ((z & 15) == 15) {
            this.thaw(ChunkPos.pack(x >> 4, (z + 1) >> 4));
        }
    }

    public void enterPlayerAction() {
        ++this.playerActions;
    }

    public void leavePlayerAction() {
        if (this.playerActions > 0) {
            --this.playerActions;
        }
    }

    private void thaw(long key) {
        Group group = this.memberGroup.get(key);
        if (group == null || !group.frozen) {
            return;
        }
        group.frozen = false;
        var members = this.memberGroup.long2ObjectEntrySet().fastIterator();
        while (members.hasNext()) {
            var entry = members.next();
            if (entry.getValue() == group) {
                this.frozenChunks.remove(entry.getLongKey());
            }
        }
        this.freezeTimer = 0;
        this.groupsDirty = true; // without the slowdown the zone's group is dissolved by the rebuild (its ticks return to the server clock)
        if (RedstoneLimiter.logWarnings) {
            LeafConfig.LOGGER.info("Redstone limiter: world={}, a redstone update at chunk [{}, {}] thawed a frozen zone",
                this.worldName, ChunkPos.getX(key), ChunkPos.getZ(key));
        }
    }

    /**
     * Virtual time: decides for the NEXT server tick whether a group runs or is skipped. Scope "all": all groups share one speed, scope
     * "hottest": every group has its own; a fractional
     * accumulator spreads the skipped ticks evenly and each group starts at a phase of its own. A skipped tick adds one tick of lag
     * so the group's own clock (server tick minus lag) stands still.
     */
    private void advanceVirtualClocks() {
        boolean virtual = this.isVirtual();
        boolean skipping = false;
        boolean lagging = false;
        this.syncScope();
        final boolean cascade = RedstoneLimiter.hottestScope;
        double groupSpeed = Math.clamp(this.speed, minSpeed(), 1.0);
        for (int i = 0; i < this.groups.size(); ++i) {
            Group group = this.groups.get(i);
            if (cascade) {
                groupSpeed = Math.clamp(group.speed, minSpeed(), 1.0); // scope "hottest": every group has a speed of its own
            }
            if (group.frozen) {
                group.skip = true; // the clock of a frozen zone stands still, also after the slowdown ended
                ++group.lag;
                skipping = true;
            } else if (virtual) {
                // at full speed nothing is skipped, below it the speed is dithered (see SPEED_JITTER) so that the groups do not always
                // run in the same residue of the game time
                group.phase += groupSpeed >= 1.0 ? 1.0 : groupSpeed * this.nextJitter();
                if (group.phase >= 1.0) {
                    group.phase = Math.min(group.phase - 1.0, 1.0);
                    group.skip = false;
                } else {
                    group.skip = true;
                    ++group.lag;
                    skipping = true;
                }
            } else {
                group.skip = false;
            }
            lagging |= group.lag > 0;
        }
        this.anySkipping = skipping;
        this.hasLag = lagging;
    }

    private void rebuildGroups(long gameTime, boolean virtual) {
        this.groupsDirty = false;
        this.remaining.clear();
        if (virtual) {
            // every chunk with recent scheduled ticks (or held back work) is a seed, connected seeds form one group
            final long window = this.activityWindow();
            var seeds = this.chunks.long2ObjectEntrySet().fastIterator();
            while (seeds.hasNext()) {
                ChunkState seed = seeds.next().getValue();
                if (elapsed(gameTime, seed.lastActivity) <= window && !this.frozenChunks.contains(seed.chunkKey)) { // frozen chunks are added below
                    this.addNeighbors(seed.chunkKey, this.groupRadius);
                }
            }
            if (!this.coupledUntil.isEmpty()) {
                var coupled = this.coupledUntil.long2LongEntrySet().fastIterator();
                while (coupled.hasNext()) {
                    var entry = coupled.next();
                    if (entry.getLongValue() < gameTime) {
                        coupled.remove();
                    } else {
                        this.remaining.add(entry.getLongKey());
                    }
                }
            }
        } else {
            this.coupledUntil.clear();
        }
        if (!this.frozenChunks.isEmpty()) {
            // frozen zones are kept as they are, whether the slowdown is on or not
            var frozen = this.frozenChunks.iterator();
            while (frozen.hasNext()) {
                this.remaining.add(frozen.nextLong());
            }
        }
        this.lastGroupRebuild = gameTime;
        final boolean hottest = RedstoneLimiter.hottestScope;
        Long2ObjectOpenHashMap<Group> replacement = this.memberSpare; // reused, no allocation per rebuild
        replacement.clear();
        this.groups.clear();
        while (!this.remaining.isEmpty()) {
            long first = this.remaining.iterator().nextLong();
            this.remaining.remove(first);
            this.queue.clear();
            this.queue.add(first);
            Group group = new Group();
            // a new group starts at a phase of its own, so several groups do not all execute in the same server tick
            group.phase = (double) ((first * 0x9E3779B97F4A7C15L) >>> 11) / (double) (1L << 53);
            boolean phaseInherited = false;
            for (int index = 0; index < this.queue.size(); ++index) {
                long key = this.queue.getLong(index);
                replacement.put(key, group);
                if (this.frozenChunks.contains(key)) {
                    group.frozen = true; // a group that contains a frozen chunk is frozen as a whole
                }
                // a chunk joining the group takes the highest delay of the members, so the group clock never runs ahead of any member;
                // the chunk's pending ticks are shifted by the difference afterwards (remaining own ticks stay the same)
                Group previous = this.memberGroup.get(key);
                long ownLag = previous != null ? previous.lag : 0L; // outside of a group a chunk is on the server clock
                if (previous != null) {
                    group.speed = Math.min(group.speed, previous.speed); // a group keeps the slowest speed of its members (scope "hottest")
                }
                if (hottest) {
                    ChunkState costState = this.chunks.get(key);
                    if (costState != null) {
                        group.cost += costState.emaMs;
                    }
                }
                this.shiftScratch.add(key);
                this.shiftScratch.add(ownLag);
                group.lag = Math.max(group.lag, ownLag);
                if (previous != null && !phaseInherited) {
                    group.phase = previous.phase;
                    group.skip = previous.skip;
                    phaseInherited = true;
                }
                long x = ChunkPos.getX(key);
                long z = ChunkPos.getZ(key);
                for (int dx = -1; dx <= 1; ++dx) {
                    for (int dz = -1; dz <= 1; ++dz) {
                        if (validCoordinate(x + dx) && validCoordinate(z + dz)) {
                            long neighbor = ChunkPos.pack((int) (x + dx), (int) (z + dz));
                            if (this.remaining.remove(neighbor)) {
                                this.queue.add(neighbor);
                            }
                        }
                    }
                }
            }
            if (group.frozen) {
                // a group that merged with a frozen one is frozen as a whole, so all its members stay frozen when the slowdown ends
                for (int index = 0; index < this.queue.size(); ++index) {
                    this.frozenChunks.add(this.queue.getLong(index));
                }
            }
            for (int i = 0; i < this.shiftScratch.size(); i += 2) {
                long delta = this.shiftScratch.getLong(i + 1) - group.lag;
                if (delta != 0L) {
                    this.addShift(this.shiftScratch.getLong(i), delta);
                }
            }
            this.shiftScratch.clear();
            this.groups.add(group);
        }
        // chunks that dropped out of every group go back to the server clock at once: their pending ticks are shifted by the lag they
        // had, so every remaining delay stays the same and nothing has to catch up
        var released = this.memberGroup.long2ObjectEntrySet().fastIterator();
        while (released.hasNext()) {
            var entry = released.next();
            if (!replacement.containsKey(entry.getLongKey()) && entry.getValue().lag > 0) {
                this.addShift(entry.getLongKey(), entry.getValue().lag);
            }
        }
        this.memberSpare = this.memberGroup;
        this.memberGroup = replacement;
        boolean lagging = false;
        for (int i = 0; i < this.groups.size() && !lagging; ++i) {
            lagging = this.groups.get(i).lag > 0;
        }
        this.hasLag = lagging;
        this.queue.clear();
        if (hottest) {
            this.updateLowestSpeed();
        }
    }

    /**
     * Virtual time: true while a structure that spans chunks (a piston move) has to be kept in one clock.
     */
    public boolean couplesChunks() {
        return !this.groups.isEmpty() && this.isVirtual();
    }

    /**
     * Virtual time: a moved structure (piston) must not run in two different clocks. A chunk that is touched by the move joins the
     * group of the other chunk right away, the next rebuild keeps it as an active member. Two different groups are merged by the
     * next rebuild.
     */
    public void coupleChunks(long keyA, long keyB) {
        if (keyA == keyB) {
            return;
        }
        Group groupA = this.memberGroup.get(keyA);
        Group groupB = this.memberGroup.get(keyB);
        if (groupA == groupB) {
            return; // both outside of any group, or in the same one
        }
        // both chunks stay members of the group while the machine works: the rebuild takes them over as anchors, otherwise a
        // chunk that joined here would drop out again and pump between the group clock and its own
        long until = this.currentTick + this.activityWindow();
        this.coupledUntil.put(keyA, until);
        this.coupledUntil.put(keyB, until);
        if (groupA != null && groupB != null) {
            this.groupsDirty = true; // the rebuild merges both groups, the anchors connect them
            return;
        }
        Group group = groupA != null ? groupA : groupB;
        long joining = groupA != null ? keyB : keyA;
        this.memberGroup.put(joining, group);
        long delta = -group.lag; // the joining chunk was on the server clock (lag 0), its pending ticks move to the group's clock
        if (delta != 0L) {
            this.addShift(joining, delta);
        }
        ChunkState state = this.chunks.get(joining);
        if (state == null) {
            state = new ChunkState(joining);
            this.chunks.put(joining, state);
        }
        state.lastActivity = this.currentTick;
        this.groupsDirty = true;
    }

    private void addNeighbors(long key, int radius) {
        long x = ChunkPos.getX(key);
        long z = ChunkPos.getZ(key);
        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                if (validCoordinate(x + dx) && validCoordinate(z + dz)) {
                    this.remaining.add(ChunkPos.pack((int) (x + dx), (int) (z + dz)));
                }
            }
        }
    }

    /**
     * A chunk that is loaded starts in the server clock (lag 0): its saved ticks are stored relative to the server clock, and a stale
     * entry from before it was unloaded must not give it the lag of a group.
     */
    public void onChunkLoaded(long chunkKey) {
        if (!this.memberGroup.isEmpty()) {
            Group group = this.memberGroup.remove(chunkKey);
            if (group != null) {
                this.groupsDirty = true;
            }
        }
    }

    public void removeChunk(long chunkKey) {
        this.coupledUntil.remove(chunkKey);
        if (this.frozenChunks.remove(chunkKey)) {
            this.groupsDirty = true; // the rebuild at the end of the tick drops the group when nothing is left of it (also without a slowdown)
        }
        // an unloaded chunk leaves its group without a shift: its container is gone, and a stale member entry would make the next
        // rebuild shift a container that is loaded again later
        this.memberGroup.remove(chunkKey);
        ChunkState state = this.chunks.remove(chunkKey);
        if (state != null && state.queued) {
            this.unlink(state);
        }
        // The groups are rebuilt at the end of the tick (the refresh interval), not here: a rebuild in the middle of a tick would
        // change the skip decision and drop the costs of the tick.
    }

    private void clearTracking() {
        if (!this.tracking) {
            return; // nothing was tracked since the limiter was switched off: no work per tick while it stays off
        }
        this.tracking = false;
        // chunks that were in a group go back to the server clock: their pending ticks are shifted by their lag
        var members = this.memberGroup.long2ObjectEntrySet().fastIterator();
        while (members.hasNext()) {
            var entry = members.next();
            if (entry.getValue().lag > 0) {
                this.addShift(entry.getLongKey(), entry.getValue().lag);
            }
        }
        this.anySkipping = false;
        this.worldTickNanos = 0;
        this.worldEmaMs = 0.0;
        this.ticksSinceStats = 0;
        this.publishedStats = Stats.EMPTY;
        this.chunks.clear();
        this.memberGroup.clear();
        this.memberSpare.clear();
        this.groups.clear();
        this.pending = null;
        this.groupsDirty = false;
        this.wasVirtual = false;
        this.wasCascade = false;
        this.coupledUntil.clear();
        this.frozenChunks.clear();
        this.freezeTimer = 0;
        this.hasLag = false;
        this.msptEma = 0.0;
        this.overTicks = 0;
        this.warmupTicks = MSPT_WARMUP_TICKS;
        this.throttling = false;
        this.speed = 1.0;
        this.loggedStart = false;
    }

    private void enqueue(ChunkState state) {
        if (!state.queued) {
            state.queued = true;
            state.next = this.pending;
            if (this.pending != null) {
                this.pending.previous = state;
            }
            this.pending = state;
        }
    }

    private void unlink(ChunkState state) {
        if (state.previous == null) {
            this.pending = state.next;
        } else {
            state.previous.next = state.next;
        }
        if (state.next != null) {
            state.next.previous = state.previous;
        }
        state.previous = null;
        state.next = null;
        state.queued = false;
    }

    private void logStart(long gameTime) {
        // the end message is only logged for a logged start, so the interval cannot leave a start without its end
        this.loggedStart = false;
        if (!RedstoneLimiter.logWarnings
            || (this.hasLog && elapsed(gameTime, this.lastLog) < Math.max(1, RedstoneLimiter.logIntervalTicks))) {
            return;
        }
        this.lastLog = gameTime;
        this.hasLog = true;
        this.loggedStart = true;
        LeafConfig.LOGGER.warn("Redstone limiter: world={}, MSPT={} ms reached the threshold of {} ms, mode={}",
            this.worldName, this.msptEma, RedstoneLimiter.msptThreshold, RedstoneLimiter.monitorOnly ? "would slow down" : "slow down");
    }

    private void logEnd() {
        if (this.loggedStart && RedstoneLimiter.logWarnings) {
            LeafConfig.LOGGER.info("Redstone limiter: recovered world={}, MSPT={} ms, mode={}",
                this.worldName, this.msptEma, RedstoneLimiter.monitorOnly ? "monitor" : "slow down");
        }
        this.loggedStart = false;
    }

    private static boolean validCoordinate(long coordinate) {
        return coordinate >= Integer.MIN_VALUE && coordinate <= Integer.MAX_VALUE;
    }

    private static double updateEma(double previous, long nanos, long elapsed, double alpha, double decay) {
        return previous * Math.pow(decay, Math.max(1, elapsed)) + alpha * (nanos / 1_000_000.0);
    }

    private static long elapsed(long now, long then) {
        if (now < then) {
            return 0;
        }
        long difference = now - then;
        return difference < 0 ? Long.MAX_VALUE : difference;
    }

    private static final class Group {
        private long lag;
        private double phase;
        private boolean skip;
        private boolean frozen; // stands still completely, independent of the speed (see updateFreeze)
        private double speed = 1.0; // scope "hottest": speed of this group, 1.0 = real time
        private double cost; // scope "hottest": summed smoothed cost of the members at the last rebuild
    }

    private static final class ChunkState {
        private final long chunkKey;
        private long tickNanos;
        private double emaMs;
        private long lastUpdate;
        private long lastActivity;
        private boolean hasUpdate;
        private boolean queued;
        private ChunkState previous;
        private ChunkState next;

        private ChunkState(long chunkKey) {
            this.chunkKey = chunkKey;
        }
    }
}
