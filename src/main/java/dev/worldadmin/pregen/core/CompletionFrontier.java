package dev.worldadmin.pregen.core;

import java.util.NavigableMap;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Tracks a bounded window of submitted work. Persist only {@link #cursor()}: after
 * a restart every index at or above it is eligible for replay, including work
 * which finished beyond a failed or unfinished index. Use from one owner thread.
 */
public final class CompletionFrontier {
    private enum State { RUNNING, SUCCEEDED, FAILED }

    private final long totalChunks;
    private final int maxOutstanding;
    private final NavigableMap<Long, State> outstanding = new TreeMap<>();
    private long cursor;
    private long nextIndex;
    private int inFlight;

    public CompletionFrontier(long initialCursor, long totalChunks, int maxOutstanding) {
        if (totalChunks < 0 || initialCursor < 0 || initialCursor > totalChunks) {
            throw new IllegalArgumentException("Cursor must be within the work range");
        }
        if (maxOutstanding < 1) {
            throw new IllegalArgumentException("Outstanding work limit must be positive");
        }
        this.cursor = initialCursor;
        this.nextIndex = initialCursor;
        this.totalChunks = totalChunks;
        this.maxOutstanding = maxOutstanding;
    }

    public long cursor() {
        return cursor;
    }

    public long nextIndex() {
        return nextIndex;
    }

    public int inFlightCount() {
        return inFlight;
    }

    /** Includes finished entries waiting behind an earlier unfinished entry. */
    public int outstandingCount() {
        return outstanding.size();
    }

    public boolean hasCapacity() {
        return nextIndex < totalChunks && outstanding.size() < maxOutstanding;
    }

    public boolean isFinished() {
        return cursor == totalChunks;
    }

    /** Reserve immediately before submitting; call fail if submission throws. */
    public void start(long index) {
        if (!hasCapacity()) {
            throw new IllegalStateException("No capacity for new work");
        }
        if (index != nextIndex) {
            throw new IllegalArgumentException("New work must start at index " + nextIndex);
        }
        outstanding.put(index, State.RUNNING);
        nextIndex++;
        inFlight++;
    }

    public void succeed(long index) {
        requireState(index, State.RUNNING);
        outstanding.put(index, State.SUCCEEDED);
        inFlight--;
        while (outstanding.get(cursor) == State.SUCCEEDED) {
            outstanding.remove(cursor);
            cursor++;
        }
    }

    public void fail(long index) {
        requireState(index, State.RUNNING);
        outstanding.put(index, State.FAILED);
        inFlight--;
    }

    public OptionalLong firstFailedIndex() {
        return outstanding.entrySet().stream()
                .filter(entry -> entry.getValue() == State.FAILED)
                .mapToLong(entry -> entry.getKey()).findFirst();
    }

    public void retry(long index) {
        requireState(index, State.FAILED);
        outstanding.put(index, State.RUNNING);
        inFlight++;
    }

    private void requireState(long index, State expected) {
        if (outstanding.get(index) != expected) {
            throw new IllegalStateException("Index " + index + " is not " + expected);
        }
    }
}
