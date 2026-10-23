package dev.worldadmin.pregen.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SquareChunkPlanTest {
    @Test
    void traversesEveryRequestedChunkExactlyOnceAndResumesAtTheSamePosition() {
        SquareChunkPlan plan = new SquareChunkPlan(-7, 12, 2);
        Set<SquareChunkPlan.ChunkPosition> actual = new HashSet<>();
        for (long index = 0; index < plan.totalChunks(); index++) {
            assertTrue(actual.add(plan.chunkAt(index)), "Repeated chunk at index " + index);
        }
        Set<SquareChunkPlan.ChunkPosition> expected = new HashSet<>();
        for (int z = 10; z <= 14; z++) {
            for (int x = -9; x <= -5; x++) {
                expected.add(new SquareChunkPlan.ChunkPosition(x, z));
            }
        }
        assertEquals(expected, actual);
        SquareChunkPlan restored = new SquareChunkPlan(-7, 12, 2);
        assertEquals(new SquareChunkPlan.ChunkPosition(-7, 12), restored.chunkAt(12));
        assertEquals(plan.chunkAt(13), restored.chunkAt(13));
    }

    @Test
    void hugePlanUsesLongCountsAndIndicesWithoutAllocatingTheGrid() {
        SquareChunkPlan plan = new SquareChunkPlan(0, 0, 1_000_000);
        assertEquals(4_000_004_000_001L, plan.totalChunks());
        assertEquals(new SquareChunkPlan.ChunkPosition(-1_000_000, -1_000_000), plan.chunkAt(0));
        assertEquals(new SquareChunkPlan.ChunkPosition(0, 0), plan.chunkAt(2_000_002_000_000L));
        assertEquals(new SquareChunkPlan.ChunkPosition(1_000_000, 1_000_000),
                plan.chunkAt(4_000_004_000_000L));
        assertThrows(IndexOutOfBoundsException.class, () -> plan.chunkAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> plan.chunkAt(plan.totalChunks()));
    }

    @Test
    void validatesWholeChunksAtDefaultBorderAndRejectsOverflowingCoordinates() {
        assertEquals(1, new SquareChunkPlan(-1_874_999, 1_874_998, 0).totalChunks());
        assertThrows(IllegalArgumentException.class, () -> new SquareChunkPlan(-1_874_999, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SquareChunkPlan(1_874_998, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SquareChunkPlan(Integer.MAX_VALUE, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SquareChunkPlan(Integer.MIN_VALUE, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SquareChunkPlan(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new SquareChunkPlan(0, 0, 1_000_001));
    }
}
