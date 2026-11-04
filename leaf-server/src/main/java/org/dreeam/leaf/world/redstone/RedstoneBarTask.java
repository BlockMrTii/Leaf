package org.dreeam.leaf.world.redstone;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.dreeam.leaf.command.subcommands.RedstoneBarCommand;
import org.dreeam.leaf.config.LeafConfig;
import org.dreeam.leaf.config.modules.opt.RedstoneLimiter;
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
 * Like Purpur's tpsbar, whether a player has the bar on is saved in the player's own data (the player's NBT, here through
 * the persistent data container), so it survives reconnects and restarts and needs no extra file. Purpur re-adds the bar
 * from a join hook; here a single synchronous scheduler task (started by the first world tick or the first command) shows the
 * bar to online players with the flag and updates it, so everything touching the bars runs on the main thread. The numbers
 * come from {@link RedstoneLimiterManager#stats()}, an immutable snapshot published by the world's tick thread.
 */
public final class RedstoneBarTask {

    private static final long UPDATE_INTERVAL_TICKS = 10L;
    private static final double FALLBACK_MAX_MS = 50.0;
    private static final double CHUNKS_FOR_RED = 16.0;
    private static final NamespacedKey FLAG = new NamespacedKey("leaf", "redstonebar");
    private static final TextColor GREEN = NamedTextColor.GREEN;
    private static final TextColor YELLOW = NamedTextColor.YELLOW;
    private static final TextColor RED = NamedTextColor.RED;

    private static final Map<UUID, Viewer> VIEWERS = new HashMap<>();
    private static volatile boolean started;
    private static @Nullable BukkitTask task;

    private RedstoneBarTask() {
    }

    /**
     * Starts the update task once. Cheap after the first call, so it can be called every tick.
     */
    public static void ensureStarted() {
        if (started) {
            return;
        }
        synchronized (RedstoneBarTask.class) {
            if (started) {
                return;
            }
            try {
                task = Bukkit.getScheduler().runTaskTimer(new MinecraftInternalPlugin(), RedstoneBarTask::tick, UPDATE_INTERVAL_TICKS, UPDATE_INTERVAL_TICKS);
                started = true;
            } catch (RuntimeException e) {
                LeafConfig.LOGGER.warn("Failed to start the redstone bar task", e);
            }
        }
    }

    private static boolean hasFlag(final Player player) {
        return player.getPersistentDataContainer().getOrDefault(FLAG, PersistentDataType.BOOLEAN, false);
    }

    /**
     * Switches the bar of the player on or off (saved in the player data) and returns whether it is on now.
     */
    public static boolean toggle(final Player player) {
        ensureStarted();
        synchronized (RedstoneBarTask.class) {
            final PersistentDataContainer data = player.getPersistentDataContainer();
            if (hasFlag(player)) {
                data.remove(FLAG);
                hide(player.getUniqueId());
                return false;
            }
            data.set(FLAG, PersistentDataType.BOOLEAN, true);
            show(player);
            return true;
        }
    }

    private static void tick() {
        synchronized (RedstoneBarTask.class) {
            // Drop bars of players who left, reconnected with a new player object, switched it off or lost the permission
            final Iterator<Map.Entry<UUID, Viewer>> iterator = VIEWERS.entrySet().iterator();
            while (iterator.hasNext()) {
                final Viewer viewer = iterator.next().getValue();
                final Player current = Bukkit.getPlayer(viewer.player.getUniqueId());
                if (current != viewer.player || !hasFlag(current) || !current.hasPermission(RedstoneBarCommand.PERM)) {
                    iterator.remove();
                    if (current != null) {
                        current.hideBossBar(viewer.bar);
                    }
                }
            }

            // Show the bar to online players who have it on but do not see it yet (e.g. after joining)
            for (final Player player : Bukkit.getOnlinePlayers()) {
                if (!VIEWERS.containsKey(player.getUniqueId()) && hasFlag(player) && player.hasPermission(RedstoneBarCommand.PERM)) {
                    show(player);
                }
            }

            final Iterator<Map.Entry<UUID, Viewer>> updates = VIEWERS.entrySet().iterator();
            while (updates.hasNext()) {
                final Viewer viewer = updates.next().getValue();
                try {
                    update(viewer);
                } catch (RuntimeException e) {
                    updates.remove();
                    viewer.player.hideBossBar(viewer.bar);
                    LeafConfig.LOGGER.warn("Failed to update the redstone bar of " + viewer.player.getName() + ", removing it", e);
                }
            }
        }
    }

    private static void show(final Player player) {
        hide(player.getUniqueId());
        final BossBar bar = BossBar.bossBar(Component.empty(), 0.0f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_20);
        final Viewer viewer = new Viewer(player, bar);
        try {
            update(viewer);
            player.showBossBar(bar);
            VIEWERS.put(player.getUniqueId(), viewer);
        } catch (RuntimeException e) {
            LeafConfig.LOGGER.warn("Failed to show the redstone bar to " + player.getName(), e);
        }
    }

    private static void hide(final UUID id) {
        final Viewer viewer = VIEWERS.remove(id);
        if (viewer != null) {
            viewer.player.hideBossBar(viewer.bar);
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

        // The bar ends at the configured limit: full bar = execution-budget-ms (50 ms if no limit is set)
        final double maxMs = RedstoneLimiter.executionBudgetMs > 0.0 ? RedstoneLimiter.executionBudgetMs : FALLBACK_MAX_MS;
        final double redstoneMs = stats.redstoneMs();
        final double ratio = Math.max(0.0, Math.min(1.0, redstoneMs / maxMs));
        final int chunks = stats.throttledChunks();

        final Component top = stats.hasHottest()
            ? Component.text("[" + stats.hottestChunkX() + ", " + stats.hottestChunkZ() + "]", NamedTextColor.WHITE)
            : Component.text("-", NamedTextColor.WHITE);

        bossbar.name(Component.text()
            .append(Component.text("MS: ", NamedTextColor.GRAY))
            .append(Component.text(String.format(Locale.ROOT, "%.2f", redstoneMs), gradient(ratio)))
            .append(Component.text(" CH: ", NamedTextColor.GRAY))
            .append(Component.text(chunks, gradient(chunks / CHUNKS_FOR_RED)))
            .append(Component.text(" TOP: ", NamedTextColor.GRAY))
            .append(top)
            .build());
        bossbar.progress((float) ratio);
        bossbar.color(ratio < 0.5 ? BossBar.Color.GREEN : ratio < 0.8 ? BossBar.Color.YELLOW : BossBar.Color.RED);
    }

    /**
     * Green (0) over yellow (0.5) to red (1).
     */
    private static TextColor gradient(final double value) {
        final float v = (float) Math.max(0.0, Math.min(1.0, value));
        return v < 0.5f ? TextColor.lerp(v * 2.0f, GREEN, YELLOW) : TextColor.lerp((v - 0.5f) * 2.0f, YELLOW, RED);
    }

    private record Viewer(Player player, BossBar bar) {
    }
}
