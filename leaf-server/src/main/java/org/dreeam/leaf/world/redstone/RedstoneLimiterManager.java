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

/** Per-world accounting on the world's tick thread; only published statistics may be read from other threads. */
public final class RedstoneLimiterManager {

    // Weak references: the world owns its manager, an unloaded world must not be kept alive by this lookup table.
    private static final ConcurrentHashMap<String, WeakReference<RedstoneLimiterManager>> MANAGERS = new ConcurrentHashMap<>();

    public record Stats(
        boolean measuring,
        boolean throttling,
        double redstoneMs,
        int hotChunks,
        int throttledChunks,
        int groups,
        boolean hasHottest,
        int hottestChunkX,
        int hottestChunkZ,
        double hottestMs
    ) {
        public static final Stats EMPTY = new Stats(false, false, 0.0, 0, 0, 0, false, 0, 0, 0.0);
    }

    private volatile Stats publishedStats = Stats.EMPTY;
    private long worldTickNanos;
    private double worldEmaMs;
    private int ticksSinceStats;

    private static final long DEBT_LIMIT_FACTOR = 2L;
    private static final long ACTIVITY_WINDOW_TICKS = 100L; // a chunk counts as running redstone for this long after its last scheduled tick
    private static final long GROUP_REFRESH_TICKS = 20L;
    private static final long VIRTUAL_GROUP_REFRESH_TICKS = 5L; // virtual time: a moving machine must not outrun the group
    private final LongArrayList propagationQueue = new LongArrayList();
    private Long2ObjectOpenHashMap<Group> memberSpare = new Long2ObjectOpenHashMap<>();
    private long lastGroupRebuild;
    private boolean lastPropagate = true;
    private int lastMaxGroupChunks = 256;

