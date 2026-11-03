package org.dreeam.leaf.world.antifreecam;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** Owns a copied bit cube and transferred sets; none are mutated after publication. */
public final class VisibilitySnapshot {
    final ServerPlayer viewer;
    private final int x, z, radius, width, minY, height;
    private final long[] visible;
    private final IntOpenHashSet blocked, exempt;
    // culled cells the client holds as filler, by section key; neither the map nor the masks are mutated after publication
    private final @Nullable Long2ObjectOpenHashMap<long[]> masks;
    private final double eyeX, eyeY, eyeZ, alwaysDistanceSquared;

    VisibilitySnapshot(final PlayerVisibility state, final IntOpenHashSet blocked, final IntOpenHashSet exempt, final double alwaysDistance,
                       final @Nullable Long2ObjectOpenHashMap<long[]> masks) {
        this.viewer = state.player;
        this.x = state.x; this.z = state.z; this.radius = state.radius; this.width = state.width;
        this.minY = state.minY; this.height = state.height;
        this.visible = state.visible.clone(); this.blocked = blocked; this.exempt = exempt;
        this.masks = masks;
        this.eyeX = state.player.getX(); this.eyeY = state.player.getEyeY(); this.eyeZ = state.player.getZ();
        this.alwaysDistanceSquared = alwaysDistance * alwaysDistance;
    }
    boolean hidden(final Entity entity) {
        if (entity == this.viewer || this.exempt.contains(entity.getId())) return false;
        final double dx = entity.getX() - this.eyeX, dy = entity.getY() - this.eyeY, dz = entity.getZ() - this.eyeZ;
        if (dx * dx + dy * dy + dz * dz < this.alwaysDistanceSquared) return false;
        // Explicit cell-mask negatives also apply to still-tracked partial chunks outside an old cube.
        if (this.blocked.contains(entity.getId())) return true;
        // Filler cells hide the entity at once, independent of the line of sight budget: bottom and top must both be culled.
        if (this.masks != null) {
            final AABB box = entity.getBoundingBox();
            final int blockX = (int)Math.floor(entity.getX()), blockZ = (int)Math.floor(entity.getZ());
            if (this.culled(blockX, (int)Math.floor(box.minY), blockZ) && this.culled(blockX, (int)Math.floor(box.maxY - 1.0E-4), blockZ)) return true;
        }
        final int cx = PlayerVisibility.section(entity.getX()), cy = PlayerVisibility.section(entity.getY()), cz = PlayerVisibility.section(entity.getZ());
        if (cx < this.x - this.radius || cx > this.x + this.radius || cz < this.z - this.radius || cz > this.z + this.radius || cy < this.minY || cy >= this.minY + this.height) return false;
        final int index = ((cz - this.z + this.radius) * this.width + cx - this.x + this.radius) * this.height + cy - this.minY;
        return (this.visible[index >>> 6] & 1L << index) == 0;
    }
    private boolean culled(final int blockX, final int blockY, final int blockZ) {
        final long[] mask = this.masks.get(SectionPos.asLong(blockX >> 4, blockY >> 4, blockZ >> 4));
        return AntiFreecamHooks.isCulled(mask, blockX, blockY, blockZ);
    }
}
