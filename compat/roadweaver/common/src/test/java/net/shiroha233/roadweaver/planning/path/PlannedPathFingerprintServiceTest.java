/* 文件职责：验证路径指纹包含实际采样策略和全部关键寻路配置。 */
package net.shiroha233.roadweaver.planning.path;

import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import net.shiroha233.roadweaver.persistence.WorldgenFingerprint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PlannedPathFingerprintServiceTest {
    private static final WorldgenFingerprint WORLD = new WorldgenFingerprint("world-a", 1, 4000);

    @Test
    void sameInputsProduceStableFingerprint() {
        PathfindingCostConfig config = new PathfindingCostConfig();

        assertEquals(
                PlannedPathFingerprintService.create(WORLD, TerrainSamplingMode.COARSE_CORRIDOR, config),
                PlannedPathFingerprintService.create(WORLD, TerrainSamplingMode.COARSE_CORRIDOR, config.snapshot()));
    }

    @Test
    void modeAndCostChangesInvalidatePath() {
        PathfindingCostConfig original = new PathfindingCostConfig();
        PathfindingCostConfig changed = original.snapshot();
        changed.setElevationWeight(original.elevationWeight() + 1);
        String coarse = PlannedPathFingerprintService.create(WORLD, TerrainSamplingMode.COARSE_CORRIDOR, original);

        assertNotEquals(coarse,
                PlannedPathFingerprintService.create(WORLD, TerrainSamplingMode.FULL_REGION, original));
        assertNotEquals(coarse,
                PlannedPathFingerprintService.create(WORLD, TerrainSamplingMode.COARSE_CORRIDOR, changed));
    }

    @Test
    void coarseSamplingQualityInvalidatesPlannedPathsWithoutChangingAStarSettings() {
        String property=net.shiroha233.roadweaver.pathfinding.terrain.region.CoarseSamplingPolicy.PROPERTY;
        String previous=System.getProperty(property);
        try {
            PathfindingCostConfig config=new PathfindingCostConfig();
            System.setProperty(property,"8");
            String original=PlannedPathFingerprintService.create(WORLD,TerrainSamplingMode.COARSE_CORRIDOR,config);
            System.setProperty(property,"32");
            String sparse=PlannedPathFingerprintService.create(WORLD,TerrainSamplingMode.COARSE_CORRIDOR,config);
            assertNotEquals(original,sparse);
            assertEquals(8,config.effectiveAStarStep());
        } finally {
            if(previous==null) System.clearProperty(property); else System.setProperty(property,previous);
        }
    }
}
