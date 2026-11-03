package org.dreeam.leaf.world.antifreecam;

import ca.spottedleaf.moonrise.common.PlatformHooks;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerPlayer;
import org.dreeam.leaf.config.modules.misc.AntiFreecam;
import org.jspecify.annotations.Nullable;
import java.util.Arrays;
import java.util.BitSet;

/** Mutable tick-thread state; only snapshot is read by tracker threads. */
final class PlayerVisibility {
    // Ordered by decreasing client visibility, allowing reveal/re-hide comparisons without allocation.
    static final int UNKNOWN = -1, REAL = 0, PARTIAL = 1, HIDDEN = 2;
    static final int STALE = -2;
    private static final int MOVEMENT_REBUILD_INTERVAL = 10;
    private static final int MAX_FRONTIER_NODES = 65536;
    private static final int MAX_CONTINUATION_QUEUE = 131072;
    static final int[] DX = {0, 0, 0, 0, -1, 1};
    static final int[] DY = {-1, 1, 0, 0, 0, 0};
    static final int[] DZ = {0, 0, -1, 1, 0, 0};
    final ServerPlayer player;
    final Long2ObjectOpenHashMap<SentChunk> live = new Long2ObjectOpenHashMap<>();
    final LongLinkedOpenHashSet reveals = new LongLinkedOpenHashSet();
    final LongLinkedOpenHashSet rehides = new LongLinkedOpenHashSet();
    final Int2LongOpenHashMap lastSeen = new Int2LongOpenHashMap();
    final IntOpenHashSet occluded = new IntOpenHashSet();
    // Cell-mask negatives are independent of LOS range and linger, and survive rotating query pages.
    final IntOpenHashSet partialOccluded = new IntOpenHashSet();
    volatile VisibilitySnapshot snapshot;
    boolean bypass, permissionBypass, dirty = true, running, hasResult, snapshotNeedsRefresh;
    boolean visibilityDirty = true;
    private boolean continuationOverflow;
    long permissionTick = Long.MIN_VALUE / 2;
    long nextDiffTick, lastFullStartTick = Long.MIN_VALUE / 2;
    // Raw reachability stays separate from the effective surface/near-player exceptions.
    int x, y, z, radius, width, height, minY;
    int workX, workY, workZ, workRadius, workWidth, workHeight, workMinY;
    int requestedX = Integer.MIN_VALUE, requestedY, requestedZ, requestedRadius;
    long[] visible = new long[0], reachable = new long[0], work = new long[0], queue = new long[1024];
    // Two words hold 15 components * 6 incoming faces per section slot.
    long[] visited = new long[0], completedVisited = new long[0];
    private short[] reachedComponents = new short[0], workReachedComponents = new short[0];
    private short[] allComponents = new short[0], workAllComponents = new short[0];
    private int[] componentIds = new int[0], workComponentIds = new int[0];
    private int eyeCell, workEyeCell;
    private ConnectivityCache.Column[] columns = new ConnectivityCache.Column[0], workColumns = new ConnectivityCache.Column[0];
    private long[] columnPasses = new long[0], workColumnPasses = new long[0];
    private int[] surfaceSections = new int[0];
    private Frontier frontier = new Frontier(), workFrontier = new Frontier();
    private long[] continuationQueue = new long[1024];
    private int continuationHead, continuationTail;
    int head, tail, entityCursor;

