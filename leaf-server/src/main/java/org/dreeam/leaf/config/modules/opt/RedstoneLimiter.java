package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigCategory;
import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.annotations.Experimental;

public class RedstoneLimiter extends ConfigModule {

    public String basePath() {
        return ConfigCategory.PERF.basePath() + ".redstone-limiter";
    }

    @Experimental
    public static boolean enabled = false;
    public static boolean monitorOnly = true;
    public static double msptThreshold = 50.0;
    public static double msptRecoveryRatio = 0.9;
    public static double minSpeed = 0.1;
    public static String scope = "all";
    public static boolean hottestScope = false;
    public static double hottestShare = 0.5;
    public static boolean slowBlockEntities = true;
    public static int neighborRadius = 1;
    public static int emaWindowTicks = 20;
    public static boolean freezeEnabled = false;
    public static int freezeAfterSeconds = 60;
    public static boolean logWarnings = true;
    public static int logIntervalTicks = 1200;

    @Override
    public void onLoaded() {
        globalConfig.addCommentRegionBased(basePath(), """
                Virtual chunk time (experimental). Everything runs normally until the tick time (MSPT) of the server reaches mspt-threshold
                and stays there for about a second. Then all chunks with recent scheduled block ticks (plus their neighbors) are grouped, and
                every group gets its own clock that runs slower than the server (like /tick rate for just that area). The speed is the same
                for all groups and is lowered step by step while the MSPT is above the threshold and raised again once it has fallen below
                the recovery limit. When it is back at normal speed the groups are dissolved and everything runs normally again.
                A group runs only some server ticks (a whole group tick is skipped, nothing partial, the skipping is slightly irregular on purpose)
                and all delays of scheduled block ticks inside it are measured in the group's own time, so repeaters, torches, observers etc.
                stay in sync with each other and just run at a lower speed. Piston block events are slowed with it, and so are block entities
                if slow-block-entities is on. Entities, fluids, random ticks and everything that is not a scheduled block tick, a piston event or
                a block entity are NOT slowed (water machines keep their speed). ALL scheduled block ticks in a group are slowed, not only
                redstone components. Machines spanning the border of two groups (or a group and normal chunks) can get out of sync.
                Monitor-only measures and logs without slowing anything.""",
            """
                虚拟区块时间 (实验性). 在服务器的 Tick 耗时 (MSPT) 达到 mspt-threshold 并持续约一秒之前, 一切正常运行.
                达到后, 所有最近有计划方块刻的区块 (及其相邻区块) 会被分组, 每组拥有自己的时钟, 运行速度低于服务器 (类似仅对该区域生效的 /tick rate).
                所有组使用相同的速度: MSPT 高于阈值时逐步降低, 回落到恢复线以下后再逐步恢复. 恢复到正常速度后分组解散, 一切恢复正常.
                组只在部分服务器 Tick 中运行 (整组跳过一个 Tick, 不做部分执行, 跳过的节奏刻意带有轻微的不规则性),
                组内计划方块刻的所有延迟按组自己的时间计算, 因此中继器, 红石火把, 侦测器等保持彼此同步, 只是整体变慢.
                活塞方块事件随之减速, 开启 slow-block-entities 时方块实体也会减速. 实体, 流体, 随机刻以及其他不属于计划方块刻, 活塞事件或方块实体的内容不会被减速 (水机械保持原速).
                组内所有计划方块刻都会被减速, 而不仅仅是红石. 跨越两个组 (或组与普通区块) 边界的机械可能失去同步.
                仅监控模式只测量并记录, 不会减速.""");

        enabled = globalConfig.getBoolean(basePath() + ".enabled", enabled,
            globalConfig.pickStringRegionBased(
                "Enable the redstone limiter module.",
                "启用红石限速器模块."
            )
        );
        monitorOnly = globalConfig.getBoolean(basePath() + ".monitor-only", monitorOnly,
            globalConfig.pickStringRegionBased(
                "When enabled, tick times are only measured and logged without actually slowing anything down.",
                "启用后仅测量并记录耗时, 不会实际减速."
            )
        );

        double loadedThreshold = globalConfig.getDouble(basePath() + ".mspt-threshold", msptThreshold,
            globalConfig.pickStringRegionBased(
                """
                Tick time (ms) of the whole server, as shown by /mspt (smoothed over ema-window-ticks), at which the slowdown starts.
                Below it everything runs normally. Range: [5.0, 1000.0].""",
                """
                整个服务器的 Tick 耗时 (毫秒, 与 /mspt 显示的一致, 按 ema-window-ticks 平滑), 达到该值时开始减速.
                低于该值时一切正常运行. 范围: [5.0, 1000.0]."""
            )
        );
        msptThreshold = Double.isFinite(loadedThreshold) ? Math.clamp(loadedThreshold, 5.0, 1000.0) : 50.0;

        double loadedRecovery = globalConfig.getDouble(basePath() + ".mspt-recovery-ratio", msptRecoveryRatio,
            globalConfig.pickStringRegionBased(
                """
                The speed is raised again while the smoothed MSPT is below this fraction of mspt-threshold (0.9 with a threshold of 50 = below 45 ms).
                Between this limit and the threshold the speed is held. Range: [0.5, 0.99].""",
                """
                当平滑后的 MSPT 低于 mspt-threshold 的该比例时, 速度逐步恢复 (阈值为 50 时 0.9 = 低于 45 毫秒).
                介于该线与阈值之间时保持当前速度. 范围: [0.5, 0.99]."""
            )
        );
        msptRecoveryRatio = Double.isFinite(loadedRecovery) ? Math.clamp(loadedRecovery, 0.5, 0.99) : 0.9;

        double loadedMinSpeed = globalConfig.getDouble(basePath() + ".min-speed", minSpeed,
            globalConfig.pickStringRegionBased(
                "Lowest speed of a slowed group as a fraction of normal speed (0.1 = at most 10 times slower). 1.0 means no slowdown at all. Range: [0.02, 1.0].",
                "被减速组的最低速度, 相对正常速度的比例 (0.1 = 最多慢 10 倍). 1.0 表示完全不减速. 范围: [0.02, 1.0]."
            )
        );
        minSpeed = Double.isFinite(loadedMinSpeed) ? Math.clamp(loadedMinSpeed, 0.02, 1.0) : 0.1;

        scope = globalConfig.getString(basePath() + ".scope", scope,
            globalConfig.pickStringRegionBased(
                """
                What is slowed down: "all" = all chunks with recent scheduled block ticks (plus neighbors), all at the same common speed,
                connected chunks share one clock (most even, but harmless machines are slowed too). "hottest" = only the most expensive
                chunks (together hottest-share of the redstone cost, plus neighbors) are slowed, cheap machines keep running normally.""",
                """
                减速范围: "all" = 所有最近有计划方块刻的区块 (及其相邻区块) 以相同的共同速度运行, 相连区块共用一个时钟 (最均匀, 但无害的机械也会被减速).
                "hottest" = 仅减速最耗时的区块 (合计占红石耗时的 hottest-share, 及其相邻区块), 廉价的机械保持正常运行."""
            )
        );
        String scopeName = scope == null ? "" : scope.trim();
        hottestScope = "hottest".equalsIgnoreCase(scopeName);

        double loadedShare = globalConfig.getDouble(basePath() + ".hottest-share", hottestShare,
            globalConfig.pickStringRegionBased(
                "Only for scope \"hottest\": the most expensive chunks are slowed until they make up this share of the total redstone cost. Range: [0.1, 1.0].",
                "仅用于 scope \"hottest\": 减速最耗时的区块, 直到它们占红石总耗时的该比例. 范围: [0.1, 1.0]."
            )
        );
        hottestShare = Double.isFinite(loadedShare) ? Math.clamp(loadedShare, 0.1, 1.0) : 0.5;

        slowBlockEntities = globalConfig.getBoolean(basePath() + ".slow-block-entities", slowBlockEntities,
            globalConfig.pickStringRegionBased(
                "Block entities (pistons in motion, hoppers, furnaces, ...) of a slowed group also only tick on the group's ticks.",
                "被减速组内的方块实体 (移动中的活塞, 漏斗, 熔炉等) 也只在该组运行的 Tick 中执行."
            )
        );

        neighborRadius = Math.clamp(globalConfig.getInt(basePath() + ".neighbor-radius", neighborRadius,
            globalConfig.pickStringRegionBased(
                """
                Radius around each active chunk (one with recent scheduled block ticks) that is added to its group, also if those chunks are idle
                (Chebyshev distance, including diagonals). Chunks of connected areas end up in the same group and run on the same clock.
                A larger radius lowers the risk that a clock border runs through a machine, but slows more of the neighborhood. Range: [0, 2].""",
                """
                每个活跃区块 (最近有计划方块刻) 周围纳入其组内的半径, 即使这些区块空闲 (切比雪夫距离, 包含对角线). 相连区域的区块属于同一组并使用同一时钟.
                半径越大, 时钟边界穿过机械的风险越低, 但会减速更多的相邻区域. 范围: [0, 2]."""
            )
        ), 0, 2);

        emaWindowTicks = Math.clamp(globalConfig.getInt(basePath() + ".ema-window-ticks", emaWindowTicks,
            globalConfig.pickStringRegionBased(
                """
                Exponential moving average (EMA) smoothing window in ticks, used for the MSPT and the per chunk load shown by the redstone bar.
                A small window reacts fast but nervously, a large window ignores short lag spikes. Range: [1, 1200].""",
                """
                指数移动平均 (EMA) 平滑窗口大小 (刻), 用于 MSPT 和红石条显示的区块负载.
                窗口小则反应快但不稳定, 窗口大则忽略短暂的卡顿尖峰. 范围: [1, 1200]."""
            )
        ), 1, 1200);

        freezeEnabled = globalConfig.getBoolean(basePath() + ".freeze-enabled", freezeEnabled,
            globalConfig.pickStringRegionBased(
                """
                Freeze zones that keep the server overloaded: if the slowdown cannot bring the MSPT below mspt-threshold within
                freeze-after-seconds, the most expensive zone (group of chunks) is stopped completely (scheduled ticks, pistons and, with
                slow-block-entities, block entities). A frozen zone stays frozen, even after the slowdown ended, until a redstone update
                (a lever, button, redstone block, ... next to it) is switched or placed by a player, a command or a plugin (updates from
                clocks, minecarts and mobs during the tick do not thaw). Has no effect in monitor-only mode.""",
                """
                冻结持续使服务器过载的区域: 如果减速在 freeze-after-seconds 内无法使 MSPT 降到 mspt-threshold 以下,
                则完全停止最耗时的区域 (区块组) (计划方块刻, 活塞, 以及开启 slow-block-entities 时的方块实体).
                被冻结的区域会一直保持冻结 (即使减速已结束), 直到红石更新 (旁边的拉杆, 按钮, 红石块等) 触及其中一个区块.
                仅监控模式下无效."""
            )
        );
        freezeAfterSeconds = Math.clamp(globalConfig.getInt(basePath() + ".freeze-after-seconds", freezeAfterSeconds,
            globalConfig.pickStringRegionBased(
                "Seconds the MSPT must stay above mspt-threshold while slowed down before the most expensive zone is frozen. Range: [5, 3600].",
                "在减速状态下 MSPT 持续高于 mspt-threshold 多少秒后冻结最耗时的区域. 范围: [5, 3600]."
            )
        ), 5, 3600);

        logWarnings = globalConfig.getBoolean(basePath() + ".log-warnings", logWarnings,
            globalConfig.pickStringRegionBased(
                "Log messages to console when the slowdown starts or ends.",
                "当减速开始或结束时在控制台输出日志."
            )
        );

        logIntervalTicks = Math.max(20, globalConfig.getInt(basePath() + ".log-interval-ticks", logIntervalTicks,
            globalConfig.pickStringRegionBased(
                "Minimum interval in ticks between log messages. (Min: 20)",
                "日志消息之间的最小间隔刻数. (最小值: 20)"
            )
        ));
    }
}
