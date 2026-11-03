package org.dreeam.leaf.world.antifreecam;

import ca.spottedleaf.moonrise.common.util.TickThread;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.dreeam.leaf.config.modules.misc.AntiFreecam;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.BitSet;
import java.util.function.Predicate;

/** World-owned state. Except for the snapshot-only tracker query, entry points require the tick thread. */
public final class AntiFreecamManager {
    private static final int BFS_BUDGET_PER_PLAYER = 16384;
    private static final int BFS_BUDGET_PER_WORLD = 131072;
    private static final int CONTINUATION_SEND_BUDGET = 32768;
    private static final int LOS_BUDGET_PER_PLAYER = 128;
    private static final int LOS_BUDGET_PER_WORLD = 1024;
    // Entity line of sight zones: up to NEAR every check, up to FAR every MID_LOS_STRIDE-th, beyond every FAR_LOS_STRIDE-th
    // check with two rays instead of nine. With the default interval of 2 ticks that is 2, 4 and 10 ticks.
    private static final double NEAR_LOS_DISTANCE = 32.0, FAR_LOS_DISTANCE = 64.0;
    private static final int MID_LOS_STRIDE = 2, FAR_LOS_STRIDE = 5;
    // Allocated once; the lookup invokes this only on the owning tick thread.
    private final Predicate<Entity> candidateSelector = this::selectCandidate;
    private int querySkip, querySeen;
    private int[] resendCounts = new int[0];
    private final ServerLevel level;
    private final ConnectivityCache connectivity;
    private final Reference2ObjectOpenHashMap<ServerPlayer, PlayerVisibility> players = new Reference2ObjectOpenHashMap<>();
    private volatile Int2ObjectOpenHashMap<VisibilitySnapshot> snapshots = new Int2ObjectOpenHashMap<>();
    private final ObjectArrayList<Entity> candidates = new ObjectArrayList<>();
    // bumped whenever the cell masks a player holds change; the entity snapshot only rebuilds its mask index then
    private final it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<ServerPlayer> maskVersions = new it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<ServerPlayer, PublishedMasks> publishedMasks = new Reference2ObjectOpenHashMap<>();
    private record PublishedMasks(int version, it.unimi.dsi.fastutil.longs.@Nullable Long2ObjectOpenHashMap<long[]> masks) {}
    // last line of sight check tick per entity id, only for the zones that are checked less often
    private final Reference2ObjectOpenHashMap<ServerPlayer, it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap> losChecks = new Reference2ObjectOpenHashMap<>();
    // players whose sent cell masks changed since their snapshot was built
    private final it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<PlayerVisibility> masksDirty = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();
    // true between this manager's tick and the publication after the player chunk loader sent its chunks
    private boolean batching;
    private long tick;
    private int generation = AntiFreecam.generation;
    private int rotation;
    private PlayerVisibility[] order = new PlayerVisibility[0];
    private boolean orderDirty;
    // Fixed storage keeps the block-change hook allocation-free, including bursts of edits.
    private final int[] changedX = new int[1024], changedY = new int[1024], changedZ = new int[1024];
    private final boolean[] changedConnectivity = new boolean[1024], changedSurface = new boolean[1024];
    private int changedCount;
    private boolean changesOverflow;

