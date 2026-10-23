package net.shiroha233.roadweaver.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparationGridTest {
    @Test void positiveAndNegativeCoreBoundariesHaveNoGapsOrOverlap() {
        assertEquals(-1,PreparationGrid.tileFor(-513));
        assertEquals(0,PreparationGrid.tileFor(-512));
        assertEquals(0,PreparationGrid.tileFor(511));
        assertEquals(1,PreparationGrid.tileFor(512));
        assertEquals(-2,PreparationGrid.tileFor(-1537));
        assertEquals(-1,PreparationGrid.tileFor(-1536));
    }

    @Test void originDependenciesUseOneCoreAndTheFullHaloIsRetained() {
        assertEquals(0,PreparationGrid.tileFor(-128));
        assertEquals(0,PreparationGrid.tileFor(191));
        assertEquals(-2560,PreparationGrid.minBlock(0));
        assertEquals(2559,PreparationGrid.maxBlock(0));
        assertEquals(5120,PreparationGrid.maxBlock(0)-PreparationGrid.minBlock(0)+1);
        assertEquals(1024,PreparationGrid.minBlock(1)-PreparationGrid.minBlock(0));
        assertEquals(1024,PreparationGrid.maxBlock(0)-PreparationGrid.maxBlock(-1));
    }

    @Test void shiftingCoordinatesDoesNotOverflowAtIntegerExtremes() {
        assertEquals(2_097_152,PreparationGrid.tileFor(Integer.MAX_VALUE));
        assertEquals(-2_097_152,PreparationGrid.tileFor(Integer.MIN_VALUE));
    }
}
