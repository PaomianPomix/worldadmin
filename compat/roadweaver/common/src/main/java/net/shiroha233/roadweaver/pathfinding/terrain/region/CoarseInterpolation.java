package net.shiroha233.roadweaver.pathfinding.terrain.region;

/** Shared interpolation for tiled and array-backed coarse fields. */
final class CoarseInterpolation {
    private CoarseInterpolation() {}

    static int height(short[] values, int width, int height, int minX, int minZ, int step, int x, int z) {
        double gx = grid(x, minX, step, width);
        double gz = grid(z, minZ, step, height);
        int x0 = (int) gx, z0 = (int) gz;
        int x1 = Math.min(x0 + 1, width - 1), z1 = Math.min(z0 + 1, height - 1);
        double tx = gx - x0, tz = gz - z0;
        double north = values[z0 * width + x0] * (1-tx) + values[z0 * width + x1] * tx;
        double south = values[z1 * width + x0] * (1-tx) + values[z1 * width + x1] * tx;
        return (int) Math.round(north * (1-tz) + south * tz);
    }

    static byte flags(byte[] values, int width, int height, int minX, int minZ, int step, int x, int z) {
        double gx = grid(x, minX, step, width);
        double gz = grid(z, minZ, step, height);
        int x0 = (int) gx, z0 = (int) gz;
        int x1 = Math.min(x0 + 1, width - 1), z1 = Math.min(z0 + 1, height - 1);
        // Preserve exact grid samples. Between samples, any contributing wet
        // corner makes the coarse cell wet, avoiding nearest-neighbor dry gaps.
        int result = values[z0 * width + x0];
        if (gx > x0) result |= values[z0 * width + x1];
        if (gz > z0) result |= values[z1 * width + x0];
        if (gx > x0 && gz > z0) result |= values[z1 * width + x1];
        return (byte) result;
    }

    private static double grid(int coordinate, int origin, int step, int size) {
        return Math.max(0, Math.min(size - 1, (coordinate - (double) origin) / step));
    }
}
