package dev.worldadmin.compat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DistantHorizonsRoadGateTest {
    @Test void coordinatesAreChunksAndIncludeFullFourChunkLodPlusDependencies() {
        assertArrayEquals(new int[] {1472, -1728, 1791, -1409},
                DistantHorizonsRoadGate.preparationBounds(100, -100));
        assertArrayEquals(new int[] {-128, -128, 191, 191},
                DistantHorizonsRoadGate.preparationBounds(0, 0));
    }

    @Test void roughAndPreexistingGenerationDoNotScheduleRoads() {
        assertTrue(DistantHorizonsRoadGate.requiresRoads((byte) 0, "FEATURES"));
        assertTrue(DistantHorizonsRoadGate.requiresRoads((byte) 0, "INTERNAL_SERVER"));
        assertFalse(DistantHorizonsRoadGate.requiresRoads((byte) 0, "SURFACE"));
        assertFalse(DistantHorizonsRoadGate.requiresRoads((byte) 0, "PRE_EXISTING_ONLY"));
        assertFalse(DistantHorizonsRoadGate.requiresRoads((byte) 1, "FEATURES"));
        assertFalse(DistantHorizonsRoadGate.requiresRoads((byte) 12, "INTERNAL_SERVER"));
    }

    @Test void malformedCoordinatesCannotWrapIntoADifferentWorldRegion() {
        assertThrows(IllegalArgumentException.class,
                () -> DistantHorizonsRoadGate.preparationBounds(Integer.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DistantHorizonsRoadGate.preparationBounds(0, Integer.MIN_VALUE));
    }
}
