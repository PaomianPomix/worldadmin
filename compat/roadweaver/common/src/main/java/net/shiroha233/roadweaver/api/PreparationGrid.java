package net.shiroha233.roadweaver.api;

/** Deterministic preparation cells centered on the origin; the full planning halo is retained. */
public final class PreparationGrid {
    public static final int CORE_BLOCKS = 64 * 16;
    public static final int HALO_BLOCKS = 128 * 16;
    private PreparationGrid() {}

    public static int tileFor(int block) {
        return Math.toIntExact(Math.floorDiv((long) block + CORE_BLOCKS / 2, CORE_BLOCKS));
    }

    public static int minBlock(int tile) {
        return Math.toIntExact((long) tile * CORE_BLOCKS - CORE_BLOCKS / 2 - HALO_BLOCKS);
    }

    public static int maxBlock(int tile) {
        return Math.toIntExact(((long) tile + 1) * CORE_BLOCKS - CORE_BLOCKS / 2 + HALO_BLOCKS - 1);
    }
}
