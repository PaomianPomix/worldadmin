package net.shiroha233.roadweaver.map.tile.render;

/**
 * 高度着色与颜色工具。
 */
public final class HeightShader {
    private HeightShader() {}

    private static final double LIGHT_AZIMUTH = Math.toRadians(315);
    private static final double LIGHT_ELEVATION = Math.toRadians(45);

    public static double computeShade(int[][] heights) {
        double dzdx = (heights[0][2] + 2.0 * heights[1][2] + heights[2][2]
                     - heights[0][0] - 2.0 * heights[1][0] - heights[2][0]) / 8.0;
        double dzdy = (heights[2][0] + 2.0 * heights[2][1] + heights[2][2]
                     - heights[0][0] - 2.0 * heights[0][1] - heights[0][2]) / 8.0;

        double slope = Math.atan(Math.sqrt(dzdx * dzdx + dzdy * dzdy));
        double aspect = Math.atan2(dzdy, -dzdx);

        double zenith = Math.PI / 2 - LIGHT_ELEVATION;
        double azimuthRad = LIGHT_AZIMUTH - Math.PI / 2;

        double shade = Math.cos(zenith) * Math.cos(slope)
                     + Math.sin(zenith) * Math.sin(slope) * Math.cos(azimuthRad - aspect);

        return 0.75 + clamp(shade * 0.25, -0.25, 0.25);
    }

    public static double simpleShade(int centerHeight, int neighborHeight, int distance) {
        if (distance <= 0) return 1.0;
        double gradient = (centerHeight - neighborHeight) / (double) distance;
        if (gradient > 1) return 0.6;
        if (gradient > 0.3) return 0.75;
        if (gradient < -1) return 1.0;
        if (gradient < -0.3) return 0.9;
        return 0.82;
    }

    public static int multiplyRgb(int argb, double factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = clampChannel((int) Math.round(((argb >>> 16) & 0xFF) * factor));
        int g = clampChannel((int) Math.round(((argb >>> 8) & 0xFF) * factor));
        int b = clampChannel((int) Math.round((argb & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int clampChannel(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