    private final String worldName;
    private long cachedAllowanceNanos = 2_000_000L; // refreshed once per server tick in endTick, keeps record() free of floating point work
    private final Long2ObjectOpenHashMap<ChunkState> chunks = new Long2ObjectOpenHashMap<>();
    private Long2ObjectOpenHashMap<Group> memberGroup = new Long2ObjectOpenHashMap<>();
    private final java.util.ArrayList<Group> groups = new java.util.ArrayList<>();
    private final LongOpenHashSet remaining = new LongOpenHashSet();
    private final LongArrayList queue = new LongArrayList();
    // Intrusive pending list; only hot or touched states remain queued between ticks.
    private ChunkState pending;
    private boolean groupsDirty;
    private int groupRadius = -1;
    private boolean wasLocalActive;
    // Virtual time: chunks that left a group keep their lag (server clock minus the chunk's own clock) until they caught up
    private final Long2LongOpenHashMap releasedLag = new Long2LongOpenHashMap();
    private boolean hasLag;
    private final Long2LongOpenHashMap coupledUntil = new Long2LongOpenHashMap(); // chunks of a moved structure, kept as group members until then
    private Long2LongOpenHashMap pendingShift = new Long2LongOpenHashMap();
    private final LongArrayList shiftScratch = new LongArrayList();
    private boolean anySkipping;
    private double catchPhase;
    private long currentTick;
    private long lastTick;
    private boolean hasTick;
    private long lastSweep;
    private boolean hasSweep;
    private long lastLog;
    private boolean hasLog;
    private long lastBudgetLog;
    private boolean hasBudgetLog;
    private long budgetExceededTicks;
    private long budgetDeferredTicks;
    private long maxBudgetNanos;

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
        this.advanceVirtualClocks(this.cachedAllowanceNanos);
    }

    /** Trigger tick shifts (chunk key to delta) that follow a lag change of chunks, applied by the scheduled tick code. */
    public boolean hasPendingShifts() {
        return !this.pendingShift.isEmpty();
    }

    public @Nullable Long2LongOpenHashMap takeShifts() {
        if (this.pendingShift.isEmpty()) {
            return null;
        }
        Long2LongOpenHashMap out = this.pendingShift;
        this.pendingShift = new Long2LongOpenHashMap();
        return out;
    }

    private boolean isVirtual() {
        return RedstoneLimiter.virtualTime && this.isLocalActive();
    }

    /** True while any chunk is behind real time, the scheduled tick code then converts times through {@link #lagOf}. */
    public boolean hasLag() {
        return this.hasLag;
    }

    /** Ticks the chunk's own clock is behind the server clock (0 for normal chunks). */
    public long lagOf(long chunkKey) {
        Group group = this.memberGroup.get(chunkKey);
        return group != null ? group.lag : this.releasedLag.get(chunkKey);
    }

    /** True while some group skips the current server tick (virtual time). */
    public boolean isSkippingChunks() {
        return this.anySkipping;
    }

    public boolean skipsBlockEntity(net.minecraft.core.BlockPos pos) {
        if (pos == null || !RedstoneLimiter.slowBlockEntities) { // some tickers have no position
            return false;
        }
        Group group = this.memberGroup.get(net.minecraft.world.level.ChunkPos.pack(pos));
        return group != null && group.skip;
    }

    public boolean isLocalActive() {
        return RedstoneLimiter.enabled && !RedstoneLimiter.monitorOnly && RedstoneLimiter.chunkThrottling
            && RedstoneLimiter.groupBudgetMs > 0.0 && Double.isFinite(RedstoneLimiter.groupBudgetMs);
    }

    public boolean admitScheduledTick(long chunkKey, long gameTime) {
        if (this.groups.isEmpty() || !this.isLocalActive()) {
            return true;
        }
        Group group = this.memberGroup.get(chunkKey);
        if (group == null) {
            return true;
        }
        if (RedstoneLimiter.virtualTime) {
            if (group.skip) {
                group.lastRejected = gameTime;
                this.markHeldBack(chunkKey, gameTime);
                return false;
            }
            return true;
        }
        // Comparing avoids overflowing balance - admittedEstimateNanos.
        if (group.balanceNanos <= group.admittedEstimateNanos) {
            group.lastRejected = gameTime;
            this.markHeldBack(chunkKey, gameTime);
            return false;
        }
        long estimate = Math.max(1L, (long) group.avgCostNanos);
        group.admittedEstimateNanos += Math.min(estimate, Long.MAX_VALUE - group.admittedEstimateNanos);
        return true;
    }

    public boolean mayRunBlockEvents(long chunkKey, long gameTime) {
        if (this.groups.isEmpty() || !(RedstoneLimiter.slowPistons || RedstoneLimiter.virtualTime) || !this.isLocalActive()) {
            return true;
        }
        Group group = this.memberGroup.get(chunkKey);
        if (group != null && RedstoneLimiter.virtualTime) {
            if (group.skip) {
                group.lastRejected = gameTime;
                this.markHeldBack(chunkKey, gameTime);
                return false;
            }
            return true;
        }
        if (group != null && group.balanceNanos <= 0) {
            group.lastRejected = gameTime;
            this.markHeldBack(chunkKey, gameTime);
            return false;
        }
        return true;
    }

    /**
     * A chunk that still has work but is held back by the limiter stays active. Otherwise the throttling would drop it from the
     * group after the activity window and let its pending work run unthrottled. Only called on rejection, so the lookup and the
     * rare state creation stay off the admitted fast path.
     */
    private void markHeldBack(long chunkKey, long gameTime) {
        ChunkState held = this.chunks.get(chunkKey);
        if (held == null) {
            held = new ChunkState(chunkKey);
            this.chunks.put(chunkKey, held);
        }
        held.lastActivity = gameTime;
        held.lastHeld = gameTime;
    }

    public boolean isBudgetActive() {
        // with virtual time a group tick is atomic, the hand back of the global budget would split it
        return !this.isVirtual() && RedstoneLimiter.enabled && !RedstoneLimiter.monitorOnly && RedstoneLimiter.executionBudgetMs > 0.0;
    }

    public long budgetNanos() {
        return this.isBudgetActive() ? (long) (RedstoneLimiter.executionBudgetMs * 1_000_000.0) : 0;
    }

    public void onBudgetExceeded(long gameTime, int deferredTicks, long usedNanos) {
        if (!this.isBudgetActive() || deferredTicks <= 0 || !RedstoneLimiter.logWarnings) {
            return;
        }
        if (this.budgetExceededTicks < Long.MAX_VALUE) {
            ++this.budgetExceededTicks;
        }
        this.budgetDeferredTicks += Math.min((long) deferredTicks, Long.MAX_VALUE - this.budgetDeferredTicks);
        this.maxBudgetNanos = Math.max(this.maxBudgetNanos, usedNanos);
        if (!RedstoneLimiter.logWarnings
            || (this.hasBudgetLog && elapsed(gameTime, this.lastBudgetLog) < Math.max(1, RedstoneLimiter.logIntervalTicks))) {
            return;
        }
        this.lastBudgetLog = gameTime;
        this.hasBudgetLog = true;
        LeafConfig.LOGGER.warn("Redstone limiter: world={}, budget exceeded in {} ticks, deferred={} ticks total (last={}), used={} ms, max={} ms, budget={} ms",
            this.worldName, this.budgetExceededTicks, this.budgetDeferredTicks, deferredTicks,
            usedNanos / 1_000_000.0, this.maxBudgetNanos / 1_000_000.0, RedstoneLimiter.executionBudgetMs);
        this.budgetExceededTicks = 0;
        this.budgetDeferredTicks = 0;
        this.maxBudgetNanos = 0;
    }

    public void record(long chunkKey, long measuredNanos) {
        long nanos = Math.max(1L, measuredNanos); // a measurement below the timer resolution still counts as activity
        if (!RedstoneLimiter.enabled || (!RedstoneLimiter.chunkThrottling && !RedstoneLimiter.monitorOnly) || nanos <= 0) {
            return;
        }
        this.worldTickNanos += Math.min(nanos, Long.MAX_VALUE - this.worldTickNanos);
        if (!this.groups.isEmpty() && this.isLocalActive()) {
            Group group = this.memberGroup.get(chunkKey);
            if (group != null) {
                // Debt is capped at DEBT_LIMIT_FACTOR allowances: one indivisible cascade can then cost at most a few paused ticks
                // instead of a long freeze, in exchange the target rate may be exceeded by huge single cascades.
                long floor = -DEBT_LIMIT_FACTOR * this.cachedAllowanceNanos;
                // Clamp before subtraction, even for an implausibly large duration.
                group.balanceNanos = Math.max(floor, group.balanceNanos - Math.min(nanos, group.balanceNanos - floor));
                group.avgCostNanos += 0.25 * ((double) nanos - group.avgCostNanos);
                group.spentNanos += Math.min(nanos, Long.MAX_VALUE - group.spentNanos);
            }
        }
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
        this.currentTick = gameTime == Long.MAX_VALUE ? Long.MAX_VALUE : gameTime + 1;
        if (!RedstoneLimiter.enabled || (!RedstoneLimiter.chunkThrottling && !RedstoneLimiter.monitorOnly)) {
            this.clearTracking();
            return;
        }
        // virtual time runs a group in 1 of N ticks, the cost per server tick is bursty (full tick, then nothing) with a period of up
        // to 1/minSpeed ticks, so the displayed load needs a window that covers several periods
        this.worldEmaMs += (!this.groups.isEmpty() && this.isVirtual() ? 0.03 : 0.2) * (this.worldTickNanos / 1_000_000.0 - this.worldEmaMs);
        this.worldTickNanos = 0;
        int window = Math.max(1, RedstoneLimiter.emaWindowTicks);
        double alpha = 2.0 / (window + 1.0);
        double decay = 1.0 - alpha;
        boolean localActive = this.isLocalActive();
        int radius = Math.clamp(RedstoneLimiter.neighborRadius, 0, 2);
        if (localActive != this.wasLocalActive || radius != this.groupRadius) {
            this.groupsDirty = true;
        }
        this.wasLocalActive = localActive;
        this.groupRadius = radius;
        boolean budgetEnabled = validChunkBudget();
        double recovery = RedstoneLimiter.recoveryRatio;
        recovery = Double.isFinite(recovery) ? Math.clamp(recovery, 0.0, 1.0) : 0.5;
        long cooldown = Math.max(0, RedstoneLimiter.cooldownTicks);
        // A gap or clock reset cannot count as consecutive rejection-free server ticks.
        boolean consecutive = this.hasTick && this.lastTick != Long.MAX_VALUE && gameTime == this.lastTick + 1;
        long allowance = allowanceNanos();
        this.cachedAllowanceNanos = allowance;
        for (int i = 0; i < this.groups.size(); ++i) {
            Group group = this.groups.get(i);
            if (!consecutive) {
                group.lastRejected = gameTime;
            }
            group.balanceNanos = Math.clamp(group.balanceNanos, -DEBT_LIMIT_FACTOR * allowance, 2 * allowance);
            group.balanceNanos = Math.min(2 * allowance, group.balanceNanos + allowance);
            group.admittedEstimateNanos = 0;
        }
        this.updateGroupCosts();

        int hotChunks = 0;
        boolean hasHottest = false;
        long hottestChunk = 0;
        double hottestMs = 0.0;
        boolean virtualTime = this.isVirtual();
        ChunkState state = this.pending;
        while (state != null) {
            ChunkState next = state.next;
            // virtual time: a tick the chunk's group skips has no cost by design. Measuring it as zero would make the throttling look
            // like a recovery, so only ticks the group executed enter the average (the cost per executed tick stays comparable)
            Group chunkGroup = virtualTime ? this.memberGroup.get(state.chunkKey) : null;
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
            boolean wasHot = state.hot;
            if (!budgetEnabled) {
                state.hot = false;
            } else if (state.hot) {
                // only the chunk's OWN held back work keeps it hot, otherwise rejections of a neighbouring farm in the same group
                // would keep an idle anchor chunk (and with it the whole group) throttled forever
                long rejection = Math.max(state.enteredAt, state.lastHeld);
                // virtual time: a group that would still throttle (its cost per executed tick is above the budget) is not released, a
                // pulsing machine has idle executed ticks in between that would otherwise look like a recovery of the chunk
                boolean stillThrottled = chunkGroup != null && chunkGroup.avgRunNanos > allowance * recovery;
                if (!stillThrottled && state.emaMs <= RedstoneLimiter.chunkBudgetMs * recovery
                    && elapsed(gameTime, state.enteredAt) >= cooldown && elapsed(gameTime, rejection) >= cooldown) {
                    state.hot = false;
                }
            } else if (state.emaMs >= RedstoneLimiter.chunkBudgetMs) {
                state.hot = true;
                state.enteredAt = gameTime;
            }
            if (state.hot != wasHot) {
                this.groupsDirty = true;
                this.logTransition(state, gameTime, state.hot);
            }
            if (state.emaMs > hottestMs) {
                hasHottest = true;
                hottestChunk = state.chunkKey;
                hottestMs = state.emaMs;
            }
            if (state.hot) {
                ++hotChunks;
            }
            if (!state.hot) {
                // the state stays in the map (not queued) so its activity can still count for spreading, the sweep removes it later
                this.unlink(state);
            }
            state = next;
        }
        // chunk activity changes without the hot set changing, so a spreading group is refreshed regularly
        if (!this.groups.isEmpty() && RedstoneLimiter.propagateActiveChunks && elapsed(gameTime, this.lastGroupRebuild) >= (virtualTime ? VIRTUAL_GROUP_REFRESH_TICKS : GROUP_REFRESH_TICKS)) {
            this.groupsDirty = true;
        }
        // a changed propagation setting (config reload) must rebuild the groups, also when switching spreading off
        if (RedstoneLimiter.propagateActiveChunks != this.lastPropagate || RedstoneLimiter.maxGroupChunks != this.lastMaxGroupChunks) {
            this.lastPropagate = RedstoneLimiter.propagateActiveChunks;
            this.lastMaxGroupChunks = RedstoneLimiter.maxGroupChunks;
            this.groupsDirty = true;
        }
        if (this.groupsDirty) {
            this.rebuildGroups(gameTime, allowance, localActive);
        }
        Stats previousStats = this.publishedStats;
        if (++this.ticksSinceStats >= 10 || !previousStats.measuring() || previousStats.throttling() != localActive) {
            this.publishedStats = new Stats(true, localActive, this.worldEmaMs, hotChunks, this.memberGroup.size(),
                this.groups.size(), hasHottest, ChunkPos.getX(hottestChunk), ChunkPos.getZ(hottestChunk), hottestMs);
            this.ticksSinceStats = 0;
        }
        this.lastTick = gameTime;
        this.hasTick = true;
        if (!this.hasSweep || elapsed(gameTime, this.lastSweep) >= 1200) {
            this.lastSweep = gameTime;
            this.hasSweep = true;
            // keep chunk data at least as long as the activity window plus a group refresh, otherwise the spreading loses its data
            long retention = Math.max((long) window * 4, ACTIVITY_WINDOW_TICKS + GROUP_REFRESH_TICKS + 1);
            var iterator = this.chunks.long2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                ChunkState candidate = iterator.next().getValue();
                if (!candidate.queued && elapsed(gameTime, candidate.lastActivity) >= retention
                    && (candidate.savedRunNanos <= 0.0 || elapsed(gameTime, candidate.savedAt) >= 1200)) { // keep the saved cost of a released group
                    iterator.remove();
                }
            }
        }
    }

    /**
     * End of a server tick, before the groups are rebuilt: the cost of the tick a group executed goes into its average. An executed
     * tick without cost counts too, so an idle group speeds up again.
     */
    private void updateGroupCosts() {
        boolean virtual = this.isVirtual();
        for (int i = 0; i < this.groups.size(); ++i) {
            Group group = this.groups.get(i);
            if (virtual && !group.skip) {
                // rises quickly, falls slowly: bursty machines (clocks with pauses) must not make the speed jump up on every idle tick
                double delta = (double) group.spentNanos - group.avgRunNanos;
                group.avgRunNanos += (delta > 0.0 ? 0.25 : 0.05) * delta;
            }
            group.spentNanos = 0;
        }
    }

    /**
     * Virtual time: decides for the NEXT server tick whether a group runs or is skipped. The group's speed is its time allowance
     * divided by the measured cost of a tick it ran; a fractional accumulator spreads the skipped ticks evenly. A skipped tick
     * adds one tick of lag so the group's own clock (server tick minus lag) stands still.
     */
    private void advanceVirtualClocks(long allowance) {
        boolean virtual = this.isVirtual();
        boolean skipping = false;
        boolean lagging = false;
        double minSpeed = Double.isFinite(RedstoneLimiter.minSpeed) ? Math.clamp(RedstoneLimiter.minSpeed, 0.02, 1.0) : 0.1;
        for (int i = 0; i < this.groups.size(); ++i) {
            Group group = this.groups.get(i);
            if (virtual) {
                double speed = Math.clamp(allowance / Math.max(1.0, group.avgRunNanos), minSpeed, 1.0);
                group.phase += speed;
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
        boolean groupsCatchUp = false;
        if (!virtual) {
            // virtual time was switched off: the groups' delay is worked off like the delay of released chunks
            for (int i = 0; i < this.groups.size(); ++i) {
                groupsCatchUp |= this.groups.get(i).lag > 0;
            }
        }
        long steps = this.catchUpSteps(groupsCatchUp || !this.releasedLag.isEmpty());
        if (steps > 0) {
            this.drainReleasedLag(steps);
            if (groupsCatchUp) {
                lagging = false;
                for (int i = 0; i < this.groups.size(); ++i) {
                    Group group = this.groups.get(i);
                    group.lag = Math.max(0L, group.lag - steps);
                    lagging |= group.lag > 0;
                }
            }
        }
        this.hasLag = lagging || !this.releasedLag.isEmpty();
    }

    /** Chunks outside of any group catch up with the server clock by running their own clock faster for a while. */
    private long catchUpSteps(boolean anyLag) {
        if (!anyLag) {
            this.catchPhase = 0.0;
            return 0L;
        }
        double extra = Double.isFinite(RedstoneLimiter.catchUpSpeed) ? Math.clamp(RedstoneLimiter.catchUpSpeed, 1.0, 4.0) - 1.0 : 0.5;
        if (extra <= 0.0) {
            return 0L;
        }
        this.catchPhase += extra;
        long steps = (long) this.catchPhase;
        this.catchPhase -= steps;
        return steps;
    }

    private void drainReleasedLag(long steps) {
        var iterator = this.releasedLag.long2LongEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long remaining = entry.getLongValue() - steps;
            if (remaining <= 0) {
                iterator.remove();
            } else {
                entry.setValue(remaining);
            }
        }
    }

    private void rebuildGroups(long gameTime, long allowance, boolean localActive) {
        this.groupsDirty = false;
        this.remaining.clear();
        if (localActive) {
            for (ChunkState state = this.pending; state != null; state = state.next) {
                if (state.hot) {
                    this.addNeighbors(state.chunkKey, this.groupRadius);
                }
            }
            if (RedstoneLimiter.propagateActiveChunks) {
                this.propagateToActiveChunks(gameTime);
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
        this.lastGroupRebuild = gameTime;
        Long2ObjectOpenHashMap<Group> replacement = this.memberSpare; // reused, no allocation per rebuild
        replacement.clear();
        this.groups.clear();
        while (!this.remaining.isEmpty()) {
            long first = this.remaining.iterator().nextLong();
            this.remaining.remove(first);
            this.queue.clear();
            this.queue.add(first);
            Group group = new Group(allowance, gameTime);
            // a new group starts at a phase of its own, so several throttled groups do not all execute in the same server tick
            group.phase = (double) ((first * 0x9E3779B97F4A7C15L) >>> 11) / (double) (1L << 53);
            boolean inherited = false;
            boolean phaseInherited = false;
            for (int index = 0; index < this.queue.size(); ++index) {
                long key = this.queue.getLong(index);
                replacement.put(key, group);
                // a chunk joining the group takes the highest delay of the members, so the group clock never runs ahead of any member;
                // the chunk's pending ticks are shifted by the difference afterwards (remaining own ticks stay the same)
                long releasedChunkLag = this.releasedLag.remove(key);
                Group previous = this.memberGroup.get(key);
                long ownLag = previous != null ? previous.lag : releasedChunkLag;
                this.shiftScratch.add(key);
                this.shiftScratch.add(ownLag);
                group.lag = Math.max(group.lag, ownLag);
                if (previous != null) {
                    group.avgRunNanos = Math.max(group.avgRunNanos, previous.avgRunNanos);
                    if (!phaseInherited) {
                        group.phase = previous.phase;
                        group.skip = previous.skip;
                        phaseInherited = true;
                    }
                    if (!inherited) {
                        group.balanceNanos = previous.balanceNanos;
                        group.avgCostNanos = previous.avgCostNanos;
                        group.lastRejected = previous.lastRejected;
                        inherited = true;
                    } else {
                        group.balanceNanos = Math.min(group.balanceNanos, previous.balanceNanos);
                        group.avgCostNanos = Math.max(group.avgCostNanos, previous.avgCostNanos);
                        group.lastRejected = Math.max(group.lastRejected, previous.lastRejected);
                    }
                } else {
                    // a chunk that was released recently brings its known cost back, so the new group does not start unthrottled
                    ChunkState known = this.chunks.get(key);
                    if (known != null && known.savedRunNanos > 0.0 && elapsed(gameTime, known.savedAt) <= 1200) {
                        group.avgRunNanos = Math.max(group.avgRunNanos, known.savedRunNanos);
                    }
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
            for (int i = 0; i < this.shiftScratch.size(); i += 2) {
                long delta = this.shiftScratch.getLong(i + 1) - group.lag;
                if (delta != 0L) {
                    this.pendingShift.addTo(this.shiftScratch.getLong(i), delta);
                }
            }
            this.shiftScratch.clear();
            this.groups.add(group);
        }
        // chunks that dropped out of every group keep their lag and catch up
        var released = this.memberGroup.long2ObjectEntrySet().fastIterator();
        while (released.hasNext()) {
            var entry = released.next();
            if (!replacement.containsKey(entry.getLongKey())) {
                ChunkState known = this.chunks.get(entry.getLongKey());
                if (known != null) {
                    known.savedRunNanos = entry.getValue().avgRunNanos;
                    known.savedAt = gameTime;
                }
                if (entry.getValue().lag > 0) {
                    this.releasedLag.put(entry.getLongKey(), entry.getValue().lag);
                }
            }
        }
        this.memberSpare = this.memberGroup;
        this.memberGroup = replacement;
        this.hasLag = this.hasLag || !this.releasedLag.isEmpty();
        this.queue.clear();
    }

    /**
     * Spreads the throttled set over neighbouring chunks that recently ran scheduled ticks (redstone activity), and from those
     * chunks on, until no active neighbour is left or maxGroupChunks additional chunks were added. Idle chunks stop the spreading.
     */
    private void propagateToActiveChunks(long gameTime) {
        this.propagationQueue.clear();
        var iterator = this.remaining.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (this.isRecentlyActive(key, gameTime)) {
                this.propagationQueue.add(key); // only active (or hot) members spread further
            }
        }
        int added = 0;
        int limit = Math.max(0, RedstoneLimiter.maxGroupChunks);
        for (int index = 0; index < this.propagationQueue.size() && added < limit; ++index) {
            long key = this.propagationQueue.getLong(index);
            long x = ChunkPos.getX(key);
            long z = ChunkPos.getZ(key);
            for (int dx = -1; dx <= 1 && added < limit; ++dx) {
                for (int dz = -1; dz <= 1 && added < limit; ++dz) {
                    if (validCoordinate(x + dx) && validCoordinate(z + dz)) {
                        long neighbor = ChunkPos.pack((int) (x + dx), (int) (z + dz));
                        if (this.isRecentlyActive(neighbor, gameTime) && this.remaining.add(neighbor)) {
                            this.propagationQueue.add(neighbor);
                            ++added;
                        }
                    }
                }
            }
        }
        if (RedstoneLimiter.virtualLookahead && this.isVirtual()) {
            // look-ahead: the neighbours of every active member run in the same clock, so a machine that moves on (flying machine)
            // is already inside the group when it crosses into the next chunk. The ring does not spread further.
            int activeMembers = this.propagationQueue.size();
            int ringStart = this.remaining.size();
            for (int index = 0; index < activeMembers && this.remaining.size() - ringStart < limit; ++index) {
                this.addNeighbors(this.propagationQueue.getLong(index), 1); // the ring is capped by maxGroupChunks as well
            }
        }
        this.propagationQueue.clear();
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
        long until = this.currentTick + ACTIVITY_WINDOW_TICKS;
        this.coupledUntil.put(keyA, until);
        this.coupledUntil.put(keyB, until);
        if (groupA != null && groupB != null) {
            this.groupsDirty = true; // the rebuild merges both groups, the anchors connect them
            return;
        }
        Group group = groupA != null ? groupA : groupB;
        long joining = groupA != null ? keyB : keyA;
        long ownLag = this.releasedLag.get(joining);
        if (ownLag > group.lag) {
            this.groupsDirty = true; // the rebuild raises the group's lag and shifts the members
            return;
        }
        this.releasedLag.remove(joining);
        this.memberGroup.put(joining, group);
        long delta = ownLag - group.lag;
        if (delta != 0L) {
            this.pendingShift.addTo(joining, delta);
        }
        ChunkState state = this.chunks.get(joining);
        if (state == null) {
            state = new ChunkState(joining);
            this.chunks.put(joining, state);
        }
        state.lastActivity = this.currentTick;
        this.groupsDirty = true;
    }

    private boolean isRecentlyActive(long chunkKey, long gameTime) {
        ChunkState state = this.chunks.get(chunkKey);
        return state != null && (state.hot || elapsed(gameTime, state.lastActivity) <= ACTIVITY_WINDOW_TICKS);
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
        if (this.hasLag || !this.memberGroup.isEmpty()) {
            Group group = this.memberGroup.remove(chunkKey);
            this.releasedLag.remove(chunkKey);
            if (group != null) {
                this.groupsDirty = true;
            }
        }
    }

    public void removeChunk(long chunkKey) {
        this.releasedLag.remove(chunkKey);
        this.coupledUntil.remove(chunkKey);
        ChunkState state = this.chunks.remove(chunkKey);
        if (state != null) {
            if (state.queued) {
                this.unlink(state);
            }
            if (state.hot) {
                this.groupsDirty = true;
            }
        }
        // Only hot-set changes alter S. A cold neighbor may still belong to S after unload. The groups are rebuilt at the end of
        // the tick (groupsDirty), not here: a rebuild in the middle of a tick would change the skip decision and drop the costs of the tick.
    }

    private void clearTracking() {
        // the delay of chunks that were in a group is kept so their scheduled ticks stay consistent while they catch up
        var members = this.memberGroup.long2ObjectEntrySet().fastIterator();
        while (members.hasNext()) {
            var entry = members.next();
            if (entry.getValue().lag > 0) {
                this.releasedLag.put(entry.getLongKey(), entry.getValue().lag);
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
        this.wasLocalActive = false;
        this.hasTick = false;
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

    private void logTransition(ChunkState state, long gameTime, boolean entering) {
        if (!RedstoneLimiter.logWarnings
            || (this.hasLog && elapsed(gameTime, this.lastLog) < Math.max(1, RedstoneLimiter.logIntervalTicks))) {
            return;
        }
        this.lastLog = gameTime;
        this.hasLog = true;
        if (entering) {
            LeafConfig.LOGGER.warn("Redstone limiter: world={}, hot chunk=[{}, {}], EMA={} ms, group budget={} ms, mode={}",
                this.worldName, ChunkPos.getX(state.chunkKey), ChunkPos.getZ(state.chunkKey), state.emaMs,
                RedstoneLimiter.groupBudgetMs, RedstoneLimiter.monitorOnly ? "would throttle" : "throttle");
        } else {
            LeafConfig.LOGGER.info("Redstone limiter: recovered world={}, chunk=[{}, {}], mode={}",
                this.worldName, ChunkPos.getX(state.chunkKey), ChunkPos.getZ(state.chunkKey),
                RedstoneLimiter.monitorOnly ? "monitor" : "throttle");
        }
    }

    private static boolean validChunkBudget() {
        return RedstoneLimiter.chunkBudgetMs > 0.0 && Double.isFinite(RedstoneLimiter.chunkBudgetMs);
    }

    private static long allowanceNanos() {
        double budget = RedstoneLimiter.groupBudgetMs;
        // Bound even direct runtime assignments: cap, floor and refill must not overflow.
        return Double.isFinite(budget) ? Math.max(1L, (long) (Math.clamp(budget, 0.0, 50.0) * 1_000_000.0)) : 2_000_000L;
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
        private long balanceNanos;
        private double avgCostNanos = 4000.0;
        private long admittedEstimateNanos;
        private long lastRejected;
        // virtual time
        private long lag;
        private double phase;
        private boolean skip;
        private double avgRunNanos = 4000.0;
        private long spentNanos;

        private Group(long allowance, long gameTime) {
            this.balanceNanos = allowance;
            this.lastRejected = gameTime;
        }
    }

    private static final class ChunkState {
        private final long chunkKey;
        private long tickNanos;
        private double emaMs;
        private long lastUpdate;
        private long lastActivity;
        private long enteredAt;
        private long lastHeld; // last time the limiter held back work of this chunk
        private double savedRunNanos; // virtual time: cost per executed tick of the group the chunk was released from
        private long savedAt;
        private boolean hasUpdate;
        private boolean hot;
        private boolean queued;
        private ChunkState previous;
        private ChunkState next;

        private ChunkState(long chunkKey) {
            this.chunkKey = chunkKey;
        }
    }
}
