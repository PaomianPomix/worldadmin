/* 文件职责：按世界会话缓存不可变道路区块计划，隔离持久化与世界生成热路径。 */
package net.shiroha233.roadweaver.worldgen.road;

import net.shiroha233.roadweaver.runtime.LevelScopedState;
import net.shiroha233.roadweaver.api.RoadWeaverWorlds;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 世界生成计划的会话入口。磁盘存储只在首次编译计划时被访问，计划本身不可变。
 */
public final class RoadWorldgenPlanCache {
    private static final int QUERY_MARGIN_BLOCKS = 32;
    private static final int MAX_PLANS_PER_LEVEL = 4096;
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final LevelScopedState<Session> SESSIONS = new LevelScopedState<>();

    private RoadWorldgenPlanCache() {
    }

    public static RoadChunkPlan get(ServerLevel level, ChunkPos chunkPos, ModConfig config) {
        if (level == null || chunkPos == null || !RoadWeaverWorlds.supports(level)
                || config == null || !config.roadAppearance().roadsEnabled()) {
            return RoadChunkPlan.empty(chunkPos == null ? new ChunkPos(0, 0) : chunkPos,
                    config == null ? 4 : config.roadAppearance().roadClearHeight(), 0L);
        }
        PlanConfig planConfig = PlanConfig.from(config);
        Session session = session(level);
        return session.get(level, chunkPos, planConfig);
    }

    public static void invalidate(ServerLevel level) {
        if (level == null) return;
        Session session = SESSIONS.peek(level);
        if (session != null) session.invalidate();
    }

    public static void invalidate(ServerLevel level, Collection<Long> chunkKeys) {
        if (level == null || chunkKeys == null || chunkKeys.isEmpty()) return;
        Session session = SESSIONS.peek(level);
        if (session != null) session.invalidate(chunkKeys);
    }

    public static void clear(ServerLevel level) {
        if (level == null) return;
        SESSIONS.remove(level);
    }

    public static void clearAll() {
        SESSIONS.clear();
    }

    private static Session session(ServerLevel level) {
        return SESSIONS.get(level, Session::new);
    }

    private static RoadChunkPlan compile(ServerLevel level,
                                         ChunkPos chunkPos,
                                         PlanConfig config,
                                         long revision) {
        int minX = chunkPos.getMinBlockX() - QUERY_MARGIN_BLOCKS;
        int minZ = chunkPos.getMinBlockZ() - QUERY_MARGIN_BLOCKS;
        int maxX = chunkPos.getMaxBlockX() + QUERY_MARGIN_BLOCKS;
        int maxZ = chunkPos.getMaxBlockZ() + QUERY_MARGIN_BLOCKS;
        try {
            var roads = RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ);
            return RoadChunkPlanCompiler.compile(chunkPos, roads,
                    !config.bridgeEnabled(), config.clearHeight(), revision);
        } catch (RuntimeException failure) {
            LOGGER.warn("编译道路区块计划失败: {}", chunkPos, failure);
            return RoadChunkPlan.empty(chunkPos, config.clearHeight(), revision);
        }
    }

    private static final class Session {
        private final ConcurrentHashMap<Long, CachedPlan> plans = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Long> chunkRevisions = new ConcurrentHashMap<>();
        private final AtomicLong revisionSequence = new AtomicLong();
        private volatile long globalRevision;

        RoadChunkPlan get(ServerLevel level, ChunkPos chunkPos, PlanConfig requested) {
            long key = chunkPos.toLong();
            while (true) {
                CachedPlan cached = plans.get(key);
                long expectedRevision = revision(key);
                if (cached != null && cached.matches(requested, expectedRevision)) {
                    return cached.plan();
                }

                CachedPlan published = plans.compute(key, (ignored, current) -> {
                    long compileRevision = revision(key);
                    if (current != null && current.matches(requested, compileRevision)) {
                        return current;
                    }
                    RoadChunkPlan compiled = compile(level, chunkPos, requested, compileRevision);
                    return revision(key) == compileRevision
                            ? new CachedPlan(requested, compiled)
                            : null;
                });
                long currentRevision = revision(key);
                if (published != null && published.matches(requested, currentRevision)) {
                    trimIfNeeded();
                    return published.plan();
                }
            }
        }

        void invalidate() {
            globalRevision = revisionSequence.incrementAndGet();
            chunkRevisions.clear();
            plans.clear();
        }

        void invalidate(Collection<Long> chunkKeys) {
            long revision = revisionSequence.incrementAndGet();
            for (long packed : chunkKeys) {
                int chunkX = ChunkPos.getX(packed);
                int chunkZ = ChunkPos.getZ(packed);
                for (int deltaZ = -1; deltaZ <= 1; deltaZ++) {
                    for (int deltaX = -1; deltaX <= 1; deltaX++) {
                        long affected = ChunkPos.asLong(chunkX + deltaX, chunkZ + deltaZ);
                        chunkRevisions.put(affected, revision);
                        plans.remove(affected);
                    }
                }
            }
        }

        private long revision(long chunkKey) {
            return Math.max(globalRevision, chunkRevisions.getOrDefault(chunkKey, 0L));
        }

        private void trimIfNeeded() {
            int excess = plans.size() - MAX_PLANS_PER_LEVEL;
            if (excess <= 0) return;
            for (Map.Entry<Long, CachedPlan> entry : plans.entrySet()) {
                plans.remove(entry.getKey(), entry.getValue());
                if (--excess <= 0) return;
            }
        }
    }

    private record CachedPlan(PlanConfig config, RoadChunkPlan plan) {
        boolean matches(PlanConfig requested, long revision) {
            return config.equals(requested) && plan.revision() == revision;
        }
    }

    private record PlanConfig(int clearHeight, boolean bridgeEnabled) {
        static PlanConfig from(ModConfig config) {
            return new PlanConfig(config.roadAppearance().roadClearHeight(), config.bridgeEnabled());
        }
    }
}
