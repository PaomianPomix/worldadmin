package net.shiroha233.roadweaver.generation;

/** Long arithmetic avoids overflowing coordinates near the world border. */
public record RoadInfluenceBounds(long minX, long minZ, long maxX, long maxZ) {
    public static RoadInfluenceBounds endpoints(int ax, int az, int bx, int bz, int margin) {
        return new RoadInfluenceBounds(Math.min((long) ax, bx)-margin, Math.min((long) az, bz)-margin,
                Math.max((long) ax, bx)+margin, Math.max((long) az, bz)+margin);
    }
    public boolean intersects(int x0, int z0, int x1, int z1) {
        return minX <= x1 && maxX >= x0 && minZ <= z1 && maxZ >= z0;
    }
}
