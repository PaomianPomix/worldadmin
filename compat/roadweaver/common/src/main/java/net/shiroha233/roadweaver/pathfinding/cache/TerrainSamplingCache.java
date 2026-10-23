/* 文件职责：缓存道路寻路期间的高度、水体与生物群系采样结果。 */
package net.shiroha233.roadweaver.pathfinding.cache;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 地形采样缓存
 */
public final class TerrainSamplingCache {
    private ConcurrentHashMap<Long, Boolean> waterCache = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, ConcurrentHashMap<Long, Boolean>> nearWaterCacheByDistance = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Boolean> columnWaterCache = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Integer> heightCache = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Integer> oceanFloorCache = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Holder<Biome>> biomeCache = new ConcurrentHashMap<>();

    private volatile FastHeightSampler fastSampler;
    private volatile AccurateHeightSampler accurateSampler;

    private static long hashXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public int height(ServerLevel level, int x, int z) {
        long key = hashXZ(x, z);
        Integer cached = heightCache.get(key);
        if (cached != null) {
            TerrainSamplingStats.recordCacheHit();
            return cached;
        }
        TerrainSamplingStats.recordCacheMiss();
        ensureFastSampler(level);
        int h = fastSampler.sampleHeight(x, z);
        heightCache.put(key, h);
        return h;
    }

    public boolean isWaterLike(ServerLevel level, int x, int z) {
        long key = hashXZ(x, z);
        Boolean cached = waterCache.get(key);
        if (cached != null) {
            TerrainSamplingStats.recordCacheHit();
            return cached;
        }
        TerrainSamplingStats.recordCacheMiss();
        var chunkSource = level.getChunkSource();
        var randomState = chunkSource.getGeneratorState().randomState();
        var biomeSource = chunkSource.getGenerator().getBiomeSource();
        Holder<Biome> biome = biomeSource.getNoiseBiome(x >> 2, 16, z >> 2, randomState.sampler());
        boolean res = biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN);
        waterCache.put(key, res);
        return res;
    }

    public int oceanFloor(ServerLevel level, int x, int z) {
        long key = hashXZ(x, z);
        Integer cached = oceanFloorCache.get(key);
        if (cached != null) {
            TerrainSamplingStats.recordCacheHit();
            return cached;
        }
        TerrainSamplingStats.recordCacheMiss();
        ensureFastSampler(level);
        int h = fastSampler.sampleHeight(x, z);
        oceanFloorCache.put(key, h);
        return h;
    }

    public boolean isNearWaterLike(ServerLevel level, int x, int z, int neighborDistance) {
        int distance = Math.max(0, neighborDistance);
        ConcurrentHashMap<Long, Boolean> nearWaterCache = nearWaterCacheByDistance.computeIfAbsent(
                distance,
                ignored -> new ConcurrentHashMap<>());
        long key = hashXZ(x, z);
        Boolean cached = nearWaterCache.get(key);
        if (cached != null) {
            TerrainSamplingStats.recordCacheHit();
            return cached;
        }
        TerrainSamplingStats.recordCacheMiss();
        int d = distance;
        int[][] offsets = {
                {d, 0}, {-d, 0}, {0, d}, {0, -d},
                {d, d}, {d, -d}, {-d, d}, {-d, -d}
        };
        for (int[] off : offsets) {
            if (isWaterLike(level, x + off[0], z + off[1])) {
                nearWaterCache.put(key, true);
                return true;
            }
        }
        nearWaterCache.put(key, false);
        return false;
    }

    public boolean isColumnWater(ServerLevel level, int x, int z) {
        long key = hashXZ(x, z);
        Boolean cached = columnWaterCache.get(key);
        if (cached != null) {
            TerrainSamplingStats.recordCacheHit();
            return cached;
        }
        TerrainSamplingStats.recordCacheMiss();
        int of = oceanFloor(level, x, z);
        int sea = level.getSeaLevel();
        int surface = height(level, x, z);
        boolean isWaterBiome = isWaterLike(level, x, z);
        boolean biomeWater = isWaterBiome && of < sea;
        boolean heightWater = of < surface;
        boolean res = biomeWater || heightWater;
        columnWaterCache.put(key, res);
        return res;
    }

    public Holder<Biome> getBiome(ServerLevel level, int x, int z) {
        long key = hashXZ(x >> 2, z >> 2);
        Holder<Biome> cached = biomeCache.get(key);
        if (cached != null) {
            TerrainSamplingStats.recordCacheHit();
            return cached;
        }
        TerrainSamplingStats.recordCacheMiss();
        var chunkSource = level.getChunkSource();
        var randomState = chunkSource.getGeneratorState().randomState();
        var biomeSource = chunkSource.getGenerator().getBiomeSource();
        Holder<Biome> biome = biomeSource.getNoiseBiome(x >> 2, 16, z >> 2, randomState.sampler());
        biomeCache.put(key, biome);
        return biome;
    }

    public void prewarmRegion(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int step) {
        ensureFastSampler(level);
        fastSampler.prewarmRegion(minX, minZ, maxX, maxZ, step);
    }

    public AccurateHeightSampler getAccurateSampler(ServerLevel level) {
        ensureAccurateSampler(level);
        return accurateSampler;
    }

    /**
     * 释放所有缓存。用新实例替换而非 clear()，确保内部 table 数组被 GC 回收。
     */
    public void clear() {
        waterCache = new ConcurrentHashMap<>();
        nearWaterCacheByDistance = new ConcurrentHashMap<>();
        columnWaterCache = new ConcurrentHashMap<>();
        heightCache = new ConcurrentHashMap<>();
        oceanFloorCache = new ConcurrentHashMap<>();
        biomeCache = new ConcurrentHashMap<>();
        if (fastSampler != null) fastSampler.clearCache();
        // 精采 sampler 由维度生命周期持有，单条道路结束不能关闭共享 GPU 后端。
        accurateSampler = null;
    }

    private void ensureFastSampler(ServerLevel level) {
        if (fastSampler == null) {
            synchronized (this) {
                if (fastSampler == null) {
                    fastSampler = FastHeightSampler.create(level);
                }
            }
        }
    }

    private void ensureAccurateSampler(ServerLevel level) {
        if (accurateSampler == null) {
            synchronized (this) {
                if (accurateSampler == null) {
                    accurateSampler = AccurateHeightSampler.create(level);
                }
            }
        }
    }
}
