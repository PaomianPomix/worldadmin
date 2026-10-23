package net.shiroha233.roadweaver.features.path.pathlogic.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.config.ModConfig;

import java.util.List;

/**
 * 高度轮廓服务
 */
public final class HeightProfileService {
    private HeightProfileService() {}

    public static record HeightProfile(boolean usePersisted, int[] smoothedY) {}

    public static HeightProfile build(WorldGenLevel world,
                                      List<BlockPos> middlePositions,
                                      ChunkPos currentChunk,
                                      int averagingRadius,
                                      ModConfig cfg,
                                      List<Integer> targetY) {
        boolean slopeLimitEnabled = cfg != null && cfg.slopeLimitEnabled();
        int maxSlopeStepPerTwoSegments = cfg != null ? cfg.maxSlopeStepPerTwoSegments() : 1;
        return build(world, middlePositions, currentChunk, averagingRadius, slopeLimitEnabled, maxSlopeStepPerTwoSegments, targetY);
    }

    public static HeightProfile build(WorldGenLevel world,
                                     List<BlockPos> middlePositions,
                                     ChunkPos currentChunk,
                                     int averagingRadius,
                                     boolean slopeLimitEnabled,
                                     int maxSlopeStepPerTwoSegments,
                                     List<Integer> targetY) {
        int n = middlePositions.size();
        boolean usePersisted = targetY != null && targetY.size() == n;
        if (usePersisted) {
            return new HeightProfile(true, null);
        }
        int[] baseYArr = new int[n];
        for (int ii = 0; ii < n; ii++) {
            java.util.List<Integer> hs = new java.util.ArrayList<>();
            for (int jj = ii - averagingRadius; jj <= ii + averagingRadius; jj++) {
                if (jj >= 0 && jj < n) {
                    BlockPos sp = middlePositions.get(jj);
                    if (new ChunkPos(sp).equals(currentChunk)) {
                        int yTop = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sp.getX(), sp.getZ());
                        hs.add(yTop);
                    }
                }
            }
            if (hs.isEmpty()) {
                BlockPos mid = middlePositions.get(ii);
                if (new ChunkPos(mid).equals(currentChunk)) {
                    baseYArr[ii] = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mid.getX(), mid.getZ());
                } else {
                    baseYArr[ii] = middlePositions.get(ii).getY();
                }
            } else {
                baseYArr[ii] = (int) Math.round(hs.stream().mapToInt(Integer::intValue).average().orElse(middlePositions.get(ii).getY()));
            }
        }
        if (!slopeLimitEnabled) {
            int[] noSmoothed = new int[n];
            for (int ii = 0; ii < n; ii++) noSmoothed[ii] = baseYArr[ii];
            return new HeightProfile(false, noSmoothed);
        }

        int[] smoothed = new int[n];
        for (int ii = 0; ii < n; ii++) smoothed[ii] = baseYArr[ii];
        int step2 = Math.max(0, Math.min(8, maxSlopeStepPerTwoSegments));
        int halfLow = Math.max(0, step2 / 2);
        int halfHigh = Math.max(0, (step2 + 1) / 2);
        
        for (int ii = 1; ii < n; ii++) {
            int y = smoothed[ii];
            if (ii == 1) {
                int py = smoothed[ii - 1];
                if (y > py + halfLow) y = py + halfLow;
                if (y < py - halfLow) y = py - halfLow;
            } else {
                int py = smoothed[ii - 1];
                if (y > py + halfHigh) y = py + halfHigh;
                if (y < py - halfHigh) y = py - halfHigh;
                int p2 = smoothed[ii - 2];
                int hi = p2 + step2;
                int lo = p2 - step2;
                if (y > hi) y = hi;
                if (y < lo) y = lo;
            }
            smoothed[ii] = y;
        }
        
        for (int ii = n - 2; ii >= 0; ii--) {
            int y = smoothed[ii];
            if (ii == n - 2) {
                int ny = smoothed[ii + 1];
                if (y > ny + halfLow) y = ny + halfLow;
                if (y < ny - halfLow) y = ny - halfLow;
            } else {
                int ny = smoothed[ii + 1];
                if (y > ny + halfHigh) y = ny + halfHigh;
                if (y < ny - halfHigh) y = ny - halfHigh;
                int n2 = smoothed[ii + 2];
                int hi = n2 + step2;
                int lo = n2 - step2;
                if (y > hi) y = hi;
                if (y < lo) y = lo;
            }
            smoothed[ii] = y;
        }
        return new HeightProfile(false, smoothed);
    }
}