    public AntiFreecamManager(final ServerLevel level) {
        TickThread.ensureTickThread(level, "Cannot create anti-freecam manager off-world-tick-thread");
        this.level = level;
        this.connectivity = new ConnectivityCache(level);
    }
    private void checkThread() { TickThread.ensureTickThread(this.level, "Cannot access anti-freecam state off-world-tick-thread"); }
    private PlayerVisibility state(final ServerPlayer player) {
        PlayerVisibility state = this.players.get(player);
        if (state == null) {
            state = new PlayerVisibility(player);
            this.players.put(player, state);
            this.orderDirty = true;
        }
        this.refreshBypass(state);
        state.request(this.level.getMinY() >> 4, this.level.getSectionsCount(), this.tick);
        return state;
    }
    private void refreshBypass(final PlayerVisibility state) {
        if (this.tick - state.permissionTick >= 20) {
            state.permissionBypass = state.player.getBukkitEntity().hasPermission(AntiFreecam.BYPASS_PERMISSION);
            state.permissionTick = this.tick;
        }
        final boolean bypass = !AntiFreecam.isEnabled(this.level) || state.player.isSpectator() || state.permissionBypass;
        if (state.bypass != bypass) {
            state.bypass = bypass;
            state.nextDiffTick = 0;
            state.dirty = true;
            state.snapshot = null;
            if (bypass) { state.lastSeen.clear(); state.occluded.clear(); state.partialOccluded.clear(); }
        }
    }
    public void tick() {
        checkThread();
        // chunk sends until publishChangedMasks are collected and published together after the loader tick
        this.batching = true;
        this.connectivity.beginPass();
        ++this.tick;
        if (this.generation != AntiFreecam.generation) {
            this.generation = AntiFreecam.generation;
            for (final PlayerVisibility state : this.players.values()) {
                state.dirty = true;
                state.visibilityDirty = true;
                state.permissionTick = Long.MIN_VALUE / 2;
                state.snapshot = null;
                state.lastSeen.clear();
                state.occluded.clear();
                state.partialOccluded.clear();
                state.entityCursor = 0;
            }
        }
        if (this.orderDirty) {
            this.order = this.players.values().toArray(new PlayerVisibility[0]);
            this.orderDirty = false;
        }
        final int count = this.order.length;
        if (count == 0) { this.changedCount = 0; this.changesOverflow = false; return; }
        int bfsBudget = BFS_BUDGET_PER_WORLD, losBudget = LOS_BUDGET_PER_WORLD;
        for (int n = 0; n < count; ++n) {
            final PlayerVisibility state = this.order[(n + this.rotation) % count];
            if (this.changesOverflow) { state.dirty = true; state.visibilityDirty = true; state.nextDiffTick = 0; }
            else for (int change = 0; change < this.changedCount; ++change) {
                final int cx = this.changedX[change], cy = this.changedY[change], cz = this.changedZ[change];
                if (this.changedConnectivity[change] && (state.contains(cx, cy, cz) || state.workContains(cx, cy, cz))) state.dirty = true;
                // Surface-only changes rebuild the effective bits/diff, not the connectivity flood.
                if (this.changedSurface[change]) {
                    if (state.contains(cx, state.minY, cz)) state.visibilityDirty = true;
                    if (state.live.containsKey(ConnectivityCache.key(cx, cz))) state.nextDiffTick = 0;
                }
            }
            this.refreshBypass(state);
            state.request(this.level.getMinY() >> 4, this.level.getSectionsCount(), this.tick);
            if (!state.bypass) state.prepareContinuations(this.connectivity);
            if (!state.bypass && bfsBudget > 0) {
                int playerBudget = Math.min(BFS_BUDGET_PER_PLAYER, bfsBudget);
                if (state.hasContinuation()) {
                    final int budget = state.running ? Math.max(1, playerBudget / 2) : playerBudget;
                    state.advanceContinuation(this.connectivity, budget);
                    playerBudget -= budget;
                    bfsBudget -= budget;
                }
                if (state.running && playerBudget > 0) {
                    state.advance(this.connectivity, playerBudget);
                    bfsBudget -= playerBudget;
                }
            }
            state.refreshVisibility(this.connectivity);
            this.diff(state);
            if (state.bypass || !AntiFreecam.entityCulling) {
                state.snapshot = null;
            } else if (state.resultCoversCurrent() && (state.snapshotNeedsRefresh || state.snapshot == null
                || this.tick % AntiFreecam.entityCheckIntervalTicks == 0 && losBudget > 0)) {
                // Refresh completed geometry immediately, even off-interval. If the ray budget is
                // exhausted, retain cached LOS decisions and publish the new section result without rays.
                final int budget = Math.min(losBudget, LOS_BUDGET_PER_PLAYER);
                this.updateEntities(state, budget);
                losBudget -= budget;
            }
        }
        this.changedCount = 0;
        this.changesOverflow = false;
        int remaining = AntiFreecam.maxResendsPerTick;
        // Two world-wide passes prevent an early player's re-hide from delaying another player's reveal.
        if (this.resendCounts.length < count) this.resendCounts = new int[count];
        final int[] sent = this.resendCounts;
        Arrays.fill(sent, 0, count, 0);
        for (int pass = 0; pass < 2 && remaining > 0; ++pass) {
            for (int n = 0; n < count && remaining > 0; ++n) {
                final PlayerVisibility state = this.order[(n + this.rotation) % count];
                final int amount = this.drain(state, pass == 0, Math.min(remaining, AntiFreecam.maxResendsPerPlayerTick - sent[n]));
                remaining -= amount; sent[n] += amount;
            }
        }
        this.rotation = (this.rotation + 1) % count;
        // the resends above changed masks after this tick's snapshots were built
        for (final PlayerVisibility state : this.masksDirty) {
            if (this.players.get(state.player) == state && !state.bypass && AntiFreecam.entityCulling) this.updateEntities(state, 0);
        }
        this.masksDirty.clear();
        int snapshotCount = 0;
        boolean publicationChanged = false;
        final Int2ObjectOpenHashMap<VisibilitySnapshot> previous = this.snapshots;
        for (final PlayerVisibility state : this.order) {
            if (state.snapshot != null) ++snapshotCount;
            if (previous.get(state.player.getId()) != state.snapshot) publicationChanged = true;
        }
        if (publicationChanged || snapshotCount != previous.size()) {
            final Int2ObjectOpenHashMap<VisibilitySnapshot> published = new Int2ObjectOpenHashMap<>(snapshotCount);
            for (final PlayerVisibility state : this.order) if (state.snapshot != null) published.put(state.player.getId(), state.snapshot);
            this.snapshots = published;
        }
    }
    public @Nullable SendMasks onChunkSend(final ServerPlayer player, final LevelChunk chunk) {
        checkThread();
        this.connectivity.beginPass();
        final PlayerVisibility state = this.state(player);
        final int x = chunk.getPos().x(), z = chunk.getPos().z();
        final ConnectivityCache.Column sendColumn = state.bypass ? null : this.connectivity.resolve(chunk);
        final int surfaceSection = sendColumn == null ? Integer.MIN_VALUE : this.connectivity.minimumSurface(sendColumn) >> 4;
        // A completed old window is safe to use while refreshing an overlapping player position.
        // First results and chunks outside that window attempt synchronous completion; frontier
        // overflow defers the remaining work to the tick budget instead of forcing a large recovery flood.
        if (!state.bypass && !state.contains(x, this.level.getMinY() >> 4, z)) state.finishForSend(this.connectivity, this.level.getMinY() >> 4, this.level.getSectionsCount(), this.tick);
        final long key = ConnectivityCache.key(x, z);
        if (!state.bypass) state.continueChunk(this.connectivity, key, CONTINUATION_SEND_BUDGET);
        state.refreshVisibility(this.connectivity);
        if (sendColumn != null) state.refreshSurfaceChunk(this.connectivity, x, z, sendColumn);
        PlayerVisibility.SentChunk sent = state.live.get(key);
        final boolean firstSend = sent == null;
        if (firstSend) {
            sent = new PlayerVisibility.SentChunk(this.level.getSectionsCount());
            state.live.put(key, sent);
            // Other viewers resume their own frontier under the regular tick budget, without forcing
            // full floods. The sending viewer's frontier was already processed before building its mask.
            for (final PlayerVisibility other : this.players.values()) {
                if (other != state && !other.bypass && other.hasMissingChunk(key)) other.continueChunk(this.connectivity, key, 0);
            }
        }
        sent.awaitingResult = !state.bypass && !state.contains(x, this.level.getMinY() >> 4, z)
            && state.workContains(x, this.level.getMinY() >> 4, z);
        BitSet hidden = null, partial = null;
        long[][] masks = null, componentMasks = null;
        final int sectionCount = this.level.getSectionsCount(), minSection = this.level.getMinY() >> 4;
        if (!state.bypass) {
            if (sent.culledSince == null) { sent.culledSince = new long[sectionCount]; Arrays.fill(sent.culledSince, -1L); }
            final PartialCulling culling = AntiFreecamHooks.partialCulling(this.level);
            final long lightTick = this.level.getServer().getTickCount();
            for (int section = 0; section < sectionCount; ++section) {
                final int target = state.maskStateForSend(x, minSection + section, z, surfaceSection);
                final int actual = actualState(sent, section);
                int outgoing = target;
                if (target <= actual || firstSend) sent.hiddenSince[section] = -1L;
                else {
                    if (sent.hiddenSince[section] < 0) sent.hiddenSince[section] = this.tick;
                    // A reveal in this packet must not prematurely apply another section's re-hide.
                    if (this.tick - sent.hiddenSince[section] < AntiFreecam.rehideDelayTicks) outgoing = actual;
                    else sent.hiddenSince[section] = -1L;
                }
                if (outgoing == PlayerVisibility.HIDDEN) {
                    sent.culledSince[section] = -1L;
                    if (hidden == null) hidden = new BitSet(sectionCount);
                    hidden.set(section);
                } else if (outgoing == PlayerVisibility.PARTIAL) {
                    sent.culledSince[section] = -1L;
                    if (partial == null) partial = new BitSet(sectionCount);
                    partial.set(section);
                    final long[] mask = culling.mask(chunk, section, lightTick);
                    if (mask != null) {
                        if (masks == null) masks = new long[sectionCount][];
                        masks[section] = mask;
                    }
                } else {
                    final long[] held = sent.componentMasks == null ? null : sent.componentMasks[section];
                    // a real section that waits for its re-hide keeps the components the client already has
                    long[] component = target == PlayerVisibility.REAL ? this.componentMask(state, sendColumn, x, minSection + section, z, section, true) : held;
                    // reachability from before a topology change cannot be mapped onto the rebuilt components
                    if (component == STALE_MASK) component = held;
                    if (component != null && !firstSend && !covers(held, component)) {
                        if (sent.culledSince[section] < 0) sent.culledSince[section] = this.tick;
                        if (this.tick - sent.culledSince[section] < AntiFreecam.rehideDelayTicks) {
                            // reveals apply at once, newly unreached components only after the delay
                            component = intersect(held, component);
                        } else sent.culledSince[section] = -1L;
                    } else sent.culledSince[section] = -1L;
                    if (component != null) {
                        if (partial == null) partial = new BitSet(sectionCount);
                        partial.set(section);
                        if (componentMasks == null) componentMasks = new long[sectionCount][];
                        componentMasks[section] = component;
                        // at the surface only dark cells become filler, unreached sky-lit air must not turn into floating stone
                        final long[] mask = minSection + section >= surfaceSection ? intersect(component, culling.mask(chunk, section, lightTick)) : component;
                        if (mask != null) {
                            if (masks == null) masks = new long[sectionCount][];
                            masks[section] = mask;
                        }
                    }
                }
            }
        } else Arrays.fill(sent.hiddenSince, -1L);
        // The packet builder owns these BitSets; retain independent copies of the client state. Masks are never mutated.
        sent.hidden = hidden == null ? null : (BitSet)hidden.clone();
        sent.partial = partial == null ? null : (BitSet)partial.clone();
        sent.masks = masks;
        sent.componentMasks = componentMasks;
        state.reveals.remove(key); state.rehides.remove(key);
        state.nextDiffTick = 0;
        this.masksChanged(state);
        // a send outside of the chunk loader tick (e.g. a plugin refresh) must not leave the tracker on the old masks
        if (!this.batching) this.publishChangedMasks();
        return hidden == null && masks == null ? null : new SendMasks(hidden, masks);
    }
    // marks component reachability that refers to components rebuilt after the flood; never sent
    private static final long[] STALE_MASK = new long[0];
    /** Client-side state of a section; component culling of a real section stays REAL for the reveal/re-hide logic. */
    private static int actualState(final PlayerVisibility.SentChunk sent, final int section) {
        if (sent.componentMasks != null && sent.componentMasks[section] != null) return PlayerVisibility.REAL;
        return sent.maskState(section);
    }
    /** Cells of the components the player does not reach, null if the section is sent whole, or STALE_MASK. */
    private long @Nullable [] componentMask(final PlayerVisibility state, final ConnectivityCache.@Nullable Column column, final int cx, final int cy, final int cz, final int section, final boolean forSend) {
        if (column == null) return null;
        final SectionConnectivity.Components components = this.connectivity.components(column, section);
        final int reached = forSend ? state.reachedBitsForSend(cx, cy, cz, components) : state.reachedBits(cx, cy, cz, components);
        if (reached == -1) return null;
        if (reached == PlayerVisibility.STALE) return STALE_MASK;
        return components.culledCells(reached);
    }
    /** @return true if every cell of {@code inner} is also set in {@code outer} */
    private static boolean covers(final long @Nullable [] outer, final long @Nullable [] inner) {
        if (inner == null || outer == inner) return true;
        if (outer == null) return false;
        for (int i = 0; i < inner.length; ++i) if ((inner[i] & ~outer[i]) != 0L) return false;
        return true;
    }
    private static boolean sameMask(final long @Nullable [] a, final long @Nullable [] b) {
        return a == b || a != null && b != null && Arrays.equals(a, b);
    }
    /** Cells set in both, or null if none; returns an argument itself where possible and never mutates them. */
    private static long @Nullable [] intersect(final long @Nullable [] a, final long @Nullable [] b) {
        if (a == null || b == null) return null;
        if (a == b || covers(b, a)) return a;
        if (covers(a, b)) return b;
        final long[] ret = new long[a.length];
        long any = 0L;
        for (int i = 0; i < ret.length; ++i) any |= ret[i] = a[i] & b[i];
        return any == 0L ? null : ret;
    }
    /** The client terrain of the player changed, the entity snapshot rebuilds its cell masks. */
    private void masksChanged(final PlayerVisibility state) {
        this.maskVersions.addTo(state.player, 1);
        state.snapshotNeedsRefresh = true;
        this.masksDirty.add(state);
    }
    /**
     * Rebuilds the snapshots of players whose sent masks changed after their snapshot was built (resends of this tick,
     * chunk sends of the player chunk loader) without new line of sight rays, and publishes them. Tick thread only.
     */
    public void publishChangedMasks() {
        checkThread();
        if (this.masksDirty.isEmpty()) { this.batching = false; return; }
        boolean changed = false;
        for (final PlayerVisibility state : this.masksDirty) {
            // the cell masks do not depend on a completed flood, e.g. right after a join or teleport
            if (this.players.get(state.player) != state || state.bypass || !AntiFreecam.entityCulling) continue;
            this.updateEntities(state, 0);
            changed = true;
        }
        this.masksDirty.clear();
        this.batching = false;
        if (!changed) return;
        final Int2ObjectOpenHashMap<VisibilitySnapshot> published = new Int2ObjectOpenHashMap<>(this.order.length);
        for (final PlayerVisibility state : this.players.values()) if (state.snapshot != null) published.put(state.player.getId(), state.snapshot);
        this.snapshots = published;
    }
    /** @return the cells of the section the client holds as filler, or null */
    public long @Nullable [] sentMask(final ServerPlayer player, final int chunkX, final int sectionY, final int chunkZ) {
        checkThread();
        final PlayerVisibility state = this.players.get(player);
        final int index = sectionY - (this.level.getMinY() >> 4);
        if (state == null || state.bypass || player.isSpectator() || index < 0 || index >= this.level.getSectionsCount()) return null;
        final PlayerVisibility.SentChunk sent = state.live.get(ConnectivityCache.key(chunkX, chunkZ));
        return sent == null || sent.masks == null || sent.partial == null || !sent.partial.get(index) ? null : sent.masks[index];
    }
    /** @return true if the client of the player holds the block as filler of a partially culled section */
    public boolean isCellCulled(final ServerPlayer player, final int blockX, final int blockY, final int blockZ) {
        return AntiFreecamHooks.isCulled(this.sentMask(player, blockX >> 4, blockY >> 4, blockZ >> 4), blockX, blockY, blockZ);
    }
    public void onChunkForget(final ServerPlayer player, final int chunkX, final int chunkZ) {
        checkThread();
        final PlayerVisibility state = this.players.get(player);
        if (state == null) return;
        final long key = ConnectivityCache.key(chunkX, chunkZ);
        if (state.live.remove(key) != null) this.masksChanged(state);
        state.reveals.remove(key); state.rehides.remove(key);
    }
    public void onPlayerRemoved(final ServerPlayer player) {
        checkThread();
        if (this.players.remove(player) == null) return;
        this.maskVersions.removeInt(player);
        this.publishedMasks.remove(player);
        this.losChecks.remove(player);
        this.orderDirty = true;
        // Do not retain the removed player in either the published view or the scheduling array.
        this.order = this.players.values().toArray(new PlayerVisibility[0]);
        final Int2ObjectOpenHashMap<VisibilitySnapshot> published = new Int2ObjectOpenHashMap<>(this.snapshots);
        published.remove(player.getId());
        this.snapshots = published;
    }
    public void onBlockChanged(final int x, final int y, final int z, final BlockState oldState, final BlockState newState) {
        checkThread();
        final int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        final boolean surface = this.connectivity.invalidateSurface(cx, y, cz);
        final boolean connectivity = SectionConnectivity.isBarrier(oldState) != SectionConnectivity.isBarrier(newState);
        if (!surface && !connectivity) return;
        if (connectivity) this.connectivity.invalidate(cx, cy - (this.level.getMinY() >> 4), cz);
        if (this.changedCount == this.changedX.length) this.changesOverflow = true;
        else {
            this.changedX[this.changedCount] = cx;
            this.changedY[this.changedCount] = cy;
            this.changedZ[this.changedCount] = cz;
            this.changedConnectivity[this.changedCount] = connectivity;
            this.changedSurface[this.changedCount++] = surface;
        }
    }
    public void onChunkUnload(final int chunkX, final int chunkZ) {
        checkThread();
        this.connectivity.unload(chunkX, chunkZ);
        final long key = ConnectivityCache.key(chunkX, chunkZ);
        for (final PlayerVisibility state : this.players.values()) {
            if (state.live.remove(key) != null) this.masksChanged(state);
            state.reveals.remove(key); state.rehides.remove(key);
            if (state.contains(chunkX, state.minY, chunkZ) || state.workContains(chunkX, state.workMinY, chunkZ)) state.dirty = true;
            state.forgetFrontier(key);
        }
    }
    public boolean isHidden(final ServerPlayer player, final int blockX, final int blockY, final int blockZ) {
        return this.isSectionHidden(player, blockX >> 4, blockY >> 4, blockZ >> 4);
    }
    public boolean isSectionHidden(final ServerPlayer player, final int chunkX, final int sectionY, final int chunkZ) {
        checkThread();
        final PlayerVisibility state = this.players.get(player);
        if (state == null || state.bypass || player.isSpectator() || !state.contains(chunkX, sectionY, chunkZ)) return false;
        if (state.sectionExempt(chunkX, sectionY, chunkZ)) return false;
        if (state.hidden(chunkX, sectionY, chunkZ)) return true;
        final PlayerVisibility.SentChunk sent = state.live.get(ConnectivityCache.key(chunkX, chunkZ));
        return sent != null && sent.hidden != null && sent.hidden.get(sectionY - state.minY);
    }
    /** Persistent block/light updates are suppressed only where the client actually holds filler. */
    public boolean isSectionMasked(final ServerPlayer player, final int chunkX, final int sectionY, final int chunkZ) {
        checkThread();
        final PlayerVisibility state = this.players.get(player);
        final int index = sectionY - (this.level.getMinY() >> 4);
        if (state == null || state.bypass || player.isSpectator() || index < 0 || index >= this.level.getSectionsCount()) return false;
        final PlayerVisibility.SentChunk sent = state.live.get(ConnectivityCache.key(chunkX, chunkZ));
        return sent != null && sent.hidden != null && sent.hidden.get(index);
    }
    /** Per-cell filtering is active only after a packet carrying the partial mask was sent. */
    public boolean isSectionPartial(final ServerPlayer player, final int chunkX, final int sectionY, final int chunkZ) {
        checkThread();
        final PlayerVisibility state = this.players.get(player);
        final int index = sectionY - (this.level.getMinY() >> 4);
        if (state == null || state.bypass || player.isSpectator() || index < 0 || index >= this.level.getSectionsCount()) return false;
        final PlayerVisibility.SentChunk sent = state.live.get(ConnectivityCache.key(chunkX, chunkZ));
        return sent != null && sent.partial != null && sent.partial.get(index);
    }
    /** Registers real client terrain when enabling protection without an initial packet send. */
    public void adoptChunk(final ServerPlayer player, final int chunkX, final int chunkZ) {
        checkThread();
        final PlayerVisibility state = this.state(player);
        final long key = ConnectivityCache.key(chunkX, chunkZ);
        PlayerVisibility.SentChunk sent = state.live.get(key);
        if (sent == null) state.live.put(key, new PlayerVisibility.SentChunk(this.level.getSectionsCount()));
        else { sent.hidden = null; sent.partial = null; sent.masks = null; sent.componentMasks = null; sent.culledSince = null; sent.awaitingResult = false; Arrays.fill(sent.hiddenSince, -1L); }
        this.masksChanged(state);
        state.reveals.remove(key);
        state.rehides.remove(key);
        state.nextDiffTick = 0;
    }
    /** The owner must detach this manager before calling, so the replacement packets are unmasked. */
    public void restoreAll() {
        checkThread();
        for (final PlayerVisibility state : this.players.values()) {
            final var iterator = state.live.long2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                final var entry = iterator.next();
                final PlayerVisibility.SentChunk sent = entry.getValue();
                if ((sent.hidden == null || sent.hidden.isEmpty()) && (sent.partial == null || sent.partial.isEmpty())) continue;
                final long key = entry.getLongKey();
                final LevelChunk chunk = this.connectivity.loaded((int)key, (int)(key >> 32));
                if (chunk != null) ChunkResender.resend(state.player, chunk);
            }
        }
        this.players.clear();
        this.maskVersions.clear();
        this.publishedMasks.clear();
        this.losChecks.clear();
        this.masksDirty.clear();
        this.order = new PlayerVisibility[0];
        this.orderDirty = false;
        this.snapshots = new Int2ObjectOpenHashMap<>();
        this.connectivity.clear();
        this.changedCount = 0;
        this.changesOverflow = false;
        this.rotation = 0;
    }
    public boolean isEntityHidden(final ServerPlayer viewer, final Entity entity) {
        final VisibilitySnapshot snapshot = this.snapshots.get(viewer.getId());
        return snapshot != null && snapshot.viewer == viewer && snapshot.hidden(entity);
    }
    private void diff(final PlayerVisibility state) {
        if (this.tick < state.nextDiffTick) return;
        state.nextDiffTick = Long.MAX_VALUE;
        final var iterator = state.live.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            final long key = entry.getLongKey();
            final int x = (int)key, z = (int)(key >> 32);
            final PlayerVisibility.SentChunk sent = entry.getValue();
            final int minSection = this.level.getMinY() >> 4;
            if (state.bypass || state.contains(x, minSection, z) || !state.workContains(x, minSection, z)) sent.awaitingResult = false;
            final ConnectivityCache.Column pendingColumn = sent.awaitingResult ? this.connectivity.resolve(x, z) : null;
            final int pendingSurface = pendingColumn == null ? Integer.MAX_VALUE : this.connectivity.minimumSurface(pendingColumn) >> 4;
            boolean reveal = false, rehide = false;
            ConnectivityCache.Column componentColumn = pendingColumn;
            boolean componentColumnResolved = pendingColumn != null;
            for (int section = 0; section < sent.hiddenSince.length; ++section) {
                final int actual = actualState(sent, section);
                // An unfinished flood is not a reveal. The current surface/near-player exceptions
                // and already proven paths still update pending chunks under the resend budgets.
                final int desired = sent.awaitingResult
                    ? pendingColumn == null ? actual : state.maskStateForSend(x, minSection + section, z, pendingSurface)
                    : state.maskState(x, minSection + section, z);
                // Leaving the completed cube proves neither reachability nor occlusion. Freeze the
                // actual client state (and cancel queued transitions) until a known result covers it.
                final int target = desired == PlayerVisibility.UNKNOWN ? actual : desired;
                if (target == PlayerVisibility.REAL && actual == PlayerVisibility.REAL && !state.bypass
                    && (desired != PlayerVisibility.UNKNOWN) && (!sent.awaitingResult || pendingColumn != null)) {
                    // Real sections can still differ in the components that are sent as filler.
                    final long[] held = sent.componentMasks == null ? null : sent.componentMasks[section];
                    if (!componentColumnResolved) { componentColumn = this.connectivity.resolve(x, z); componentColumnResolved = true; }
                    long[] wanted = this.componentMask(state, componentColumn, x, minSection + section, z, section, sent.awaitingResult);
                    // stale bits wait for the flood that the topology change started
                    if (wanted == STALE_MASK) wanted = held;
                    if (sameMask(held, wanted)) {
                        if (sent.culledSince != null) sent.culledSince[section] = -1L;
                    } else if (!covers(wanted, held)) {
                        // the client holds filler where a component is reached now
                        reveal = true;
                        if (sent.culledSince != null) sent.culledSince[section] = -1L;
                    } else {
                        if (sent.culledSince == null) { sent.culledSince = new long[sent.hiddenSince.length]; Arrays.fill(sent.culledSince, -1L); }
                        if (sent.culledSince[section] < 0) sent.culledSince[section] = this.tick;
                        final long deadline = sent.culledSince[section] + AntiFreecam.rehideDelayTicks;
                        if (this.tick >= deadline) rehide = true;
                        else state.nextDiffTick = Math.min(state.nextDiffTick, deadline);
                    }
                }
                if (target <= actual) {
                    sent.hiddenSince[section] = -1L;
                    if (target < actual) reveal = true;
                } else {
                    if (sent.hiddenSince[section] < 0) sent.hiddenSince[section] = this.tick;
                    final long deadline = sent.hiddenSince[section] + AntiFreecam.rehideDelayTicks;
                    if (this.tick >= deadline) rehide = true;
                    else state.nextDiffTick = Math.min(state.nextDiffTick, deadline);
                }
            }
            if (reveal) { state.reveals.add(key); state.rehides.remove(key); }
            else {
                state.reveals.remove(key);
                if (rehide) state.rehides.add(key); else state.rehides.remove(key);
            }
        }
    }
    private int drain(final PlayerVisibility state, final boolean reveal, final int limit) {
        final var queue = reveal ? state.reveals : state.rehides;
        int sent = 0;
        while (sent < limit && !queue.isEmpty()) {
            final long key = queue.removeFirstLong();
            if (!state.live.containsKey(key)) continue;
            final LevelChunk chunk = this.connectivity.loaded((int)key, (int)(key >> 32));
            if (chunk == null) continue;
            ChunkResender.resend(state.player, chunk);
            ++sent;
        }
        return sent;
    }
    private boolean selectCandidate(final Entity entity) {
        return this.querySeen++ >= this.querySkip;
    }
    private void updateEntities(final PlayerVisibility state, final int budget) {
        final ServerPlayer player = state.player;
        final double radius = AntiFreecam.entityLineOfSightDistance;
        final double trackingRadius = (state.requestedRadius - 1) * 16.0;
        this.candidates.clear();
        if (budget > 0) {
            this.querySkip = state.entityCursor;
            this.querySeen = 0;
            // Tracking is capped horizontally by send view distance, not by the LOS sphere, and
            // can span the world's full height. Follow the current player position even while its
            // completed flood lags behind; actual partial masks can outlive that completed cube.
            // The shared candidate budget bounds lookups; only nearby candidates cast LOS rays.
            this.level.moonrise$getEntityLookup().getEntities(player, new AABB(
                player.getX() - trackingRadius, this.level.getMinY(), player.getZ() - trackingRadius,
                player.getX() + trackingRadius, this.level.getMinY() + this.level.getHeight(), player.getZ() + trackingRadius),
                this.candidates, this.candidateSelector, budget);
            final int count = this.candidates.size();
            state.entityCursor = count < budget || state.entityCursor > Integer.MAX_VALUE - count ? 0 : state.entityCursor + count;
        }
        final int count = this.candidates.size();
        final IntOpenHashSet exempt = new IntOpenHashSet();
        final Entity root = player.getRootVehicle();
        exempt.add(root.getId());
        addPassengers(root, exempt);
        final long oldest = this.tick - AntiFreecam.entityLingerTicks;
        final var seenIterator = state.lastSeen.int2LongEntrySet().fastIterator();
        while (seenIterator.hasNext()) if (seenIterator.next().getLongValue() < oldest) seenIterator.remove();
        // Retain negative results across rotating windows. Remove them only when their entity leaves
        // the relevant region, is removed/exempt, or receives a positive LOS result.
        final var blockedIterator = state.occluded.iterator();
        while (blockedIterator.hasNext()) {
            final int id = blockedIterator.nextInt();
            final Entity entity = this.level.getEntity(id);
            if (entity == null || entity.isRemoved() || exempt.contains(id) || !state.contains(PlayerVisibility.section(entity.getX()), PlayerVisibility.section(entity.getY()), PlayerVisibility.section(entity.getZ()))) {
                blockedIterator.remove();
                continue;
            }
            final double dx = entity.getX() - player.getX(), dy = entity.getY() - player.getEyeY(), dz = entity.getZ() - player.getZ();
            final double distance = dx * dx + dy * dy + dz * dz;
            if (distance > radius * radius || distance < AntiFreecam.entityAlwaysVisibleDistance * AntiFreecam.entityAlwaysVisibleDistance) blockedIterator.remove();
        }
        final var partialIterator = state.partialOccluded.iterator();
        while (partialIterator.hasNext()) {
            final int id = partialIterator.nextInt();
            final Entity entity = this.level.getEntity(id);
            if (entity == null || entity.isRemoved() || exempt.contains(id)
                || Math.abs(entity.getX() - player.getX()) > trackingRadius
                || Math.abs(entity.getZ() - player.getZ()) > trackingRadius) partialIterator.remove();
        }
        it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap checked = this.losChecks.get(player);
        if (checked != null && this.tick % 200L == 0L) {
            // forget entities that were not checked for a while (left the range or were removed)
            final var checkedIterator = checked.int2LongEntrySet().fastIterator();
            while (checkedIterator.hasNext()) if (this.tick - checkedIterator.next().getLongValue() > 200L) checkedIterator.remove();
        }
        for (int n = 0; n < count; ++n) {
            final Entity entity = this.candidates.get(n);
            if (entity == player || exempt.contains(entity.getId())) continue;
            final double dx = entity.getX() - player.getX(), dy = entity.getY() - player.getEyeY(), dz = entity.getZ() - player.getZ();
            final double distance = dx * dx + dy * dy + dz * dz;
            final int id = entity.getId();
            final int cx = PlayerVisibility.section(entity.getX()), cy = PlayerVisibility.section(entity.getY()), cz = PlayerVisibility.section(entity.getZ());
            if (distance < AntiFreecam.entityAlwaysVisibleDistance * AntiFreecam.entityAlwaysVisibleDistance || state.hidden(cx, cy, cz)) {
                state.partialOccluded.remove(id);
                continue;
            }
            // The cells the client holds as filler are authoritative; a section that is about to become partial
            // is covered by its sky light mask while its packet waits in the resend queue.
            final int blockX = (int)Math.floor(entity.getX()), blockY = (int)Math.floor(entity.getY()), blockZ = (int)Math.floor(entity.getZ());
            if (this.isCellCulled(player, blockX, blockY, blockZ)
                || state.maskState(cx, cy, cz) == PlayerVisibility.PARTIAL && AntiFreecamHooks.partialCulling(this.level).isCulled(blockX, blockY, blockZ)) {
                state.partialOccluded.add(id);
                continue; // Cell filler is authoritative: no LOS rays, distance limit or linger.
            }
            state.partialOccluded.remove(id);
            if (distance > radius * radius || !state.contains(cx, cy, cz)) continue;
            // Farther entities are checked less often; their last result (occluded or seen) is kept in between.
            final int stride = distance > FAR_LOS_DISTANCE * FAR_LOS_DISTANCE ? FAR_LOS_STRIDE : distance > NEAR_LOS_DISTANCE * NEAR_LOS_DISTANCE ? MID_LOS_STRIDE : 1;
            if (stride > 1) {
                if (checked == null) checked = this.losChecks.computeIfAbsent(player, k -> new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap());
                final long last = checked.getOrDefault(id, Long.MIN_VALUE);
                if (last != Long.MIN_VALUE && this.tick - last < (long)stride * AntiFreecam.entityCheckIntervalTicks) continue;
                checked.put(id, this.tick);
            }
            if (this.lineOfSight(player, entity, stride < FAR_LOS_STRIDE)) {
                state.lastSeen.put(entity.getId(), this.tick);
                state.occluded.remove(entity.getId());
            } else if (!state.lastSeen.containsKey(entity.getId())) state.occluded.add(entity.getId());
        }
        final IntOpenHashSet blocked = new IntOpenHashSet(state.occluded);
        blocked.addAll(state.partialOccluded);
        state.snapshot = new VisibilitySnapshot(state, blocked, exempt, AntiFreecam.entityAlwaysVisibleDistance, this.publishedMasks(state));
        state.snapshotNeedsRefresh = false;
        this.candidates.clear();
    }
    /**
     * Immutable index of the culled cells the client of the player holds, by section key, for the tracker threads.
     * Rebuilt only when a sent mask changed.
     */
    private it.unimi.dsi.fastutil.longs.@Nullable Long2ObjectOpenHashMap<long[]> publishedMasks(final PlayerVisibility state) {
        final int version = this.maskVersions.getInt(state.player);
        final PublishedMasks published = this.publishedMasks.get(state.player);
        if (published != null && published.version() == version) return published.masks();
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<long[]> masks = null;
        final int minSection = this.level.getMinY() >> 4;
        final var iterator = state.live.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            final PlayerVisibility.SentChunk sent = entry.getValue();
            if (sent.masks == null || sent.partial == null) continue;
            final long key = entry.getLongKey();
            final int chunkX = (int)key, chunkZ = (int)(key >> 32);
            for (int section = sent.partial.nextSetBit(0); section >= 0 && section < sent.masks.length; section = sent.partial.nextSetBit(section + 1)) {
                final long[] mask = sent.masks[section];
                if (mask == null) continue;
                if (masks == null) masks = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
                masks.put(net.minecraft.core.SectionPos.asLong(chunkX, minSection + section, chunkZ), mask);
            }
        }
        this.publishedMasks.put(state.player, new PublishedMasks(version, masks));
        return masks;
    }
    private static void addPassengers(final Entity parent, final IntOpenHashSet exempt) {
        final var passengers = parent.getPassengers();
        for (int i = 0; i < passengers.size(); ++i) {
            final Entity passenger = passengers.get(i);
            if (exempt.add(passenger.getId())) addPassengers(passenger, exempt);
        }
    }
    /** @param corners false for far entities: they cover a few pixels, the center and top center are enough */
    private boolean lineOfSight(final ServerPlayer player, final Entity entity, final boolean corners) {
        final AABB box = entity.getBoundingBox();
        if (this.ray(player.getX(), player.getEyeY(), player.getZ(), (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5)) return true;
        if (!corners) return this.ray(player.getX(), player.getEyeY(), player.getZ(), (box.minX + box.maxX) * 0.5, box.maxY, (box.minZ + box.maxZ) * 0.5);
        for (int corner = 0; corner < 8; ++corner) {
            if (this.ray(player.getX(), player.getEyeY(), player.getZ(), (corner & 1) == 0 ? box.minX : box.maxX,
                (corner & 2) == 0 ? box.minY : box.maxY, (corner & 4) == 0 ? box.minZ : box.maxZ)) return true;
        }
        return false;
    }
    /** Missing terrain and rays exceeding the fixed work cap fail open. No chunk loads or BlockPos allocations. */
    private boolean ray(final double sx, final double sy, final double sz, final double ex, final double ey, final double ez) {
        int x = (int)Math.floor(sx), y = (int)Math.floor(sy), z = (int)Math.floor(sz);
        final int endX = (int)Math.floor(ex), endY = (int)Math.floor(ey), endZ = (int)Math.floor(ez);
        final double dx = ex - sx, dy = ey - sy, dz = ez - sz;
        final int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        final int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        final int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        final double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        final double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        final double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tx = stepX == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? x + 1.0 - sx : sx - x) * deltaX);
        double ty = stepY == 0 ? Double.POSITIVE_INFINITY : ((stepY > 0 ? y + 1.0 - sy : sy - y) * deltaY);
        double tz = stepZ == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? z + 1.0 - sz : sz - z) * deltaZ);
        int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;
        LevelChunk chunk = null;
        for (int steps = 0; steps < 512; ++steps) {
            if (x == endX && y == endY && z == endZ) return true;
            final int cx = x >> 4, cz = z >> 4;
            if (cx != lastChunkX || cz != lastChunkZ) {
                chunk = this.connectivity.loaded(cx, cz); lastChunkX = cx; lastChunkZ = cz;
            }
            if (chunk == null) return true;
            final int section = (y >> 4) - (this.level.getMinY() >> 4);
            if (section < 0 || section >= this.level.getSectionsCount()) return true;
            final net.minecraft.world.level.chunk.LevelChunkSection sec = chunk.getSections()[section];
            if (steps != 0 && sec != null && SectionConnectivity.isBarrier(sec.getBlockState(x & 15, y & 15, z & 15))) return false;
            if (tx <= ty && tx <= tz) { x += stepX; tx += deltaX; }
            else if (ty <= tz) { y += stepY; ty += deltaY; }
            else { z += stepZ; tz += deltaZ; }
        }
        return true;
    }
}
