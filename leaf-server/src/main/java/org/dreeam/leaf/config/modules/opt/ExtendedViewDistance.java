package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigCategory;
import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.annotations.Experimental;

public class ExtendedViewDistance extends ConfigModule {

    public String basePath() {
        return ConfigCategory.PERF.basePath() + ".extended-view-distance";
    }

    @Experimental
    public static boolean enabled = false;
    public static int maxDistance = 32;
    public static int sendsPerTick = 4;
    public static int maxConcurrentReads = 64;
    public static int workerThreads = 2;
    public static int cacheSize = 2048;
    public static int cacheExpireSeconds = 300;
    public static boolean sendBlockEntities = true;

    @Override
    public void onLoaded() {
        globalConfig.addCommentRegionBased(basePath(), """
                Allows players to see up to 32 chunks without loading them on the server.
                Distant chunks are read asynchronously from disk and sent as static snapshots
                without ticking entities or events. Only already generated chunks are sent.
                Automatically disabled for worlds with active Anti-Xray.
                The server's real view-distance remains the actual chunk loading radius.""",
            """
                允许玩家在服务器不加载区块的情况下看到最多 32 个区块.
                远距离区块从磁盘异步读取并作为静态快照发送,
                不包含活跃实体或事件. 仅发送已生成的区块.
                在启用了反透视 (Anti-Xray) 的世界中会自动禁用.
                服务器实际的 view-distance 仍是真实的区块加载半径.""");

        enabled = globalConfig.getBoolean(basePath() + ".enabled", enabled);
        maxDistance = Math.clamp(
            globalConfig.getInt(basePath() + ".max-distance", maxDistance),
            3,
            ca.spottedleaf.moonrise.common.util.MoonriseConstants.MAX_VIEW_DISTANCE
        );
        sendsPerTick = Math.max(1, globalConfig.getInt(basePath() + ".sends-per-tick", sendsPerTick));
        maxConcurrentReads = Math.max(1, globalConfig.getInt(basePath() + ".max-concurrent-reads", maxConcurrentReads));
        workerThreads = Math.max(1, globalConfig.getInt(basePath() + ".worker-threads", workerThreads));
        cacheSize = Math.max(0, globalConfig.getInt(basePath() + ".cache-size", cacheSize));
        cacheExpireSeconds = Math.max(1, globalConfig.getInt(basePath() + ".cache-expire-seconds", cacheExpireSeconds));
        sendBlockEntities = globalConfig.getBoolean(basePath() + ".send-block-entities", sendBlockEntities);
    }
}
