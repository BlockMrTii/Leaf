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
    // Allocated once; the lookup invokes this only on the owning tick thread.
    private final Predicate<Entity> candidateSelector = this::selectCandidate;
    private int querySkip, querySeen;
    private int[] resendCounts = new int[0];
    private final ServerLevel level;
    private final ConnectivityCache connectivity;
    private final Reference2ObjectOpenHashMap<ServerPlayer, PlayerVisibility> players = new Reference2ObjectOpenHashMap<>();
    private volatile Int2ObjectOpenHashMap<VisibilitySnapshot> snapshots = new Int2ObjectOpenHashMap<>();
    private final ObjectArrayList<Entity> candidates = new ObjectArrayList<>();
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
        TickThread.ensureTickThread("Cannot create anti-freecam manager async");
        this.level = level;
        this.connectivity = new ConnectivityCache(level);
    }
    private static void checkThread() { TickThread.ensureTickThread("Cannot access anti-freecam state async"); }
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
        if (!state.bypass) {
            for (int section = 0; section < this.level.getSectionsCount(); ++section) {
                final int target = state.maskStateForSend(x, (this.level.getMinY() >> 4) + section, z, surfaceSection);
                final int actual = sent.maskState(section);
                int outgoing = target;
                if (target <= actual || firstSend) sent.hiddenSince[section] = -1L;
                else {
                    if (sent.hiddenSince[section] < 0) sent.hiddenSince[section] = this.tick;
                    // A reveal in this packet must not prematurely apply another section's re-hide.
                    if (this.tick - sent.hiddenSince[section] < AntiFreecam.rehideDelayTicks) outgoing = actual;
                    else sent.hiddenSince[section] = -1L;
                }
                if (outgoing == PlayerVisibility.HIDDEN) {
                    if (hidden == null) hidden = new BitSet(this.level.getSectionsCount());
                    hidden.set(section);
                } else if (outgoing == PlayerVisibility.PARTIAL) {
                    if (partial == null) partial = new BitSet(this.level.getSectionsCount());
                    partial.set(section);
                }
            }
        } else Arrays.fill(sent.hiddenSince, -1L);
        // The packet builder owns these BitSets; retain independent copies of the client state.
        sent.hidden = hidden == null ? null : (BitSet)hidden.clone();
        sent.partial = partial == null ? null : (BitSet)partial.clone();
        state.reveals.remove(key); state.rehides.remove(key);
        state.nextDiffTick = 0;
        return hidden == null && partial == null ? null : new SendMasks(hidden, partial);
    }
    public void onChunkForget(final ServerPlayer player, final int chunkX, final int chunkZ) {
        checkThread();
        final PlayerVisibility state = this.players.get(player);
        if (state == null) return;
        final long key = ConnectivityCache.key(chunkX, chunkZ);
        state.live.remove(key); state.reveals.remove(key); state.rehides.remove(key);
    }
    public void onPlayerRemoved(final ServerPlayer player) {
        checkThread();
        if (this.players.remove(player) == null) return;
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
        final boolean connectivity = oldState.isSolidRender() != newState.isSolidRender();
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
            state.live.remove(key); state.reveals.remove(key); state.rehides.remove(key);
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
        else { sent.hidden = null; sent.partial = null; sent.awaitingResult = false; Arrays.fill(sent.hiddenSince, -1L); }
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
            for (int section = 0; section < sent.hiddenSince.length; ++section) {
                final int actual = sent.maskState(section);
                // An unfinished flood is not a reveal. The current surface/near-player exceptions
                // and already proven paths still update pending chunks under the resend budgets.
                final int desired = sent.awaitingResult
                    ? pendingColumn == null ? actual : state.maskStateForSend(x, minSection + section, z, pendingSurface)
                    : state.maskState(x, minSection + section, z);
                // Leaving the completed cube proves neither reachability nor occlusion. Freeze the
                // actual client state (and cancel queued transitions) until a known result covers it.
                final int target = desired == PlayerVisibility.UNKNOWN ? actual : desired;
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
            // Also cover a still-sent partial section while its reveal waits in the resend queue.
            final boolean partial = state.maskState(cx, cy, cz) == PlayerVisibility.PARTIAL || this.isSectionPartial(player, cx, cy, cz);
            if (partial && AntiFreecamHooks.partialCulling(this.level).isCulled(
                (int)Math.floor(entity.getX()), (int)Math.floor(entity.getY()), (int)Math.floor(entity.getZ()))) {
                state.partialOccluded.add(id);
                continue; // Cell filler is authoritative: no LOS rays, distance limit or linger.
            }
            state.partialOccluded.remove(id);
            if (distance > radius * radius || !state.contains(cx, cy, cz)) continue;
            if (this.lineOfSight(player, entity)) {
                state.lastSeen.put(entity.getId(), this.tick);
                state.occluded.remove(entity.getId());
            } else if (!state.lastSeen.containsKey(entity.getId())) state.occluded.add(entity.getId());
        }
        final IntOpenHashSet blocked = new IntOpenHashSet(state.occluded);
        blocked.addAll(state.partialOccluded);
        state.snapshot = new VisibilitySnapshot(state, blocked, exempt, AntiFreecam.entityAlwaysVisibleDistance);
        state.snapshotNeedsRefresh = false;
        this.candidates.clear();
    }
    private static void addPassengers(final Entity parent, final IntOpenHashSet exempt) {
        final var passengers = parent.getPassengers();
        for (int i = 0; i < passengers.size(); ++i) {
            final Entity passenger = passengers.get(i);
            if (exempt.add(passenger.getId())) addPassengers(passenger, exempt);
        }
    }
    private boolean lineOfSight(final ServerPlayer player, final Entity entity) {
        final AABB box = entity.getBoundingBox();
        if (this.ray(player.getX(), player.getEyeY(), player.getZ(), (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5)) return true;
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
            if (chunk.getSections()[section].getBlockState(x & 15, y & 15, z & 15).isSolidRender()) return false;
            if (tx <= ty && tx <= tz) { x += stepX; tx += deltaX; }
            else if (ty <= tz) { y += stepY; ty += deltaY; }
            else { z += stepZ; tz += deltaZ; }
        }
        return true;
    }
}
