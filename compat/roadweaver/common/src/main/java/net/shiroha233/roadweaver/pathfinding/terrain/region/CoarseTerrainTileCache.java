package net.shiroha233.roadweaver.pathfinding.terrain.region;

import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.persistence.files.CoarseTerrainTileFileStorage;
import net.shiroha233.roadweaver.runtime.LevelScopedState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 粗采样地形瓦片内存缓存和持久化加载入口。
 */
public final class CoarseTerrainTileCache {
    private CoarseTerrainTileCache() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int MEMORY_CAPACITY = RoadConstants.COARSE_TERRAIN_TILE_CACHE_MAX_ENTRIES;
    private static final LevelScopedState<State> STATES = new LevelScopedState<>();
    private static final class State {
        final Object lock = new Object();
        final ConcurrentHashMap<CoarseTerrainTileKey, CompletableFuture<CoarseTerrainTile>> inFlight = new ConcurrentHashMap<>();
        final Map<CoarseTerrainTileKey, CoarseTerrainTile> memory = createMemoryMap();
    }

    public static CoarseTerrainTile getOrLoad(ServerLevel level, CoarseTerrainTileKey key) {
        if (level == null || key == null) return null;
        if (!key.dimensionId().equals(level.dimension().location())) throw new IllegalArgumentException("Terrain tile belongs to another world");
        State state = STATES.get(level, State::new);

        CoarseTerrainTile cached = getMemory(state, key);
        if (cached != null) {
            InitialGenerationProgressTracker.recordTileMemoryHit();
            return cached;
        }

        CompletableFuture<CoarseTerrainTile> future = new CompletableFuture<>();
        CompletableFuture<CoarseTerrainTile> existing = state.inFlight.putIfAbsent(key, future);
        if (existing != null) {
            try {
                return existing.join();
            } catch (Throwable ignored) {
                return null;
            }
        }

        try {
            CoarseTerrainTile loaded = loadOrSample(level, state, key);
            future.complete(loaded);
            return loaded;
        } catch (Throwable t) {
            future.completeExceptionally(t);
            LOGGER.warn("加载粗采样地形瓦片失败 tile=[{},{}]", key.tileX(), key.tileZ(), t);
            return null;
        } finally {
            state.inFlight.remove(key, future);
        }
    }

    /**
     * 释放所有缓存。用新实例替换而非 clear()，确保内部 table 数组被 GC 回收。
     */
    public static void clearAll() {
        STATES.clear();
    }

    public static void clear(ServerLevel level) {
        State state = STATES.peek(level);
        if (state != null && !state.inFlight.isEmpty()) throw new IllegalStateException("Terrain samples still draining");
        STATES.remove(level);
    }

    private static LinkedHashMap<CoarseTerrainTileKey, CoarseTerrainTile> createMemoryMap() {
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CoarseTerrainTileKey, CoarseTerrainTile> eldest) {
                return size() > MEMORY_CAPACITY;
            }
        };
    }

    private static CoarseTerrainTile loadOrSample(ServerLevel level, State state, CoarseTerrainTileKey key) {
        CoarseTerrainTile cached = getMemory(state, key);
        if (cached != null) return cached;

        CoarseTerrainTile stored = CoarseTerrainTileFileStorage.loadTile(level, key);
        if (stored != null) {
            InitialGenerationProgressTracker.recordTileDiskHit();
            putMemory(state, key, stored);
            return stored;
        }

        CoarseTerrainTile sampled = CoarseTerrainTileSampler.sample(level, key);
        if (sampled != null) {
            InitialGenerationProgressTracker.recordTileSampled();
            CoarseTerrainTileFileStorage.saveTile(level, sampled);
            putMemory(state, key, sampled);
        }
        return sampled;
    }

    private static CoarseTerrainTile getMemory(State state, CoarseTerrainTileKey key) {
        synchronized (state.lock) {
            return state.memory.get(key);
        }
    }

    private static void putMemory(State state, CoarseTerrainTileKey key, CoarseTerrainTile tile) {
        synchronized (state.lock) {
            state.memory.put(key, tile);
        }
    }
}
