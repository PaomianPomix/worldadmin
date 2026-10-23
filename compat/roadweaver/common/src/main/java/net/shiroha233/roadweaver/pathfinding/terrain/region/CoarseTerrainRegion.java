package net.shiroha233.roadweaver.pathfinding.terrain.region;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.shiroha233.roadweaver.pathfinding.terrain.PathTerrainField;

import java.util.Map;

/**
 * 区域级粗采样结果。
 */
public final class CoarseTerrainRegion implements PathTerrainField {
    private static final byte FLAG_COLUMN_WATER = 1;
    private static final byte FLAG_WATER_BIOME = 1 << 1;
    private static final byte FLAG_NEAR_WATER = 1 << 2;

    private final CoarseRegionBounds bounds;
    private final int seaLevel;
    private short[] heights;
    private short[] oceanFloors;
    private byte[] flags;
    private int[] terrainArgb;
    private Map<CoarseTerrainTileKey, CoarseTerrainTile> tiles;

    public CoarseTerrainRegion(CoarseRegionBounds bounds,
                               int seaLevel,
                               Map<CoarseTerrainTileKey, CoarseTerrainTile> tiles) {
        if (bounds == null || tiles == null || tiles.isEmpty()) {
            throw new IllegalArgumentException("coarse region tiles must not be empty");
        }
        this.bounds = bounds;
        this.seaLevel = seaLevel;
        this.heights = null;
        this.oceanFloors = null;
        this.flags = null;
        this.terrainArgb = null;
        this.tiles = Map.copyOf(tiles);
    }

    public CoarseTerrainRegion(CoarseRegionBounds bounds,
                               int seaLevel,
                               short[] heights,
                               short[] oceanFloors,
                               byte[] flags,
                               int[] terrainArgb) {
        this.bounds = bounds;
        this.seaLevel = seaLevel;
        this.heights = heights;
        this.oceanFloors = oceanFloors;
        this.flags = flags;
        this.terrainArgb = terrainArgb;
        this.tiles = null;
        int expected = Math.toIntExact(bounds.sampleCount());
        if (heights.length != expected || oceanFloors.length != expected
                || flags.length != expected || terrainArgb.length != expected) {
            throw new IllegalArgumentException("coarse region arrays do not match bounds");
        }
    }

    public CoarseRegionBounds bounds() {
        return bounds;
    }

    public int terrainArgbAtIndex(int index) {
        if (tiles == null) return terrainArgb[index];
        int ix = index % bounds.width();
        int iz = index / bounds.width();
        int x = bounds.blockXAt(ix);
        int z = bounds.blockZAt(iz);
        CoarseTerrainTile tile = tileAt(x, z);
        return tile != null ? tile.terrainArgb(x, z) : 0x00000000;
    }

    @Override
    public int seaLevel() {
        return seaLevel;
    }

    @Override
    public int height(int x, int z) {
        if (!contains(x, z)) return seaLevel;
        if (tiles != null) {
            CoarseTerrainTile tile = tileAt(x, z);
            return tile != null ? tile.height(x, z) : seaLevel;
        }
        return interpolated(heights, x, z);
    }

    @Override
    public int oceanFloor(int x, int z) {
        if (!contains(x, z)) return seaLevel;
        if (tiles != null) {
            CoarseTerrainTile tile = tileAt(x, z);
            return tile != null ? tile.oceanFloor(x, z) : seaLevel;
        }
        return interpolated(oceanFloors, x, z);
    }

    @Override
    public boolean isColumnWater(int x, int z) {
        if (!contains(x, z)) return false;
        if (tiles != null) {
            CoarseTerrainTile tile = tileAt(x, z);
            return tile != null && tile.isColumnWater(x, z);
        }
        return (interpolatedFlags(x, z) & FLAG_COLUMN_WATER) != 0;
    }

    @Override
    public boolean isNearWater(int x, int z, int neighborDistance) {
        if (!contains(x, z)) return false;
        if (tiles != null) {
            CoarseTerrainTile tile = tileAt(x, z);
            if (tile != null && tile.isNearWater(x, z)) return true;
        } else if ((interpolatedFlags(x, z) & FLAG_NEAR_WATER) != 0) {
            return true;
        }
        int steps = Math.max(1, ceilDiv(Math.max(0, neighborDistance), bounds.step()));
        for (int dz = -steps; dz <= steps; dz++) {
            for (int dx = -steps; dx <= steps; dx++) {
                if (dx == 0 && dz == 0) continue;
                int wx = x + dx * bounds.step();
                int wz = z + dz * bounds.step();
                if (contains(wx, wz) && isColumnWater(wx, wz)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Holder<Biome> biome(int x, int z) {
        return null;
    }

    @Override
    public boolean contains(int x, int z) {
        return bounds.contains(x, z);
    }

    @Override
    public int step() {
        return bounds.step();
    }

    /**
     * 释放内部大数组与瓦片引用，协助 GC 回收。调用后此对象不可再用。
     */
    @Override
    public void dispose() {
        heights = null;
        oceanFloors = null;
        flags = null;
        terrainArgb = null;
        tiles = null;
    }

    @Override
    public boolean isWaterBiome(int x, int z) {
        if (!contains(x, z)) return false;
        if (tiles != null) {
            CoarseTerrainTile tile = tileAt(x, z);
            return tile != null && tile.isWaterBiome(x, z);
        }
        return (interpolatedFlags(x, z) & FLAG_WATER_BIOME) != 0;
    }

    @Override
    public SampleBundle sampleBundle(int x, int z) {
        if (!contains(x, z)) {
            return new SampleBundle(seaLevel, seaLevel, false, false, 0);
        }
        if (tiles != null) {
            CoarseTerrainTile tile = tileAt(x, z);
            return tile != null ? tile.sampleBundle(x, z) : new SampleBundle(seaLevel, seaLevel, false, false, 0);
        }
        int ocean = oceanFloor(x, z);
        byte bits = interpolatedFlags(x, z);
        boolean columnWater = (bits & FLAG_COLUMN_WATER) != 0;
        boolean waterBiome = (bits & FLAG_WATER_BIOME) != 0;
        int depth = Math.max(0, seaLevel - ocean);
        return new SampleBundle(height(x, z), ocean, columnWater, waterBiome, depth);
    }

    static byte flags(boolean columnWater, boolean waterBiome, boolean nearWater) {
        int value = 0;
        if (columnWater) value |= FLAG_COLUMN_WATER;
        if (waterBiome) value |= FLAG_WATER_BIOME;
        if (nearWater) value |= FLAG_NEAR_WATER;
        return (byte) value;
    }

    private CoarseTerrainTile tileAt(int x, int z) {
        if (tiles == null) return null;
        CoarseTerrainTileKey key = CoarseTerrainTileKey.forBlock(bounds.dimensionId(), x, z, bounds.step());
        return tiles.get(key);
    }

    private boolean hasFlag(int index, byte flag) {
        return (flags[index] & flag) != 0;
    }

    private int interpolated(short[] values, int x, int z) {
        return CoarseInterpolation.height(values, bounds.width(), bounds.height(), bounds.minX(), bounds.minZ(), bounds.step(), x, z);
    }

    private byte interpolatedFlags(int x, int z) {
        return CoarseInterpolation.flags(flags, bounds.width(), bounds.height(), bounds.minX(), bounds.minZ(), bounds.step(), x, z);
    }

    private static int ceilDiv(int value, int divisor) {
        return Math.floorDiv(value + divisor - 1, divisor);
    }
}
