/* 文件职责：保存自适应精采走廊的列数据并记录寻路越界位置。 */
package net.shiroha233.roadweaver.planning.terrain;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.shiroha233.roadweaver.pathfinding.cache.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.cache.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class AdaptiveCorridorTerrainField implements PathTerrainField {
    private final ServerLevel level;
    private final TerrainSamplingCache metadata;
    private final int step;
    private final int seaLevel;
    private final Set<Long> chunks;
    private final Map<Long, AccurateHeightSample> samples;
    private final Long2IntOpenHashMap rejectedProbeCounts = new Long2IntOpenHashMap();

    AdaptiveCorridorTerrainField(ServerLevel level,
                                 TerrainSamplingCache metadata,
                                 int step,
                                 Set<Long> chunks,
                                 Map<Long, AccurateHeightSample> samples) {
        this.level = level;
        this.metadata = metadata;
        this.step = Math.max(1, step);
        this.seaLevel = level.getSeaLevel();
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.samples = Objects.requireNonNull(samples, "samples");
    }

    static List<BlockPos> gridPositions(Collection<Long> chunks, int step) {
        int safeStep = Math.max(1, step);
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        ArrayList<BlockPos> positions = new ArrayList<>();
        for (long chunkKey : chunks) {
            int minX = ChunkPos.getX(chunkKey) << 4;
            int minZ = ChunkPos.getZ(chunkKey) << 4;
            int maxX = minX + 15;
            int maxZ = minZ + 15;
            int firstX = alignUp(minX, safeStep);
            int firstZ = alignUp(minZ, safeStep);
            for (int z = firstZ; z <= maxZ; z += safeStep) {
                for (int x = firstX; x <= maxX; x += safeStep) {
                    long key = AccurateHeightSample.key(x, z);
                    if (seen.add(key)) {
                        positions.add(new BlockPos(x, 0, z));
                    }
                }
            }
        }
        return positions;
    }

    Set<Long> rejectedHotspots(int limit) {
        if (limit <= 0 || rejectedProbeCounts.isEmpty()) {
            return Set.of();
        }
        return rejectedProbeCounts.long2IntEntrySet().stream()
                .sorted(Comparator
                        .<Long2IntMap.Entry>comparingInt(Long2IntMap.Entry::getIntValue)
                        .reversed()
                        .thenComparingLong(Long2IntMap.Entry::getLongKey))
                .limit(limit)
                .map(Long2IntMap.Entry::getLongKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    Set<Long> boundaryChunks(List<BlockPos> path) {
        LinkedHashSet<Long> boundary = new LinkedHashSet<>();
        if (path == null) {
            return boundary;
        }
        int[][] offsets = {{step, 0}, {-step, 0}, {0, step}, {0, -step}};
        for (BlockPos point : path) {
            for (int[] offset : offsets) {
                int x = point.getX() + offset[0];
                int z = point.getZ() + offset[1];
                if (!hasCell(x, z)) {
                    boundary.add(ChunkPos.asLong(x >> 4, z >> 4));
                }
            }
        }
        return boundary;
    }

    @Override
    public int seaLevel() {
        return seaLevel;
    }

    @Override
    public int height(int x, int z) {
        AccurateHeightSample sample = requireSample(x, z);
        return sample.motionBlockingNoLeaves() > seaLevel + 2
                ? sample.motionBlockingNoLeaves()
                : sample.worldSurfaceWg();
    }

    @Override
    public int oceanFloor(int x, int z) {
        return requireSample(x, z).oceanFloorWg();
    }

    @Override
    public boolean isColumnWater(int x, int z) {
        AccurateHeightSample sample = requireSample(x, z);
        boolean waterBiome = isWaterBiome(x, z);
        return (waterBiome && sample.oceanFloorWg() < seaLevel)
                || sample.oceanFloorWg() < sample.worldSurfaceWg();
    }

    @Override
    public boolean isNearWater(int x, int z, int neighborDistance) {
        if (isColumnWater(x, z)) {
            return true;
        }
        int distance = Math.max(step, alignUp(Math.max(1, neighborDistance), step));
        int[][] offsets = {
                {distance, 0}, {-distance, 0}, {0, distance}, {0, -distance},
                {distance, distance}, {distance, -distance}, {-distance, distance}, {-distance, -distance}
        };
        for (int[] offset : offsets) {
            int nx = x + offset[0];
            int nz = z + offset[1];
            if (hasCell(nx, nz) && isColumnWater(nx, nz)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Holder<Biome> biome(int x, int z) {
        return metadata.getBiome(level, x, z);
    }

    @Override
    public boolean contains(int x, int z) {
        boolean present = hasCell(x, z);
        if (!present) {
            rejectedProbeCounts.addTo(ChunkPos.asLong(x >> 4, z >> 4), 1);
        }
        return present;
    }

    @Override
    public int step() {
        return step;
    }

    @Override
    public boolean hasAccurateSample(int x, int z) {
        return hasCell(x, z);
    }

    @Override
    public boolean isWaterBiome(int x, int z) {
        Holder<Biome> biome = biome(x, z);
        return biome != null && (biome.is(BiomeTags.IS_RIVER)
                || biome.is(BiomeTags.IS_OCEAN)
                || biome.is(BiomeTags.IS_DEEP_OCEAN));
    }

    @Override
    public SampleBundle sampleBundle(int x, int z) {
        AccurateHeightSample sample = requireSample(x, z);
        int surface = sample.motionBlockingNoLeaves() > seaLevel + 2
                ? sample.motionBlockingNoLeaves()
                : sample.worldSurfaceWg();
        boolean waterBiome = isWaterBiome(x, z);
        boolean columnWater = (waterBiome && sample.oceanFloorWg() < seaLevel)
                || sample.oceanFloorWg() < sample.worldSurfaceWg();
        return new SampleBundle(surface, sample.oceanFloorWg(), columnWater, waterBiome,
                Math.max(0, seaLevel - sample.oceanFloorWg()));
    }

    private boolean hasCell(int x, int z) {
        return chunks.contains(ChunkPos.asLong(x >> 4, z >> 4))
                && samples.containsKey(AccurateHeightSample.key(x, z));
    }

    private AccurateHeightSample requireSample(int x, int z) {
        AccurateHeightSample sample = samples.get(AccurateHeightSample.key(x, z));
        if (sample == null || !chunks.contains(ChunkPos.asLong(x >> 4, z >> 4))) {
            throw new IllegalArgumentException("corridor has no accurate sample at " + x + "," + z);
        }
        return sample;
    }

    private static int alignUp(int value, int step) {
        return -Math.floorDiv(-value, step) * step;
    }
}
