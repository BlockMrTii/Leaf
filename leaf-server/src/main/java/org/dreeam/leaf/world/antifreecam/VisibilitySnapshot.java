package org.dreeam.leaf.world.antifreecam;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Owns a copied bit cube and transferred sets; none are mutated after publication. */
public final class VisibilitySnapshot {
    final ServerPlayer viewer;
    private final int x, z, radius, width, minY, height;
    private final long[] visible;
    private final IntOpenHashSet blocked, exempt;
    private final double eyeX, eyeY, eyeZ, alwaysDistanceSquared;

    VisibilitySnapshot(final PlayerVisibility state, final IntOpenHashSet blocked, final IntOpenHashSet exempt, final double alwaysDistance) {
        this.viewer = state.player;
        this.x = state.x; this.z = state.z; this.radius = state.radius; this.width = state.width;
        this.minY = state.minY; this.height = state.height;
        this.visible = state.visible.clone(); this.blocked = blocked; this.exempt = exempt;
        this.eyeX = state.player.getX(); this.eyeY = state.player.getEyeY(); this.eyeZ = state.player.getZ();
        this.alwaysDistanceSquared = alwaysDistance * alwaysDistance;
    }
    boolean hidden(final Entity entity) {
        if (entity == this.viewer || this.exempt.contains(entity.getId())) return false;
        final double dx = entity.getX() - this.eyeX, dy = entity.getY() - this.eyeY, dz = entity.getZ() - this.eyeZ;
        if (dx * dx + dy * dy + dz * dz < this.alwaysDistanceSquared) return false;
        // Explicit cell-mask negatives also apply to still-tracked partial chunks outside an old cube.
        if (this.blocked.contains(entity.getId())) return true;
        final int cx = PlayerVisibility.section(entity.getX()), cy = PlayerVisibility.section(entity.getY()), cz = PlayerVisibility.section(entity.getZ());
        if (cx < this.x - this.radius || cx > this.x + this.radius || cz < this.z - this.radius || cz > this.z + this.radius || cy < this.minY || cy >= this.minY + this.height) return false;
        final int index = ((cz - this.z + this.radius) * this.width + cx - this.x + this.radius) * this.height + cy - this.minY;
        return (this.visible[index >>> 6] & 1L << index) == 0;
    }
}
