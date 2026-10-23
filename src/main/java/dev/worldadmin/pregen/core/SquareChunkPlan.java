package dev.worldadmin.pregen.core;

/** A resumable square of complete chunks within Minecraft's default world border. */
public record SquareChunkPlan(int centerChunkX, int centerChunkZ, int radius) {
    public static final int MAX_RADIUS = 1_000_000;
    // The default border covers blocks [-29_999_984, 29_999_984).
    public static final int MIN_CHUNK_COORDINATE = -1_874_999;
    public static final int MAX_CHUNK_COORDINATE = 1_874_998;

    public SquareChunkPlan {
        if (radius < 0 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("Chunk radius must be between 0 and " + MAX_RADIUS);
        }
        checkAxis(centerChunkX, radius);
        checkAxis(centerChunkZ, radius);
    }

    private static void checkAxis(int center, int radius) {
        if ((long) center - radius < MIN_CHUNK_COORDINATE
                || (long) center + radius > MAX_CHUNK_COORDINATE) {
            throw new IllegalArgumentException("Requested chunks exceed Minecraft's default world border");
        }
    }

    public long totalChunks() {
        long width = 2L * radius + 1L;
        return width * width;
    }

    /** Rows run west to east, starting at the square's north-west corner. */
    public ChunkPosition chunkAt(long index) {
        if (index < 0 || index >= totalChunks()) {
            throw new IndexOutOfBoundsException("Chunk index out of range: " + index);
        }
        long width = 2L * radius + 1L;
        return new ChunkPosition(
                (int) ((long) centerChunkX - radius + index % width),
                (int) ((long) centerChunkZ - radius + index / width));
    }

    public record ChunkPosition(int x, int z) {}
}
