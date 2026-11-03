package org.dreeam.leaf.config.modules.misc;

import net.minecraft.server.level.ServerLevel;
import org.dreeam.leaf.config.ConfigCategory;
import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.util.list.WorldList;

import java.util.ArrayList;

public class AntiFreecam extends ConfigModule {

    public static final String BYPASS_PERMISSION = "leaf.antifreecam.bypass";

    public static WorldList worlds = WorldList.EMPTY;
    public static boolean filterSoundsAndParticles = true;
    public static boolean entityCulling = true;
    // radius in sections around the player's section that is always sent real (1 = 3x3x3)
    public static int alwaysVisibleRadius = 1;
    public static int maxResendsPerPlayerTick = 4;
    public static int maxResendsPerTick = 64;
    public static int rehideDelayTicks = 20;
    public static int entityCheckIntervalTicks = 2;
    public static double entityAlwaysVisibleDistance = 4.0;
    public static double entityLineOfSightDistance = 128.0;
    public static int entityLingerTicks = 20;

    // incremented on every (re)load so cached per-world state can detect config changes
    public static volatile int generation;

    public String basePath() {
        return ConfigCategory.MISC.basePath() + ".anti-freecam";
    }

    @Override
    public void onLoaded() {
        globalConfig.addCommentRegionBased(basePath(), """
                Hides caves, bases and entities that the player cannot reach or see.
                Unreachable 16x16x16 sections are sent as stone/deepslate without block entities and block light,
                updates, sounds and particles from them are filtered, and unseen entities are not sent.
                Extended view distance chunks are filled below their lowest surface point.
                Ores in visible sections are still handled by Paper's Anti-Xray, if enabled.""",
            """
                隐藏玩家无法到达或看到的洞穴、基地和实体.
                无法到达的 16x16x16 区段以石头/深板岩发送, 不包含方块实体和方块光照,
                来自这些区段的更新、声音和粒子会被过滤, 看不到的实体不会被发送.
                扩展视距区块在其最低地表以下被填充.
                可见区段中的矿石仍由 Paper 的反透视处理 (如已启用).""");

        worlds = new WorldList(globalConfig.getList(basePath() + ".worlds", new ArrayList<>(),
            globalConfig.pickStringRegionBased("""
                    Worlds with anti-freecam enabled. Empty disables the feature.
                    '*' works as a wildcard, e.g. 'world*' matches world and world_nether.""",
                """
                    启用反自由视角的世界. 留空则禁用此功能.
                    '*' 可作为通配符使用, 例如 'world*' 匹配 world 和 world_nether.""")));
        filterSoundsAndParticles = globalConfig.getBoolean(basePath() + ".filter-sounds-and-particles", filterSoundsAndParticles);
        entityCulling = globalConfig.getBoolean(basePath() + ".entity-culling", entityCulling);
        alwaysVisibleRadius = Math.clamp(globalConfig.getInt(basePath() + ".always-visible-radius", alwaysVisibleRadius), 1, 4);
        maxResendsPerPlayerTick = Math.max(1, globalConfig.getInt(basePath() + ".max-resends-per-player-tick", maxResendsPerPlayerTick));
        maxResendsPerTick = Math.max(1, globalConfig.getInt(basePath() + ".max-resends-per-tick", maxResendsPerTick));
        rehideDelayTicks = Math.max(0, globalConfig.getInt(basePath() + ".rehide-delay-ticks", rehideDelayTicks));
        entityCheckIntervalTicks = Math.max(1, globalConfig.getInt(basePath() + ".entity-check-interval-ticks", entityCheckIntervalTicks));
        entityAlwaysVisibleDistance = Math.max(0.0, globalConfig.getDouble(basePath() + ".entity-always-visible-distance", entityAlwaysVisibleDistance));
        entityLineOfSightDistance = Math.max(0.0, globalConfig.getDouble(basePath() + ".entity-line-of-sight-distance", entityLineOfSightDistance));
        entityLingerTicks = Math.max(0, globalConfig.getInt(basePath() + ".entity-linger-ticks", entityLingerTicks));
        ++generation;
    }

    public static boolean isEnabled(final ServerLevel level) {
        return !worlds.isEmpty() && worlds.contains(level.getWorld().getName());
    }
}