    private static final class Frontier {
        final Long2ObjectOpenHashMap<LongArrayList> nodes = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet missing = new LongOpenHashSet();
        final LongOpenHashSet incomplete = new LongOpenHashSet();
        int size;
        void add(final long key, final long node) {
            this.missing.add(key);
            if (this.size == MAX_FRONTIER_NODES) { this.incomplete.add(key); return; }
            LongArrayList list = this.nodes.get(key);
            if (list == null) { list = new LongArrayList(4); this.nodes.put(key, list); }
            list.add(node);
            ++this.size;
        }
        LongArrayList take(final long key) {
            this.missing.remove(key);
            this.incomplete.remove(key);
            final LongArrayList list = this.nodes.remove(key);
            if (list != null) this.size -= list.size();
            return list;
        }
        void clear() { this.nodes.clear(); this.missing.clear(); this.incomplete.clear(); this.size = 0; }
    }
    static final class SentChunk {
        BitSet hidden, partial;
        // Written only by AntiFreecamManager: per section, filler cells held by the client (null = none).
        long @Nullable [][] masks;
        // Component part of masks, used to diff against current reachability (null = none).
        long @Nullable [][] componentMasks;
        // Pending component re-hide start ticks, -1 = none; allocated lazily by the manager.
        long @Nullable [] culledSince;
        int maskState(final int section) {
            return this.hidden != null && this.hidden.get(section) ? HIDDEN
                : this.partial != null && this.partial.get(section) ? PARTIAL : REAL;
        }
        boolean awaitingResult;
        final long[] hiddenSince;
        SentChunk(final int height) { this.hiddenSince = new long[height]; Arrays.fill(this.hiddenSince, -1L); }
    }
    PlayerVisibility(final ServerPlayer player) { this.player = player; }
    static int section(final double value) { return (int)Math.floor(value) >> 4; }
    boolean contains(final int cx, final int cy, final int cz) {
        return this.hasResult && cx >= this.x - this.radius && cx <= this.x + this.radius && cz >= this.z - this.radius && cz <= this.z + this.radius && cy >= this.minY && cy < this.minY + this.height;
    }
    boolean workContains(final int cx, final int cy, final int cz) {
        return cx >= this.workX - this.workRadius && cx <= this.workX + this.workRadius && cz >= this.workZ - this.workRadius && cz <= this.workZ + this.workRadius && cy >= this.workMinY && cy < this.workMinY + this.workHeight;
    }
    int index(final int cx, final int cy, final int cz) {
        return ((cz - this.z + this.radius) * this.width + cx - this.x + this.radius) * this.height + cy - this.minY;
    }
    private boolean nearPlayer(final int cx, final int cy, final int cz) {
        final int r = AntiFreecam.alwaysVisibleRadius;
        return Math.abs(cx - this.requestedX) <= r && Math.abs(cy - this.requestedY) <= r && Math.abs(cz - this.requestedZ) <= r;
    }
    boolean sectionExempt(final int cx, final int cy, final int cz) {
        if (this.nearPlayer(cx, cy, cz)) return true;
        if (!this.contains(cx, cy, cz)) return false;
        return cy >= this.surfaceSections[(cz - this.z + this.radius) * this.width + cx - this.x + this.radius];
    }
    boolean hidden(final int cx, final int cy, final int cz) {
        if (this.bypass || !this.contains(cx, cy, cz) || this.sectionExempt(cx, cy, cz)) return false;
        final int index = this.index(cx, cy, cz);
        return (this.visible[index >>> 6] & 1L << index) == 0;
    }
    int maskState(final int cx, final int cy, final int cz) {
        if (this.bypass) return REAL;
        if (!this.contains(cx, cy, cz)) return UNKNOWN;
        final int columnIndex = (cz - this.z + this.radius) * this.width + cx - this.x + this.radius;
        return this.maskStateForSend(cx, cy, cz, this.surfaceSections[columnIndex]);
    }
    int maskStateForSend(final int cx, final int cy, final int cz, final int surfaceSection) {
        if (this.bypass || this.nearPlayer(cx, cy, cz)) return REAL;
        if (this.contains(cx, cy, cz)) {
            final int index = this.index(cx, cy, cz);
            if ((this.reachable[index >>> 6] & 1L << index) != 0) return REAL;
        } else {
            // Before completion, only already proven paths are real. Unknown surface sections use
            // per-cell culling instead of full filler; the near-player exception still wins above.
            if (!this.running || !this.workContains(cx, cy, cz)) return cy >= surfaceSection ? PARTIAL : HIDDEN;
            final int index = ((cz - this.workZ + this.workRadius) * this.workWidth + cx - this.workX + this.workRadius) * this.workHeight + cy - this.workMinY;
            if ((this.work[index >>> 6] & 1L << index) != 0) return REAL;
        }
        return cy >= surfaceSection ? PARTIAL : HIDDEN;
    }
    /** Uses the same completed/work selection as maskStateForSend; STALE rejects rebuilt component labels. */
    int reachedBitsForSend(final int cx, final int cy, final int cz, final SectionConnectivity.Components current) {
        if (this.bypass || this.nearPlayer(cx, cy, cz)) return -1;
        if (this.contains(cx, cy, cz)) return this.reachedBits(cx, cy, cz, current);
        if (!this.running || !this.workContains(cx, cy, cz)) return -1;
        final int index = ((cz - this.workZ + this.workRadius) * this.workWidth + cx - this.workX + this.workRadius) * this.workHeight + cy - this.workMinY;
        final int all = this.workAllComponents[index];
        if (all == 0) return -1;
        if (this.workComponentIds[index] != current.id()) return STALE;
        final int reached = this.workReachedComponents[index];
        return reached == all ? -1 : reached;
    }
    /** Completed reachability, or STALE after a rebuild; near-player and out-of-window cells stay unmasked. */
    int reachedBits(final int cx, final int cy, final int cz, final SectionConnectivity.Components current) {
        if (this.bypass || this.nearPlayer(cx, cy, cz) || !this.contains(cx, cy, cz)) return -1;
        final int index = this.index(cx, cy, cz);
        final int all = this.allComponents[index];
        if (all == 0) return -1;
        if (this.componentIds[index] != current.id()) return STALE;
        final int reached = this.reachedComponents[index];
        return reached == all ? -1 : reached;
    }
    boolean resultIsCurrent() {
        return this.hasResult && this.x == this.requestedX && this.y == this.requestedY && this.z == this.requestedZ && this.radius == this.requestedRadius;
    }
    boolean resultCoversCurrent() { return this.contains(this.requestedX, this.requestedY, this.requestedZ); }
    void request(final int minY, final int height, final long tick) { this.request(minY, height, tick, false); }
    private void request(final int minY, final int height, final long tick, final boolean force) {
        final int cx = section(this.player.getX()), cy = Math.clamp(section(this.player.getEyeY()), minY, minY + height - 1), cz = section(this.player.getZ());
        final int radius = Math.max(1, PlatformHooks.get().getSendViewDistance(this.player) + 1);
        if (cx != this.requestedX || cy != this.requestedY || cz != this.requestedZ || radius != this.requestedRadius) {
            this.requestedX = cx; this.requestedY = cy; this.requestedZ = cz; this.requestedRadius = radius;
            this.visibilityDirty = true;
            this.snapshotNeedsRefresh = true;
            this.nextDiffTick = 0;
            // Keep both the immutable snapshot and the completed flood while moving within its window.
        }
        final boolean urgent = !this.hasResult || Math.abs(cx - this.x) > 2 || Math.abs(cz - this.z) > 2
            || Math.abs(cy - this.y) > 1 || radius != this.radius;
        final boolean sameWork = this.workX == cx && this.workY == cy && this.workZ == cz && this.workRadius == radius;
        if (this.running && (force || urgent) && !sameWork) this.running = false;
        if (this.running) return;
        // Ten ticks bounds redundant moving floods without delaying topology changes or large jumps.
        if (!this.dirty && (this.resultIsCurrent() || !force && !urgent && tick - this.lastFullStartTick < MOVEMENT_REBUILD_INTERVAL)) return;
        this.workX = cx; this.workY = cy; this.workZ = cz; this.workRadius = radius;
        this.workWidth = radius * 2 + 1; this.workHeight = height; this.workMinY = minY;
        final int count = this.workWidth * this.workWidth * height;
        if (this.visited.length != count * 2) this.visited = new long[count * 2];
        else Arrays.fill(this.visited, 0L);
        if (this.workReachedComponents.length != count) {
            this.workReachedComponents = new short[count];
            this.workAllComponents = new short[count];
            this.workComponentIds = new int[count];
        } else {
            Arrays.fill(this.workReachedComponents, (short)0);
            Arrays.fill(this.workAllComponents, (short)0);
            Arrays.fill(this.workComponentIds, 0);
        }
        this.workEyeCell = (((int)Math.floor(this.player.getEyeY()) & 15) << 8)
            | (((int)Math.floor(this.player.getZ()) & 15) << 4) | ((int)Math.floor(this.player.getX()) & 15);
        final int words = (count + 63) >>> 6;
        if (this.work.length != words) this.work = new long[words];
        else Arrays.fill(this.work, 0L);
        final int columnCount = this.workWidth * this.workWidth;
        if (this.workColumns.length != columnCount) {
            this.workColumns = new ConnectivityCache.Column[columnCount];
            this.workColumnPasses = new long[columnCount];
        } else {
            Arrays.fill(this.workColumns, null);
            Arrays.fill(this.workColumnPasses, 0L);
        }
        this.workFrontier.clear();
        this.head = this.tail = 0;
        final int root = ((cz - this.workZ + radius) * this.workWidth + cx - this.workX + radius) * height + cy - minY;
        // Component 15 is the root sentinel, resolved only after its chunk is available.
        this.enqueue(((long)root << 13) | (15L << 9) | 6L, true);
        this.lastFullStartTick = tick;
        this.dirty = false; this.running = true;
    }
    private ConnectivityCache.Column column(final ConnectivityCache cache, final int index, final boolean working) {
        final ConnectivityCache.Column[] columns = working ? this.workColumns : this.columns;
        final long[] passes = working ? this.workColumnPasses : this.columnPasses;
        if (passes[index] != cache.pass()) {
            final int width = working ? this.workWidth : this.width, radius = working ? this.workRadius : this.radius;
            final int cx = (working ? this.workX : this.x) - radius + index % width;
            final int cz = (working ? this.workZ : this.z) - radius + index / width;
            columns[index] = cache.resolve(cx, cz);
            passes[index] = cache.pass();
        }
        return columns[index];
    }
    private ConnectivityCache.Column column(final ConnectivityCache cache, final long key, final boolean working) {
        final int cx = (int)key, cz = (int)(key >> 32);
        final int centerX = working ? this.workX : this.x, centerZ = working ? this.workZ : this.z;
        final int width = working ? this.workWidth : this.width, radius = working ? this.workRadius : this.radius;
        if (cx < centerX - radius || cx > centerX + radius || cz < centerZ - radius || cz > centerZ + radius) return null;
        return this.column(cache, (cz - centerZ + radius) * width + cx - centerX + radius, working);
    }
    private static void setRange(final long[] bits, final int from, final int to) {
        if (from >= to) return;
        final int first = from >>> 6, last = (to - 1) >>> 6;
        final long start = -1L << from, end = -1L >>> (64 - (to & 63));
        if (first == last) { bits[first] |= start & end; return; }
        bits[first] |= start;
        for (int word = first + 1; word < last; ++word) bits[word] = -1L;
        bits[last] |= end;
    }
    void refreshVisibility(final ConnectivityCache cache) {
        if (!this.hasResult || !this.visibilityDirty) return;
        if (this.visible.length != this.reachable.length) this.visible = new long[this.reachable.length];
        System.arraycopy(this.reachable, 0, this.visible, 0, this.reachable.length);
        if (this.surfaceSections.length != this.columns.length) this.surfaceSections = new int[this.columns.length];
        for (int index = 0; index < this.columns.length; ++index) {
            final ConnectivityCache.Column column = this.column(cache, index, false);
            this.surfaceSections[index] = column == null ? Integer.MAX_VALUE : cache.minimumSurface(column) >> 4;
            if (column == null) continue;
            final int first = Math.clamp(this.surfaceSections[index] - this.minY, 0, this.height);
            setRange(this.visible, index * this.height + first, (index + 1) * this.height);
        }
        final int r = AntiFreecam.alwaysVisibleRadius;
        for (int dz = -r; dz <= r; ++dz) for (int dx = -r; dx <= r; ++dx) for (int dy = -r; dy <= r; ++dy) {
            final int cx = this.requestedX + dx, cy = this.requestedY + dy, cz = this.requestedZ + dz;
            if (!this.contains(cx, cy, cz)) continue;
            final int index = this.index(cx, cy, cz);
            this.visible[index >>> 6] |= 1L << index;
        }
        this.visibilityDirty = false;
        this.nextDiffTick = 0;
        this.snapshotNeedsRefresh = true;
    }
    /** Refresh the just-sent column even if its surface changed after the last manager tick. */
    void refreshSurfaceChunk(final ConnectivityCache cache, final int cx, final int cz, final ConnectivityCache.Column column) {
        if (!this.contains(cx, this.minY, cz)) return;
        final int columnIndex = (cz - this.z + this.radius) * this.width + cx - this.x + this.radius;
        this.columns[columnIndex] = column;
        this.columnPasses[columnIndex] = cache.pass();
        final int surfaceSection = cache.minimumSurface(column) >> 4;
        this.surfaceSections[columnIndex] = surfaceSection;
        boolean changed = false;
        for (int section = 0; section < this.height; ++section) {
            final int index = columnIndex * this.height + section;
            final long bit = 1L << index;
            final boolean show = (this.reachable[index >>> 6] & bit) != 0 || this.minY + section >= surfaceSection || this.nearPlayer(cx, this.minY + section, cz);
            final long old = this.visible[index >>> 6];
            this.visible[index >>> 6] = show ? old | bit : old & ~bit;
            changed |= old != this.visible[index >>> 6];
        }
        if (changed) { this.nextDiffTick = 0; this.snapshotNeedsRefresh = true; }
    }
    private boolean enqueue(final long node, final boolean working) {
        if (working) {
            if (this.tail == this.queue.length) this.queue = Arrays.copyOf(this.queue, this.queue.length * 2);
            this.queue[this.tail++] = node;
            return true;
        }
        if (this.continuationTail == this.continuationQueue.length && this.continuationHead != 0) {
            final int remaining = this.continuationTail - this.continuationHead;
            System.arraycopy(this.continuationQueue, this.continuationHead, this.continuationQueue, 0, remaining);
            this.continuationHead = 0; this.continuationTail = remaining;
        }
        if (this.continuationTail == this.continuationQueue.length) {
            if (this.continuationQueue.length == MAX_CONTINUATION_QUEUE) { this.dirty = true; this.continuationOverflow = true; return false; }
            this.continuationQueue = Arrays.copyOf(this.continuationQueue, Math.min(MAX_CONTINUATION_QUEUE, this.continuationQueue.length * 2));
        }
        this.continuationQueue[this.continuationTail++] = node;
        return true;
    }
    boolean hasMissingChunk(final long key) { return this.frontier.missing.contains(key) || this.workFrontier.missing.contains(key); }
    void forgetFrontier(final long key) { this.frontier.take(key); this.workFrontier.take(key); }
    private void resumeFrontier(final long key, final boolean working) {
        final Frontier source = working ? this.workFrontier : this.frontier;
        if (!source.missing.contains(key)) return;
        if (source.incomplete.contains(key)) { this.dirty = true; this.continuationOverflow = true; }
        final LongArrayList nodes = source.take(key);
        if (nodes == null) { this.dirty = true; return; }
        for (int i = 0; i < nodes.size(); ++i) if (!this.enqueue(nodes.getLong(i), working)) this.dirty = true;
    }
    private void resumeLoadedFrontiers(final ConnectivityCache cache, final boolean working) {
        final Frontier source = working ? this.workFrontier : this.frontier;
        final var iterator = source.missing.iterator();
        while (iterator.hasNext()) {
            final long key = iterator.nextLong();
            if (this.column(cache, key, working) == null) continue;
            iterator.remove();
            if (source.incomplete.remove(key)) { this.dirty = true; this.continuationOverflow = true; }
            final LongArrayList nodes = source.nodes.remove(key);
            if (nodes == null) { this.dirty = true; continue; }
            source.size -= nodes.size();
            for (int i = 0; i < nodes.size(); ++i) if (!this.enqueue(nodes.getLong(i), working)) this.dirty = true;
        }
    }
    void continueChunk(final ConnectivityCache cache, final long key, final int budget) {
        if (this.running) this.resumeFrontier(key, true);
        if (!this.hasResult) return;
        this.resumeFrontier(key, false);
        if (budget > 0) this.advanceContinuation(cache, budget);
    }
    void prepareContinuations(final ConnectivityCache cache) { if (this.hasResult) this.resumeLoadedFrontiers(cache, false); }
    boolean hasContinuation() { return this.continuationHead < this.continuationTail; }
    void advanceContinuation(final ConnectivityCache cache, final int budget) {
        this.resumeLoadedFrontiers(cache, false);
        int remaining = budget;
        boolean progressed = false;
        while (this.continuationHead < this.continuationTail && remaining-- > 0) {
            this.visit(cache, this.continuationQueue[this.continuationHead++], false);
            progressed = true;
        }
        if (this.continuationHead == this.continuationTail) this.continuationHead = this.continuationTail = 0;
        if (progressed) { this.visibilityDirty = true; this.refreshVisibility(cache); }
    }
    void finishForSend(final ConnectivityCache cache, final int minY, final int height, final long tick) {
        if (this.resultIsCurrent()) return;
        this.request(minY, height, tick, true);
        // Overflow recovery must stay in the regular tick budget, not trigger a large synchronous rebuild.
        if (this.continuationOverflow) return;
        // Keep the pre-component synchronous ceiling; remaining components use the regular tick budget.
        long remaining = (long)this.workWidth * this.workWidth * this.workHeight * 6L + 1L;
        while (remaining > 0) {
            final int slice = (int)Math.min(8192L, remaining);
            if (this.advance(cache, slice)) break;
            remaining -= slice;
            if (!this.workFrontier.incomplete.isEmpty()) { this.continuationOverflow = true; break; }
        }
    }
    private void visit(final ConnectivityCache cache, final long node, final boolean working) {
        final int centerX = working ? this.workX : this.x, centerZ = working ? this.workZ : this.z;
        final int radius = working ? this.workRadius : this.radius, width = working ? this.workWidth : this.width;
        final int height = working ? this.workHeight : this.height, minY = working ? this.workMinY : this.minY;
        final long[] visited = working ? this.visited : this.completedVisited;
        final long[] bits = working ? this.work : this.reachable;
        final short[] reached = working ? this.workReachedComponents : this.reachedComponents;
        final short[] all = working ? this.workAllComponents : this.allComponents;
        final int[] componentIds = working ? this.workComponentIds : this.componentIds;
        final Frontier frontier = working ? this.workFrontier : this.frontier;
        final int index = (int)(node >>> 13), component = (int)(node >>> 9) & 15;
        final int used = (int)(node >>> 3) & 63;
        final int section = index % height, columnIndex = index / height;
        final int sy = section + minY, sx = columnIndex % width + centerX - radius, sz = columnIndex / width + centerZ - radius;
        final long key = ConnectivityCache.key(sx, sz);
        final ConnectivityCache.Column column = this.column(cache, columnIndex, working);
        if (column == null) { frontier.add(key, node); return; }
        final SectionConnectivity.Components components = cache.components(column, section);
        // Keep queued labels and already accumulated bits bound to one component generation.
        if (componentIds[index] != 0 && componentIds[index] != components.id()) { this.dirty = true; return; }
        componentIds[index] = components.id();
        all[index] = (short)components.allBits();
        if (component == 15) {
            final int eyeComponent = components.componentAt(working ? this.workEyeCell : this.eyeCell);
            for (int rootComponent = 0; rootComponent < components.count(); ++rootComponent) {
                if (eyeComponent >= 0 && rootComponent != eyeComponent) continue;
                this.enqueue(((long)index << 13) | ((long)rootComponent << 9) | 6L, working);
            }
            return;
        }
        // A topology edit can leave queued component ids obsolete until the next full rebuild.
        if (component >= components.count()) { this.dirty = true; return; }
        bits[index >>> 6] |= 1L << index;
        reached[index] |= (short)(1 << component);
        for (int direction = 0; direction < 6; ++direction) {
            if ((used & 1 << (direction ^ 1)) != 0 || !components.hasPortal(component, direction)) continue;
            final int nx = sx + DX[direction], ny = sy + DY[direction], nz = sz + DZ[direction];
            if (nx < centerX - radius || nx > centerX + radius || nz < centerZ - radius || nz > centerZ + radius || ny < minY || ny >= minY + height) continue;
            final int nextColumn = (nz - centerZ + radius) * width + nx - centerX + radius;
            final int next = nextColumn * height + ny - minY, incoming = direction ^ 1;
            final ConnectivityCache.Column nextColumnData = this.column(cache, nextColumn, working);
            if (nextColumnData == null) {
                // Replay the source component: the unloaded target's labels and portals are unknown.
                frontier.add(ConnectivityCache.key(nx, nz), node);
                continue;
            }
            final SectionConnectivity.Components nextComponents = cache.components(nextColumnData, ny - minY);
            if (componentIds[next] != 0 && componentIds[next] != nextComponents.id()) { this.dirty = true; continue; }
            for (int nextComponent = 0; nextComponent < nextComponents.count(); ++nextComponent) {
                final int visit = nextComponent * 6 + incoming;
                final int word = next * 2 + (visit >>> 6);
                final long visitBit = 1L << visit;
                if ((visited[word] & visitBit) != 0 || !components.overlaps(component, direction, nextComponents, nextComponent)) continue;
                final long nextNode = ((long)next << 13) | ((long)nextComponent << 9) | ((long)(used | 1 << direction) << 3) | incoming;
                if (this.enqueue(nextNode, working)) {
                    componentIds[next] = nextComponents.id();
                    visited[word] |= visitBit;
                }
            }
        }
    }
    boolean advance(final ConnectivityCache cache, final int budget) {
        if (!this.running) return false;
        this.resumeLoadedFrontiers(cache, true);
        int remaining = budget;
        while (this.head < this.tail && remaining-- > 0) this.visit(cache, this.queue[this.head++], true);
        if (!this.workFrontier.incomplete.isEmpty()) this.continuationOverflow = true;
        if (this.head != this.tail) {
            // Pending sent chunks may reveal already proven paths without waiting for the whole cube.
            this.nextDiffTick = 0;
            return false;
        }
        final long[] old = this.reachable; this.reachable = this.work; this.work = old;
        final long[] oldVisited = this.completedVisited; this.completedVisited = this.visited; this.visited = oldVisited;
        final short[] oldReached = this.reachedComponents; this.reachedComponents = this.workReachedComponents; this.workReachedComponents = oldReached;
        final short[] oldAll = this.allComponents; this.allComponents = this.workAllComponents; this.workAllComponents = oldAll;
        final int[] oldIds = this.componentIds; this.componentIds = this.workComponentIds; this.workComponentIds = oldIds;
        this.eyeCell = this.workEyeCell;
        final ConnectivityCache.Column[] oldColumns = this.columns; this.columns = this.workColumns; this.workColumns = oldColumns;
        final long[] oldPasses = this.columnPasses; this.columnPasses = this.workColumnPasses; this.workColumnPasses = oldPasses;
        final Frontier oldFrontier = this.frontier; this.frontier = this.workFrontier; this.workFrontier = oldFrontier;
        this.workFrontier.clear();
        this.continuationHead = this.continuationTail = 0;
        this.x = this.workX; this.y = this.workY; this.z = this.workZ; this.radius = this.workRadius;
        this.width = this.workWidth; this.height = this.workHeight; this.minY = this.workMinY;
        this.hasResult = true;
        this.running = false;
        if (!this.dirty && this.frontier.incomplete.isEmpty()) this.continuationOverflow = false;
        this.visibilityDirty = true;
        this.refreshVisibility(cache);
        return true;
    }
}
