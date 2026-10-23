package net.shiroha233.roadweaver.pathfinding.terrain.region;

import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoarseSamplingPolicyTest {
    @Test void explicitQualitySettingsAreValidated() {
        assertEquals(32,CoarseSamplingPolicy.parse(null));
        assertEquals(8,CoarseSamplingPolicy.parse("8"));
        assertEquals(16,CoarseSamplingPolicy.parse(" 16 "));
        assertEquals(32,CoarseSamplingPolicy.parse("32"));
        assertThrows(IllegalArgumentException.class,()->CoarseSamplingPolicy.parse("0"));
        assertThrows(IllegalArgumentException.class,()->CoarseSamplingPolicy.parse("17"));
    }

    @Test void coarseResolutionDoesNotChangeAStarResolution() {
        String previous=System.getProperty(CoarseSamplingPolicy.PROPERTY);
        try {
            System.setProperty(CoarseSamplingPolicy.PROPERTY,"32");
            PathfindingCostConfig path=new PathfindingCostConfig();
            assertEquals(8,path.effectiveAStarStep());
            assertEquals(32,CoarseSamplingPolicy.sampleStep(path.effectiveAStarStep()));
            assertEquals(8,path.effectiveAStarStep());
            assertEquals(64,CoarseSamplingPolicy.sampleStep(64));
            System.setProperty(CoarseSamplingPolicy.PROPERTY,"8");
            assertEquals(8,CoarseSamplingPolicy.sampleStep(path.effectiveAStarStep()));
        } finally {
            if(previous==null) System.clearProperty(CoarseSamplingPolicy.PROPERTY);
            else System.setProperty(CoarseSamplingPolicy.PROPERTY,previous);
        }
    }
}
