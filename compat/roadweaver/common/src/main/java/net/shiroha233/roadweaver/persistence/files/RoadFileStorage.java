/* 文件职责：按世界路径路由道路 ChunkPos 存储，并保留旧文件存储门面的对外语义。 */
package net.shiroha233.roadweaver.persistence.files;

import net.shiroha233.roadweaver.runtime.LevelScopedState;
import net.shiroha233.roadweaver.runtime.LevelWriteFence;
import net.shiroha233.roadweaver.api.RoadWeaverWorlds;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.persistence.RoadReplacement;
import net.shiroha233.roadweaver.persistence.chunk.RoadChunkStore;
import net.shiroha233.roadweaver.persistence.chunk.RoadFingerprint;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 道路文件存储门面。
 *
 * <p>道路数据本身由 {@link RoadChunkStore} 管理；这里唯一的静态状态是按已加载世界实例隔离的
 * 实例路由表，不共享任何道路快照。世界关闭时由 {@link #close(ServerLevel)} 移除对应实例。</p>
 */
public final class RoadFileStorage {
    private static final String CATEGORY = "roads";
    private static final LevelScopedState<RoadChunkStore> STORES = new LevelScopedState<>();

    private RoadFileStorage() {}

    public static void addRoad(ServerLevel level, RoadData road) {
        if (!isSupportedWorld(level)) return;
        assertWritable(level);
        store(level).addRoad(road);
    }

    public static void preload(ServerLevel level) {
        if (isSupportedWorld(level)) store(level).preload();
    }

    public static List<RoadData> queryRect(ServerLevel level,
                                           int minBlockX, int minBlockZ,
                                           int maxBlockX, int maxBlockZ) {
        return isSupportedWorld(level)
                ? store(level).queryRect(minBlockX, minBlockZ, maxBlockX, maxBlockZ)
                : List.of();
    }

    public static CompletableFuture<List<RoadData>> queryRectAsync(ServerLevel level,
                                                                     int minBlockX, int minBlockZ,
                                                                     int maxBlockX, int maxBlockZ) {
        if (!isSupportedWorld(level)) return CompletableFuture.completedFuture(List.of());
        // Capture the store now so a late read cannot recreate state for an unloaded level.
        RoadChunkStore store = store(level);
        return CompletableFuture.supplyAsync(() -> store.queryRect(minBlockX, minBlockZ, maxBlockX, maxBlockZ));
    }

    public static List<RoadData> queryChunk(ServerLevel level, ChunkPos chunkPos) {
        return isSupportedWorld(level) && chunkPos != null
                ? store(level).queryChunk(chunkPos)
                : List.of();
    }

    public static List<RoadData> queryChunk(ServerLevel level, int chunkX, int chunkZ) {
        return isSupportedWorld(level) ? store(level).queryChunk(chunkX, chunkZ) : List.of();
    }

    public static List<RoadData> loadAll(ServerLevel level) {
        return isSupportedWorld(level) ? store(level).loadAll() : List.of();
    }

    public static RoadData loadByFingerprint(ServerLevel level, long fingerprint) {
        return isSupportedWorld(level) ? store(level).loadByFingerprint(fingerprint) : null;
    }

    public static void deleteRoad(ServerLevel level, long fingerprint) {
        if (isSupportedWorld(level)) { assertWritable(level); store(level).deleteRoad(fingerprint); }
    }

    public static void replaceRoad(ServerLevel level, long oldFingerprint, RoadData newRoad) {
        if (isSupportedWorld(level)) { assertWritable(level); store(level).replaceRoad(oldFingerprint, newRoad); }
    }

    public static void replaceRoads(ServerLevel level, Collection<RoadReplacement> replacements) {
        if (isSupportedWorld(level)) { assertWritable(level); store(level).replaceRoads(replacements); }
    }

    public static boolean hasAnyRoad(ServerLevel level) {
        return isSupportedWorld(level) && store(level).hasAnyRoad();
    }

    public static boolean hasRoadInRect(ServerLevel level,
                                        int minBlockX, int minBlockZ,
                                        int maxBlockX, int maxBlockZ) {
        return isSupportedWorld(level)
                && store(level).hasRoadInRect(minBlockX, minBlockZ, maxBlockX, maxBlockZ);
    }

    public static void flush(ServerLevel level) {
        RoadChunkStore existing = STORES.peek(level);
        if (existing != null) existing.flush();
    }

    public static void clearAll(ServerLevel level) {
        if (isSupportedWorld(level)) { assertWritable(level); store(level).clear(); }
    }

    public static void close(ServerLevel level) {
        if (!isSupportedWorld(level)) return;
        synchronized (STORES) {
            LevelWriteFence.close(level);
            RoadChunkStore removed = STORES.remove(level);
            if (removed != null) removed.close();
        }
    }

    public static void shutdown() {
        synchronized (STORES) {
            for (Object key : STORES.keysSnapshot()) LevelWriteFence.close((ServerLevel) key);
            for (RoadChunkStore store : STORES.valuesSnapshot()) store.close();
            STORES.clear();
        }
    }

    public static long computeFingerprint(RoadData road) {
        return RoadFingerprint.compute(road);
    }

    private static RoadChunkStore store(ServerLevel level) {
        synchronized (STORES) {
            LevelWriteFence.requireActive(level);
            return STORES.get(level, () -> new RoadChunkStore(storagePath(level)));
        }
    }

    public static void assertWritable(ServerLevel level) {
        LevelWriteFence.requireActive(level);
    }

    private static Path storagePath(ServerLevel level) {
        return FileStoragePathResolver.categoryRoot(level, CATEGORY).toAbsolutePath().normalize();
    }

    private static boolean isSupportedWorld(ServerLevel level) {
        return level != null && RoadWeaverWorlds.supports(level);
    }
}
