package net.shiroha233.roadweaver.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoadInfluenceBoundsTest {
    @Test void roadsCrossingRegionAreIncludedEvenWhenBothEndpointsAreOutside() {
        var road = RoadInfluenceBounds.endpoints(-2000, -100, 2000, 100, 512);
        assertTrue(road.intersects(-16, -16, 16, 16));
        assertFalse(road.intersects(20_000, 20_000, 20_016, 20_016));
    }

    @Test void detourEnvelopeIncludesNearbyRegionsWithoutIncludingAnUnrelatedWorldRegion() {
        var fallback = RoadInfluenceBounds.endpoints(-100, 0, 100, 0, 2048 + 128 + 512);
        assertTrue(fallback.intersects(0, 2048, 16, 2064));
        assertFalse(fallback.intersects(0, 30_000, 16, 30_016));
    }

    @Test void borderCoordinatesDoNotOverflow() {
        var road = RoadInfluenceBounds.endpoints(Integer.MAX_VALUE - 10, -10, Integer.MAX_VALUE, 10, 2688);
        assertTrue(road.maxX() > Integer.MAX_VALUE);
        assertTrue(road.intersects(Integer.MAX_VALUE - 5, 0, Integer.MAX_VALUE, 1));
    }
}
