package dev.worldadmin.pregen.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompletionFrontierTest {
    @Test
    void crashCheckpointReplaysSuccessesBeyondAnUnfinishedChunk() {
        CompletionFrontier beforeCrash = new CompletionFrontier(0, 6, 3);
        beforeCrash.start(0);
        beforeCrash.start(1);
        beforeCrash.start(2);
        beforeCrash.succeed(2);
        beforeCrash.succeed(0);
        assertEquals(1, beforeCrash.cursor());

        CompletionFrontier restored = new CompletionFrontier(beforeCrash.cursor(), 6, 3);
        List<Long> replayed = new ArrayList<>();
        while (!restored.isFinished()) {
            long index = restored.nextIndex();
            restored.start(index);
            replayed.add(index);
            restored.succeed(index);
        }
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), replayed);
        assertEquals(6, restored.cursor());
    }

    @Test
    void failedGapPinsCheckpointAndBoundsBufferedSuccessesUntilRetried() {
        CompletionFrontier tracker = new CompletionFrontier(10, 20, 3);
        tracker.start(10);
        tracker.start(11);
        tracker.start(12);
        tracker.succeed(11);
        tracker.fail(10);
        tracker.succeed(12);

        assertEquals(10, tracker.cursor());
        assertEquals(0, tracker.inFlightCount());
        assertEquals(3, tracker.outstandingCount());
        assertFalse(tracker.hasCapacity());
        assertThrows(IllegalStateException.class, () -> tracker.start(13));
        assertEquals(10, tracker.firstFailedIndex().orElseThrow());

        tracker.retry(10);
        assertEquals(1, tracker.inFlightCount());
        tracker.succeed(10);
        assertEquals(13, tracker.cursor());
        assertEquals(0, tracker.outstandingCount());
        assertTrue(tracker.hasCapacity());
        assertTrue(tracker.firstFailedIndex().isEmpty());
    }

    @Test
    void rejectsUnissuedAndDuplicateCompletionsWithoutCorruptingTheCheckpoint() {
        CompletionFrontier tracker = new CompletionFrontier(0, 2, 2);
        assertThrows(IllegalArgumentException.class, () -> tracker.start(1));
        assertThrows(IllegalStateException.class, () -> tracker.succeed(0));
        tracker.start(0);
        tracker.fail(0);
        assertThrows(IllegalStateException.class, () -> tracker.succeed(0));
        tracker.retry(0);
        tracker.succeed(0);
        assertThrows(IllegalStateException.class, () -> tracker.succeed(0));
        assertEquals(1, tracker.cursor());
        assertEquals(0, tracker.inFlightCount());
    }

    @Test
    void worksNearTheLongBoundaryAndAcceptsACompletedCheckpoint() {
        CompletionFrontier tracker = new CompletionFrontier(Long.MAX_VALUE - 1, Long.MAX_VALUE, 1);
        tracker.start(Long.MAX_VALUE - 1);
        tracker.succeed(Long.MAX_VALUE - 1);
        assertTrue(tracker.isFinished());
        assertFalse(tracker.hasCapacity());
        assertEquals(Long.MAX_VALUE, tracker.cursor());
        assertTrue(new CompletionFrontier(0, 0, 1).isFinished());
        assertThrows(IllegalArgumentException.class, () -> new CompletionFrontier(2, 1, 1));
    }
}
