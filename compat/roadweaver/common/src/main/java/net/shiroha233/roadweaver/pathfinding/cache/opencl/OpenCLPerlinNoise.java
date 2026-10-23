package net.shiroha233.roadweaver.pathfinding.cache.opencl;

import java.util.List;

/**
 * OpenCL 侧 PerlinNoise 描述。
 */
public record OpenCLPerlinNoise(
        int firstOctave,
        double lowestFreqInputFactor,
        double lowestFreqValueFactor,
        double maxValue,
        List<Double> amplitudes,
        List<Integer> improvedNoiseIndices
) {
    public OpenCLPerlinNoise {
        amplitudes = List.copyOf(amplitudes == null ? List.of() : amplitudes);
        improvedNoiseIndices = List.copyOf(improvedNoiseIndices == null ? List.of() : improvedNoiseIndices);
    }
}