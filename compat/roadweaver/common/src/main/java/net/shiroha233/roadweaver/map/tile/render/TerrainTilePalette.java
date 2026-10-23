package net.shiroha233.roadweaver.map.tile.render;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * 低精度瓦片调色板。
 */
public final class TerrainTilePalette {
    private TerrainTilePalette() {}

    public static int colorFor(Holder<Biome> biome,
                               int height,
                               int seaLevel,
                               int oceanFloor,
                               boolean columnWater,
                               boolean nearWater,
                               double heightShade) {
        if (columnWater) {
            return waterColor(seaLevel, oceanFloor, nearWater);
        }
        String biomePath = biomePath(biome);
        int base = landBaseColor(biomePath, height, seaLevel, nearWater);
        return HeightShader.multiplyRgb(base, heightShade);
    }

    public static int colorFor(Holder<Biome> biome,
                               int height,
                               int seaLevel,
                               int oceanFloor,
                               boolean columnWater,
                               boolean nearWater) {
        return colorFor(biome, height, seaLevel, oceanFloor, columnWater, nearWater, 1.0);
    }

    private static int waterColor(int seaLevel, int oceanFloor, boolean nearWater) {
        int depth = Math.max(0, seaLevel - oceanFloor);
        double t = clamp(depth / 32.0, 0.0, 1.0);
        int shallow = nearWater ? 0xFF4F82CB : 0xFF4676C0;
        int deep = 0xFF244D97;
        return mix(shallow, deep, t);
    }

    private static int landBaseColor(String biomePath, int height, int seaLevel, boolean nearWater) {
        int base;
        if (biomePath.contains("snow") || biomePath.contains("frozen") || biomePath.contains("ice")) {
            base = 0xFFE9EEF2;
        } else if (biomePath.contains("badlands")) {
            base = 0xFFC6784A;
        } else if (biomePath.contains("desert") || biomePath.contains("beach")) {
            base = 0xFFD5C089;
        } else if (biomePath.contains("savanna")) {
            base = 0xFFB9AE64;
        } else if (biomePath.contains("swamp") || biomePath.contains("mangrove")) {
            base = 0xFF516D42;
        } else if (biomePath.contains("jungle")) {
            base = 0xFF3A7643;
        } else if (biomePath.contains("taiga") || biomePath.contains("spruce")) {
            base = 0xFF5B775F;
        } else if (height >= seaLevel + 90) {
            base = 0xFF7B7A76;
        } else if (height >= seaLevel + 60) {
            base = 0xFF749060;
        } else {
            base = 0xFF6B9B52;
        }
        if (nearWater) {
            base = mix(base, 0xFFB0A06C, 0.14);
        }
        return base;
    }

    private static String biomePath(Holder<Biome> biome) {
        if (biome == null) return "";
        return biome.unwrapKey().map(key -> key.location().getPath()).orElse("");
    }

    private static int mix(int from, int to, double t) {
        double clamped = clamp(t, 0.0, 1.0);
        int a0 = (from >>> 24) & 0xFF;
        int r0 = (from >>> 16) & 0xFF;
        int g0 = (from >>> 8) & 0xFF;
        int b0 = from & 0xFF;
        int a1 = (to >>> 24) & 0xFF;
        int r1 = (to >>> 16) & 0xFF;
        int g1 = (to >>> 8) & 0xFF;
        int b1 = to & 0xFF;
        int a = HeightShader.clampChannel((int) Math.round(a0 + (a1 - a0) * clamped));
        int r = HeightShader.clampChannel((int) Math.round(r0 + (r1 - r0) * clamped));
        int g = HeightShader.clampChannel((int) Math.round(g0 + (g1 - g0) * clamped));
        int b = HeightShader.clampChannel((int) Math.round(b0 + (b1 - b0) * clamped));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}