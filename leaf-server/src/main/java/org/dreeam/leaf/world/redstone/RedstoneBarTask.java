package org.dreeam.leaf.world.redstone;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.dreeam.leaf.config.LeafConfig;
import org.jspecify.annotations.Nullable;
import org.purpurmc.purpur.util.MinecraftInternalPlugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shows the redstone load of a player's world in a boss bar (like Purpur's tpsbar).
 * <p>
 * Everything here runs on the main thread: the command toggles the bar there and the update task is a synchronous
 * scheduler task, so there is no concurrent access to the bars and no locking is needed. The numbers come from
 * {@link RedstoneLimiterManager#stats()}, an immutable snapshot published by the world's tick thread.
 */
public final class RedstoneBarTask {

    private static final long UPDATE_INTERVAL_TICKS = 10L;
    private static final Map<UUID, Viewer> VIEWERS = new HashMap<>();
    private static @Nullable BukkitTask task;

    private RedstoneBarTask() {
    }

    /**
     * Switches the bar of the player on or off and returns whether it is on now. Normally called on the main thread; it is
     * synchronized because a plugin can dispatch the command from a world thread with parallel world ticking.
     */
    public static synchronized boolean toggle(final Player player) {
        final Viewer existing = VIEWERS.get(player.getUniqueId());
        if (existing != null && existing.player == player) {
            VIEWERS.remove(player.getUniqueId());
            player.hideBossBar(existing.bar);
            stopIfUnused();
            return false;
        }
        // No bar yet, or the entry belongs to an earlier connection of the same player (quick reconnect): start a fresh bar
        VIEWERS.remove(player.getUniqueId());

        final BossBar bar = BossBar.bossBar(Component.empty(), 0.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        final Viewer viewer = new Viewer(player, bar);
        VIEWERS.put(player.getUniqueId(), viewer);
        try {
            update(viewer);
            player.showBossBar(bar);
            if (task == null) {
                task = Bukkit.getScheduler().runTaskTimer(new MinecraftInternalPlugin(), RedstoneBarTask::tick, UPDATE_INTERVAL_TICKS, UPDATE_INTERVAL_TICKS);
            }
        } catch (RuntimeException e) {
            VIEWERS.remove(player.getUniqueId());
            stopIfUnused();
            throw e;
        }
        return true;
    }

    private static synchronized void tick() {
        final Iterator<Map.Entry<UUID, Viewer>> iterator = VIEWERS.entrySet().iterator();
        while (iterator.hasNext()) {
            final Viewer viewer = iterator.next().getValue();
            final Player current = Bukkit.getPlayer(viewer.player.getUniqueId());
            // Logged out, or logged in again with a new player object: the old bar is gone with the old connection
            if (current != viewer.player) {
                iterator.remove();
                continue;
            }

            try {
                update(viewer);
            } catch (RuntimeException e) {
                iterator.remove();
                current.hideBossBar(viewer.bar);
                LeafConfig.LOGGER.warn("Failed to update the redstone bar of " + current.getName() + ", removing it", e);
            }
        }
        stopIfUnused();
    }

    private static void stopIfUnused() {
        if (VIEWERS.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private static void update(final Viewer viewer) {
        final BossBar bossbar = viewer.bar;
        final String worldName = viewer.player.getWorld().getName();
        final RedstoneLimiterManager manager = RedstoneLimiterManager.forWorld(worldName);
        final RedstoneLimiterManager.Stats stats = manager != null ? manager.stats() : null;

        if (stats == null || !stats.measuring()) {
            bossbar.name(Component.text()
                .append(Component.text("Redstone is not measured in this world ", NamedTextColor.RED))
                .append(Component.text("(performance.redstone-limiter: enabled and monitor-only or chunk-throttling)", NamedTextColor.GRAY))
                .build());
            bossbar.progress(0.0f);
            bossbar.color(BossBar.Color.WHITE);
            return;
        }

        final double redstoneMs = stats.redstoneMs();
        NamedTextColor textColor;
        BossBar.Color barColor;

        if (redstoneMs < 5.0) {
            textColor = NamedTextColor.GREEN;
            barColor = BossBar.Color.GREEN;
        } else if (redstoneMs < 15.0) {
            textColor = NamedTextColor.YELLOW;
            barColor = BossBar.Color.YELLOW;
        } else {
            textColor = NamedTextColor.RED;
            barColor = BossBar.Color.RED;
        }

        if (stats.throttledChunks() > 0 && barColor == BossBar.Color.GREEN) {
            textColor = NamedTextColor.YELLOW;
            barColor = BossBar.Color.YELLOW;
        }

        final double percent = (redstoneMs / 50.0) * 100.0;
        final TextComponent.Builder title = Component.text()
            .append(Component.text("Redstone: "))
            .append(Component.text(String.format(Locale.ROOT, "%.2f", redstoneMs), textColor))
            .append(Component.text(" ms (" + String.format(Locale.ROOT, "%.1f", percent) + "%)"))
            .append(Component.text(" | hot: " + stats.hotChunks() + " | throttled: " + stats.throttledChunks() + " chunks"));

        if (!stats.throttling() && stats.hotChunks() > 0) {
            title.append(Component.text(" (monitor)", NamedTextColor.GRAY));
        }

        if (stats.hasHottest() && stats.hottestMs() > 0.05) {
            title.append(Component.text(" | top [" + stats.hottestChunkX() + ", " + stats.hottestChunkZ() + "] "
                + String.format(Locale.ROOT, "%.2f", stats.hottestMs()) + " ms"));
        }

        title.append(Component.text(" [" + worldName + "]"));

        bossbar.name(title.build());
        bossbar.progress((float) Math.max(0.0, Math.min(1.0, redstoneMs / 50.0)));
        bossbar.color(barColor);
    }

    private record Viewer(Player player, BossBar bar) {
    }
}
