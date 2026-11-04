package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigCategory;
import org.dreeam.leaf.config.ConfigModule;

public class RedstoneLimiter extends ConfigModule {

    public String basePath() {
        return ConfigCategory.PERF.basePath() + ".redstone-limiter";
    }

    public static boolean enabled = false;
    public static boolean monitorOnly = true;
    public static double executionBudgetMs = 5.0;
    public static boolean chunkThrottling = true;
    public static boolean slowPistons = false;
    public static boolean virtualTime = false;
    public static double minSpeed = 0.1;
    public static double catchUpSpeed = 1.5;
    public static boolean slowBlockEntities = true;
    public static boolean virtualLookahead = false;
    public static double chunkBudgetMs = 2.0;
    public static double groupBudgetMs = 2.0;
    public static int neighborRadius = 1;
    public static boolean propagateActiveChunks = true;
    public static int maxGroupChunks = 256;
    public static int emaWindowTicks = 40;
    public static double recoveryRatio = 0.5;
    public static int cooldownTicks = 100;
    public static boolean logWarnings = true;
    public static int logIntervalTicks = 1200;

    @Override
    public void onLoaded() {
        globalConfig.addCommentRegionBased(basePath(), """
                Provides a per-world execution budget and optional local group budgets for scheduled block ticks.
                Hot chunks and their neighbors form connected groups sharing one time account per group.
                ALL scheduled block ticks in a group may be delayed, not only redstone components; machine timing can change.
                Group budgets are targets: a single cascade may overrun and is compensated by later pauses.
                Monitor-only measures and logs without delaying ticks.""",
            """
                为计划方块刻提供每个世界的执行预算和可选的局部组预算.
                高负载区块及其相邻区块组成连通组，每组共享一个时间账户.
                组内所有计划方块刻都可能被推迟，而不仅仅是红石，因此机械时序可能改变.
                组预算是目标值: 单次级联可以超支，之后通过暂停执行来补偿.
                仅监控模式只测量并记录，不会推迟方块刻.""");

        enabled = globalConfig.getBoolean(basePath() + ".enabled", enabled,
            globalConfig.pickStringRegionBased(
                "Enable the redstone limiter module.",
                "启用红石限速器模块."
            )
        );
        monitorOnly = globalConfig.getBoolean(basePath() + ".monitor-only", monitorOnly,
            globalConfig.pickStringRegionBased(
                "When enabled, tick times are only measured and logged without actually throttling ticks.",
                "启用后仅测量并记录耗时，不会实际限速节流."
            )
        );

        double loadedExecutionBudget = globalConfig.getDouble(basePath() + ".execution-budget-ms", executionBudgetMs,
            globalConfig.pickStringRegionBased(
                """
                Maximum execution time budget (in ms) per world and server tick for scheduled block ticks.
                When reached, remaining due ticks are deferred to subsequent server ticks and run first there, before fresh ticks (deferred ticks are ordered by age, fresh ticks keep the vanilla order).
                If a deferred tick collides with a newer tick for the same position and block, the two are merged into the newer one.
                Set to 0 to disable the budget.
                Affects all scheduled block ticks in the world (including non-redstone).
                Slows down all contraptions in the world under heavy load, which can change their timing.
                The currently running cascade of a tick is always fully completed (not an absolute limit).
                Only active when enabled is true and monitor-only is false.""",
                """
                每个世界每个服务器 Tick 执行计划方块刻的最大耗时预算 (毫秒).
                达到预算后，剩余的到期刻将推迟至后续服务器 Tick 并优先于新产生的刻执行 (被推迟的刻按时间先后排序，新产生的刻保持原版排序).
                若推迟的刻与同位置同方块的较新刻冲突，则合并为较新的刻.
                设为 0 可禁用预算.
                对世界内的所有计划方块刻生效 (包括非红石).
                在负载过高时减缓世界内的所有电路，可能改变其时序.
                当前正在执行的单次方块刻级联始终完整结束 (非绝对硬性中断).
                仅在 enabled 为 true 且 monitor-only 为 false 时生效."""
            )
        );
        if (!Double.isFinite(loadedExecutionBudget)) {
            executionBudgetMs = 5.0;
        } else {
            double clamped = Math.clamp(loadedExecutionBudget, 0.0, 50.0);
            executionBudgetMs = (clamped > 0.0 && clamped < 0.5) ? 0.5 : clamped;
        }

        chunkThrottling = globalConfig.getBoolean(basePath() + ".chunk-throttling", chunkThrottling,
            globalConfig.pickStringRegionBased(
                """
                Enable local group throttling for hot chunks and their neighbors (neighbor-radius, plus active neighbors if propagate-active-chunks is on).
                Also needed for slow-pistons. Deferred ticks of throttled groups run before fresh ticks (backlog first).
                Connected chunks share group-budget-ms per server tick for ALL scheduled block ticks, not only redstone.
                A single cascade may overrun the target time account; later pauses compensate for the overrun.
                Can change machine timing, including contraptions crossing group boundaries.
                Only active when enabled is true and monitor-only is false.""",
                """
                对高负载区块及 neighbor-radius 范围内的相邻区块启用局部组限速.
                连通区块每个服务器 Tick 共享 group-budget-ms，作用于所有计划方块刻，而不仅仅是红石.
                单次级联可以超出时间账户的目标预算，之后通过暂停执行来补偿.
                可能改变机械时序，包括跨越组边界的机械.
                仅在 enabled 为 true 且 monitor-only 为 false 时生效."""
            )
        );
        slowPistons = globalConfig.getBoolean(basePath() + ".slow-pistons", slowPistons,
            globalConfig.pickStringRegionBased(
                """
                Also throttle piston block events.
                WARNING: Alters timing of piston contraptions and duplication machines, only enable after testing!""",
                """
                同时节流活塞方块事件.
                警告: 会改变活塞机械和复制机的时序，仅在充分测试后启用!"""
            )
        );

        virtualTime = globalConfig.getBoolean(basePath() + ".virtual-time", virtualTime,
            globalConfig.pickStringRegionBased(
                """
                Virtual chunk time (experimental). Instead of the time account, every throttled group gets its own clock that runs slower than the server (like /tick rate for just that area).
                The group runs only some server ticks (a whole group tick is skipped, nothing partial) and all delays of scheduled block ticks inside it are measured in the group's own time,
                so repeaters, torches, observers etc. stay in sync with each other and just run at a lower speed. Piston block events are slowed with it (slow-pistons is not needed),
                and so are block entities if slow-block-entities is on.
                The speed of a group is group-budget-ms divided by its measured cost per executed tick, limited by min-speed.
                Entities are not slowed. Machines spanning the border of two groups (or a group and normal chunks) can get out of sync.
                The global execution-budget-ms is not used while virtual-time is on (it would split a group tick).
                Only active when enabled is true, monitor-only is false and chunk-throttling is true.""",
                """
                虚拟区块时间 (实验性). 不再使用时间账户, 每个限速组拥有自己的时钟, 运行速度低于服务器 (类似仅对该区域生效的 /tick rate).
                组只在部分服务器 Tick 中运行 (整组跳过一个 Tick, 不做部分执行), 组内计划方块刻的所有延迟按组自己的时间计算,
                因此中继器, 红石火把, 侦测器等保持彼此同步, 只是整体变慢. 活塞方块事件随之减速 (无需 slow-pistons), 开启 slow-block-entities 时方块实体也会减速.
                组的速度 = group-budget-ms / 每个已执行 Tick 的实测耗时, 且不低于 min-speed.
                实体不会被减速. 跨越两个组 (或组与普通区块) 边界的机械可能失去同步.
                启用 virtual-time 时不使用全局 execution-budget-ms (它会拆分组的 Tick).
                仅在 enabled 为 true, monitor-only 为 false 且 chunk-throttling 为 true 时生效."""
            )
        );
        double loadedMinSpeed = globalConfig.getDouble(basePath() + ".min-speed", minSpeed,
            globalConfig.pickStringRegionBased(
                "Lowest speed of a throttled group with virtual-time, as a fraction of normal speed (0.1 = at most 10 times slower). Range: [0.02, 1.0].",
                "启用 virtual-time 时限速组的最低速度, 相对正常速度的比例 (0.1 = 最多慢 10 倍). 范围: [0.02, 1.0]."
            )
        );
        minSpeed = Double.isFinite(loadedMinSpeed) ? Math.clamp(loadedMinSpeed, 0.02, 1.0) : 0.1;
        double loadedCatchUp = globalConfig.getDouble(basePath() + ".catch-up-speed", catchUpSpeed,
            globalConfig.pickStringRegionBased(
                """
                With virtual-time, chunks that left a throttled group are behind real time. Their clock runs at this multiple of normal speed until they have caught up
                (1.0 = never catch up, they keep the delay). Higher values catch up faster but speed machines up for a moment. Range: [1.0, 4.0].""",
                """
                启用 virtual-time 时, 离开限速组的区块落后于真实时间. 其时钟以该倍速运行直到追上 (1.0 = 不追赶, 保留延迟).
                数值越高追赶越快, 但机械会短暂加速. 范围: [1.0, 4.0]."""
            )
        );
        catchUpSpeed = Double.isFinite(loadedCatchUp) ? Math.clamp(loadedCatchUp, 1.0, 4.0) : 1.5;
        slowBlockEntities = globalConfig.getBoolean(basePath() + ".slow-block-entities", slowBlockEntities,
            globalConfig.pickStringRegionBased(
                "With virtual-time, block entities (pistons in motion, hoppers, furnaces, ...) of a throttled group also only tick on the group's ticks.",
                "启用 virtual-time 时, 限速组内的方块实体 (移动中的活塞, 漏斗, 熔炉等) 也只在该组运行的 Tick 中执行."
            )
        );
        virtualLookahead = globalConfig.getBoolean(basePath() + ".virtual-lookahead", virtualLookahead,
            globalConfig.pickStringRegionBased(
                """
                With virtual-time, every active chunk of a throttled group also pulls its 8 neighbours into the group, even if they are idle (limited by max-group-chunks).
                This lowers the risk that a clock border runs through a moving machine (flying machine) when it crosses into the next chunk. It is no guarantee.
                Requires propagate-active-chunks to be true, otherwise it has no effect.
                The price: the group gets larger, and other redstone next to the machine is slowed as well. Moved pistons keep the chunks they touch in the group either way.""",
                """
                启用 virtual-time 时, 限速组内每个活跃区块会把其 8 个相邻区块一并拉入该组, 即使它们空闲 (受 max-group-chunks 限制).
                这会降低移动的机器 (飞行机器) 进入下一个区块时时钟边界穿过机器的风险, 但不能保证.
                需要 propagate-active-chunks 为 true, 否则不起作用.
                代价: 组变大, 机器附近的其他红石也会被减速. 被活塞移动触及的区块无论如何都会留在组内."""
            )
        );

        double loadedChunkBudget = globalConfig.getDouble(basePath() + ".chunk-budget-ms", chunkBudgetMs,
            globalConfig.pickStringRegionBased(
                "Maximum allowed tick time budget (in ms) per chunk before throttling starts. (Min: 0.1)",
                "开始限速前每个区块允许的最大 Tick 耗时预算 (毫秒). (最小值: 0.1)"
            )
        );
        chunkBudgetMs = Double.isFinite(loadedChunkBudget) ? Math.max(0.1, loadedChunkBudget) : 2.0;

        double loadedGroupBudget = globalConfig.getDouble(basePath() + ".group-budget-ms", groupBudgetMs,
            globalConfig.pickStringRegionBased(
                """
                Target time allowance (ms) per server tick for an ENTIRE connected group, shared by all members.
                The account holds at most two ticks of credit and two ticks of debt.
                A single cascade can exceed the target and is compensated by later pauses. Range: [0.1, 50.0].""",
                """
                每个服务器 Tick 为整个连通组提供的目标时间额度 (毫秒)，由组内所有区块共享.
                账户最多保留两个 Tick 的额度和两个 Tick 的债务.
                单次级联可以超出目标，之后通过暂停执行来补偿. 范围: [0.1, 50.0]."""
            )
        );
        groupBudgetMs = Double.isFinite(loadedGroupBudget) ? Math.clamp(loadedGroupBudget, 0.1, 50.0) : 2.0;

        neighborRadius = Math.clamp(globalConfig.getInt(basePath() + ".neighbor-radius", neighborRadius,
            globalConfig.pickStringRegionBased(
                "Radius around each hot chunk included in its group (Chebyshev distance, including diagonals). Range: [0, 2].",
                "每个高负载区块周围纳入组内的半径 (切比雪夫距离，包含对角线). 范围: [0, 2]."
            )
        ), 0, 2);

        propagateActiveChunks = globalConfig.getBoolean(basePath() + ".propagate-active-chunks", propagateActiveChunks,
            globalConfig.pickStringRegionBased(
                """
                Also pull in every chunk next to a throttled chunk that ran scheduled ticks within the last 100 ticks (any scheduled block ticks,
                not only redstone, or was held back by the limiter), and continue from those chunks in the same way until no active neighbor
                is left (or max-group-chunks is reached). The set is refreshed every 20 ticks. Aims at a common speed for machines spanning
                many chunks, it is not a guarantee. Idle chunks stop the spreading. Unrelated active farms next to the machine can be slowed too.""",
                """
                将与已限速区块相邻且在最近 100 个 Tick 内执行过计划方块刻 (不仅限于红石, 或被限速器暂缓执行) 的区块也纳入组内,
                并从这些区块继续以同样方式扩展, 直到没有活跃的相邻区块 (或达到 max-group-chunks). 该集合每 20 个 Tick 刷新一次.
                目标是让跨越多个区块的机械保持统一速度, 但不作保证. 空闲区块会终止扩展. 机械附近无关的活跃农场也可能被减速."""
            )
        );

        maxGroupChunks = Math.clamp(globalConfig.getInt(basePath() + ".max-group-chunks", maxGroupChunks,
            globalConfig.pickStringRegionBased(
                "Maximum number of ADDITIONAL chunks that spreading may pull into the throttled set of one world per refresh (the fixed neighbor-radius rings are not counted). Range: [9, 4096].",
                "传播扩展时单个世界内每次刷新允许额外纳入限速集合的最大区块数 (固定的 neighbor-radius 范围不计入). 范围: [9, 4096]."
            )
        ), 9, 4096);

        emaWindowTicks = Math.clamp(globalConfig.getInt(basePath() + ".ema-window-ticks", emaWindowTicks,
            globalConfig.pickStringRegionBased(
                "Exponential moving average (EMA) smoothing window in ticks. Range: [1, 1200].",
                "指数移动平均 (EMA) 平滑窗口大小 (刻). 范围: [1, 1200]."
            )
        ), 1, 1200);

        double loadedRecovery = globalConfig.getDouble(basePath() + ".recovery-ratio", recoveryRatio,
            globalConfig.pickStringRegionBased(
                "EMA must fall below this fraction of chunk-budget-ms to recover, with both cooldown conditions satisfied. Range: [0.05, 0.95].",
                "EMA 必须降至 chunk-budget-ms 的该比例以下，且同时满足两项冷却条件，才解除限速. 范围: [0.05, 0.95]."
            )
        );
        recoveryRatio = Double.isFinite(loadedRecovery) ? Math.clamp(loadedRecovery, 0.05, 0.95) : 0.5;

        cooldownTicks = Math.max(0, globalConfig.getInt(basePath() + ".cooldown-ticks", cooldownTicks,
            globalConfig.pickStringRegionBased(
                "Recovery requires both this many ticks in the hot state and this many ticks without any rejection in the chunk's group.",
                "恢复要求区块保持高负载状态至少达到此刻数，且其所属组连续至少此刻数没有任何拒绝执行."
            )
        ));

        logWarnings = globalConfig.getBoolean(basePath() + ".log-warnings", logWarnings,
            globalConfig.pickStringRegionBased(
                "Log warning and recovery messages to console when chunks enter or leave throttled state.",
                "当区块进入或解除限速状态时在控制台输出警告和恢复日志."
            )
        );

        logIntervalTicks = Math.max(20, globalConfig.getInt(basePath() + ".log-interval-ticks", logIntervalTicks,
            globalConfig.pickStringRegionBased(
                "Minimum interval in ticks between throttle log messages. (Min: 20)",
                "限速日志消息之间的最小间隔刻数. (最小值: 20)"
            )
        ));
    }
}
