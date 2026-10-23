/* 文件职责：在 OpenCL 设备上执行粗高度与 NoiseChunk 精确高度采样。 */
#pragma OPENCL EXTENSION cl_khr_fp64 : enable
#pragma OPENCL FP_CONTRACT OFF

#define NODE_CONSTANT 0
#define NODE_ADD 1
#define NODE_MUL 2
#define NODE_MIN 3
#define NODE_MAX 4
#define NODE_CLAMP 5
#define NODE_ABS 6
#define NODE_SQUARE 7
#define NODE_CUBE 8
#define NODE_HALF_NEGATIVE 9
#define NODE_QUARTER_NEGATIVE 10
#define NODE_SQUEEZE 11
#define NODE_INVERT 12
#define NODE_Y_CLAMPED_GRADIENT 13
#define NODE_RANGE_CHOICE 14
#define NODE_NOISE 15
#define NODE_SHIFTED_NOISE 16
#define NODE_SHIFT_A 17
#define NODE_SHIFT_B 18
#define NODE_SHIFT 19
#define NODE_SPLINE 20
#define NODE_WEIRD_SCALED_SAMPLER 21
#define NODE_CLAMP_TO_NEAREST_UNIT 22
#define NODE_MARKER 23
#define NODE_INTERPOLATED 24
#define NODE_BLENDED_NOISE 25
#define NODE_END_ISLAND 26

#define PARAM_SAMPLE_COUNT 0
#define PARAM_MIN_Y 1
#define PARAM_MAX_Y 2
#define PARAM_CELL_HEIGHT 3
#define PARAM_MIN_X 4
#define PARAM_MIN_Z 5
#define PARAM_STEP 6
#define PARAM_SAMPLE_WIDTH 7
#define PARAM_ROOT_NODE 8
#define PARAM_NODE_COUNT 9

#define ACC_CHUNK_COUNT 0
#define ACC_MIN_Y 1
#define ACC_HEIGHT 2
#define ACC_CELL_WIDTH 3
#define ACC_CELL_HEIGHT 4
#define ACC_CELL_COUNT_XZ 5
#define ACC_CELL_COUNT_Y 6
#define ACC_MIN_CELL_Y 7
#define ACC_NODE_COUNT 8
#define ACC_INTERPOLATOR_COUNT 9
#define ACC_LATTICE_POINTS 10
#define ACC_AQUIFER_GRID_Y_SIZE 11
#define ACC_AQUIFER_POINTS 12
#define ACC_AQUIFER_MIN_GRID_Y 13
#define ACC_AQUIFERS_ENABLED 14
#define ACC_SEA_LEVEL 15
#define ACC_LAVA_LEVEL 16
#define ACC_LAVA_THRESHOLD 17
#define ACC_WAY_BELOW_MIN_Y 18
#define ACC_DEFAULT_FLUID_KIND 19
#define ACC_DEFAULT_BLOCK_MASK 20
#define ACC_DEFAULT_FLUID_MASK 21
#define ACC_LAVA_MASK 22
#define ACC_ROOT_FINAL_DENSITY 23
#define ACC_ROOT_INITIAL_DENSITY 24
#define ACC_ROOT_BARRIER 25
#define ACC_ROOT_FLOODEDNESS 26
#define ACC_ROOT_SPREAD 27
#define ACC_ROOT_LAVA 28
#define ACC_ROOT_EROSION 29
#define ACC_ROOT_DEPTH 30
#define ACC_AQUIFER_UNIQUE_POINTS 31
#define ACC_PRELIMINARY_POINT_COUNT 32
#define ACC_SPARSE_LATTICE 33
#define ACC_HEIGHT_PARALLEL_LANES 64

#define USAGE_FINAL_DENSITY 1
#define USAGE_INITIAL_DENSITY 2
#define USAGE_BARRIER 4
#define USAGE_FLOODEDNESS 8
#define USAGE_SPREAD 16
#define USAGE_LAVA 32
#define USAGE_EROSION 64
#define USAGE_DEPTH 128
#define USAGE_INTERPOLATED_LATTICE 256

#define MATERIAL_WORLD_SURFACE 1
#define MATERIAL_OCEAN_FLOOR 2
#define MATERIAL_MOTION_BLOCKING 4

#define FLUID_AIR 0
#define FLUID_WATER 1
#define FLUID_LAVA 2
#define FLUID_GENERIC 3

__constant int AQUIFER_SURFACE_OFFSETS[26] = {
     0,  0,
    -2, -1,
    -1, -1,
     0, -1,
     1, -1,
    -3,  0,
    -2,  0,
    -1,  0,
     1,  0,
    -2,  1,
    -1,  1,
     0,  1,
     1,  1
};

static int floor_int(double value) {
    int i = (int)value;
    return value < (double)i ? i - 1 : i;
}

static double lerp(double delta, double start, double end) {
    return start + delta * (end - start);
}

static double lerp2(double dx, double dy, double v00, double v10, double v01, double v11) {
    return lerp(dy, lerp(dx, v00, v10), lerp(dx, v01, v11));
}

static double lerp3(double dx, double dy, double dz,
                    double v000, double v100, double v010, double v110,
                    double v001, double v101, double v011, double v111) {
    return lerp(dz, lerp2(dx, dy, v000, v100, v010, v110), lerp2(dx, dy, v001, v101, v011, v111));
}

static double smoothstep(double value) {
    return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
}

static double clamp_double(double value, double min_value, double max_value) {
    return value < min_value ? min_value : fmin(value, max_value);
}

static double clamped_lerp(double start, double end, double delta) {
    if (delta < 0.0) {
        return start;
    }
    if (delta > 1.0) {
        return end;
    }
    return lerp(delta, start, end);
}

static double clamped_map(double value, double old_min, double old_max, double new_min, double new_max) {
    return clamped_lerp(new_min, new_max, (value - old_min) / (old_max - old_min));
}

static double perlin_wrap(double value) {
    return value - floor(value / 33554432.0 + 0.5) * 33554432.0;
}

static int permutation(__global const int* permutations, int noise_index, int value) {
    return permutations[noise_index * 256 + (value & 255)] & 255;
}

static double grad_dot(int gradient, double x, double y, double z) {
    switch (gradient & 15) {
        case 0: return x + y;
        case 1: return -x + y;
        case 2: return x - y;
        case 3: return -x - y;
        case 4: return x + z;
        case 5: return -x + z;
        case 6: return x - z;
        case 7: return -x - z;
        case 8: return y + z;
        case 9: return -y + z;
        case 10: return y - z;
        case 11: return -y - z;
        case 12: return x + y;
        case 13: return -y + z;
        case 14: return -x + y;
        default: return -y - z;
    }
}

static double improved_noise(__global const double* improved_values,
                             __global const int* permutations,
                             int noise_index,
                             double x,
                             double y,
                             double z,
                             double y_scale,
                             double y_max) {
    double shifted_x = x + improved_values[noise_index * 3];
    double shifted_y = y + improved_values[noise_index * 3 + 1];
    double shifted_z = z + improved_values[noise_index * 3 + 2];
    int floor_x = floor_int(shifted_x);
    int floor_y = floor_int(shifted_y);
    int floor_z = floor_int(shifted_z);
    double local_x = shifted_x - (double)floor_x;
    double local_y = shifted_y - (double)floor_y;
    double local_z = shifted_z - (double)floor_z;
    double y_offset = 0.0;
    if (y_scale != 0.0) {
        double y_limit = y_max >= 0.0 && y_max < local_y ? y_max : local_y;
        y_offset = floor(y_limit / y_scale + (double)1.0e-7f) * y_scale;
    }

    int i = permutation(permutations, noise_index, floor_x);
    int j = permutation(permutations, noise_index, floor_x + 1);
    int k = permutation(permutations, noise_index, i + floor_y);
    int l = permutation(permutations, noise_index, i + floor_y + 1);
    int i1 = permutation(permutations, noise_index, j + floor_y);
    int j1 = permutation(permutations, noise_index, j + floor_y + 1);
    double y_sample = local_y - y_offset;

    double d0 = grad_dot(permutation(permutations, noise_index, k + floor_z), local_x, y_sample, local_z);
    double d1 = grad_dot(permutation(permutations, noise_index, i1 + floor_z), local_x - 1.0, y_sample, local_z);
    double d2 = grad_dot(permutation(permutations, noise_index, l + floor_z), local_x, y_sample - 1.0, local_z);
    double d3 = grad_dot(permutation(permutations, noise_index, j1 + floor_z), local_x - 1.0, y_sample - 1.0, local_z);
    double d4 = grad_dot(permutation(permutations, noise_index, k + floor_z + 1), local_x, y_sample, local_z - 1.0);
    double d5 = grad_dot(permutation(permutations, noise_index, i1 + floor_z + 1), local_x - 1.0, y_sample, local_z - 1.0);
    double d6 = grad_dot(permutation(permutations, noise_index, l + floor_z + 1), local_x, y_sample - 1.0, local_z - 1.0);
    double d7 = grad_dot(permutation(permutations, noise_index, j1 + floor_z + 1), local_x - 1.0, y_sample - 1.0, local_z - 1.0);

    return lerp3(smoothstep(local_x), smoothstep(local_y), smoothstep(local_z), d0, d1, d2, d3, d4, d5, d6, d7);
}

static double perlin_noise(__global const int* perlin_ints,
                           __global const double* perlin_values,
                           __global const double* amplitudes,
                           __global const int* improved_indices,
                           __global const double* improved_values,
                           __global const int* permutations,
                           int noise_index,
                           double x,
                           double y,
                           double z) {
    if (noise_index < 0) {
        return 0.0;
    }
    int int_base = noise_index * 4;
    int octave_count = perlin_ints[int_base + 1];
    int amplitude_offset = perlin_ints[int_base + 2];
    int improved_offset = perlin_ints[int_base + 3];
    int value_base = noise_index * 3;
    double input_factor = perlin_values[value_base];
    double value_factor = perlin_values[value_base + 1];
    double result = 0.0;

    for (int i = 0; i < octave_count; i++) {
        int improved_index = improved_indices[improved_offset + i];
        if (improved_index >= 0) {
            double noise = improved_noise(
                    improved_values,
                    permutations,
                    improved_index,
                    perlin_wrap(x * input_factor),
                    perlin_wrap(y * input_factor),
                    perlin_wrap(z * input_factor),
                    0.0,
                    0.0);
            result += amplitudes[amplitude_offset + i] * noise * value_factor;
        }
        input_factor *= 2.0;
        value_factor /= 2.0;
    }
    return result;
}

static double normal_noise(__global const int* normal_ints,
                           __global const double* normal_values,
                           __global const int* perlin_ints,
                           __global const double* perlin_values,
                           __global const double* amplitudes,
                           __global const int* improved_indices,
                           __global const double* improved_values,
                           __global const int* permutations,
                           int noise_index,
                           double x,
                           double y,
                           double z) {
    if (noise_index < 0) {
        return 0.0;
    }
    int int_base = noise_index * 2;
    int first = normal_ints[int_base];
    int second = normal_ints[int_base + 1];
    double value_factor = normal_values[noise_index * 2];
    double input_factor = 1.0181268882175227;
    return (perlin_noise(perlin_ints, perlin_values, amplitudes, improved_indices, improved_values, permutations, first, x, y, z)
            + perlin_noise(perlin_ints, perlin_values, amplitudes, improved_indices, improved_values, permutations, second, x * input_factor, y * input_factor, z * input_factor)) * value_factor;
}

static int perlin_octave_noise(__global const int* perlin_ints,
                               __global const int* improved_indices,
                               int perlin_index,
                               int octave) {
    if (perlin_index < 0) {
        return -1;
    }
    int int_base = perlin_index * 4;
    int octave_count = perlin_ints[int_base + 1];
    if (octave < 0 || octave >= octave_count) {
        return -1;
    }
    return improved_indices[perlin_ints[int_base + 3] + (octave_count - 1 - octave)];
}

static double blended_noise(__global const int* perlin_ints,
                            __global const int* improved_indices,
                            __global const double* improved_values,
                            __global const int* permutations,
                            int min_limit_noise,
                            int max_limit_noise,
                            int main_noise,
                            double xz_multiplier,
                            double y_multiplier,
                            double xz_factor,
                            double y_factor,
                            double smear_scale_multiplier,
                            int x,
                            int y,
                            int z) {
    double scaled_x = (double)x * xz_multiplier;
    double scaled_y = (double)y * y_multiplier;
    double scaled_z = (double)z * xz_multiplier;
    double main_x = scaled_x / xz_factor;
    double main_y = scaled_y / y_factor;
    double main_z = scaled_z / xz_factor;
    double smear = y_multiplier * smear_scale_multiplier;
    double main_smear = smear / y_factor;
    double min_value = 0.0;
    double max_value = 0.0;
    double main_value = 0.0;
    double octave_scale = 1.0;

    for (int octave = 0; octave < 8; octave++) {
        int noise_index = perlin_octave_noise(perlin_ints, improved_indices, main_noise, octave);
        if (noise_index >= 0) {
            main_value += improved_noise(
                    improved_values,
                    permutations,
                    noise_index,
                    perlin_wrap(main_x * octave_scale),
                    perlin_wrap(main_y * octave_scale),
                    perlin_wrap(main_z * octave_scale),
                    main_smear * octave_scale,
                    main_y * octave_scale) / octave_scale;
        }
        octave_scale /= 2.0;
    }

    double blend = (main_value / 10.0 + 1.0) / 2.0;
    int skip_min = blend >= 1.0;
    int skip_max = blend <= 0.0;
    octave_scale = 1.0;
    for (int octave = 0; octave < 16; octave++) {
        double sample_x = perlin_wrap(scaled_x * octave_scale);
        double sample_y = perlin_wrap(scaled_y * octave_scale);
        double sample_z = perlin_wrap(scaled_z * octave_scale);
        double y_scale = smear * octave_scale;
        if (!skip_min) {
            int noise_index = perlin_octave_noise(perlin_ints, improved_indices, min_limit_noise, octave);
            if (noise_index >= 0) {
                min_value += improved_noise(
                        improved_values, permutations, noise_index,
                        sample_x, sample_y, sample_z, y_scale, scaled_y * octave_scale) / octave_scale;
            }
        }
        if (!skip_max) {
            int noise_index = perlin_octave_noise(perlin_ints, improved_indices, max_limit_noise, octave);
            if (noise_index >= 0) {
                max_value += improved_noise(
                        improved_values, permutations, noise_index,
                        sample_x, sample_y, sample_z, y_scale, scaled_y * octave_scale) / octave_scale;
            }
        }
        octave_scale /= 2.0;
    }
    return clamped_lerp(min_value / 512.0, max_value / 512.0, blend) / 128.0;
}

static double simplex_corner_2d(int gradient, double x, double y) {
    double attenuation = 0.5 - x * x - y * y;
    if (attenuation < 0.0) {
        return 0.0;
    }
    attenuation *= attenuation;
    return attenuation * attenuation * grad_dot(gradient, x, y, 0.0);
}

static double simplex_noise_2d(__global const int* permutations,
                               int noise_index,
                               double x,
                               double y) {
    const double f2 = 0.3660254037844386;
    const double g2 = 0.21132486540518713;
    double skew = (x + y) * f2;
    int cell_x = floor_int(x + skew);
    int cell_y = floor_int(y + skew);
    double unskew = (double)(cell_x + cell_y) * g2;
    double local_x = x - ((double)cell_x - unskew);
    double local_y = y - ((double)cell_y - unskew);
    int offset_x = local_x > local_y ? 1 : 0;
    int offset_y = local_x > local_y ? 0 : 1;
    double middle_x = local_x - (double)offset_x + g2;
    double middle_y = local_y - (double)offset_y + g2;
    double last_x = local_x - 1.0 + 2.0 * g2;
    double last_y = local_y - 1.0 + 2.0 * g2;
    int first = permutation(permutations, noise_index,
            cell_x + permutation(permutations, noise_index, cell_y)) % 12;
    int middle = permutation(permutations, noise_index,
            cell_x + offset_x + permutation(permutations, noise_index, cell_y + offset_y)) % 12;
    int last = permutation(permutations, noise_index,
            cell_x + 1 + permutation(permutations, noise_index, cell_y + 1)) % 12;
    return 70.0 * (
            simplex_corner_2d(first, local_x, local_y)
            + simplex_corner_2d(middle, middle_x, middle_y)
            + simplex_corner_2d(last, last_x, last_y));
}

static double end_island_density(__global const int* permutations,
                                 int noise_index,
                                 int block_x,
                                 int block_z) {
    int island_x = (block_x / 8);
    int island_z = (block_z / 8);
    int half_x = island_x / 2;
    int half_z = island_z / 2;
    int remainder_x = island_x % 2;
    int remainder_z = island_z % 2;
    int radial_bits = as_int((uint)island_x * (uint)island_x + (uint)island_z * (uint)island_z);
    float height = 100.0f - sqrt((float)radial_bits) * 8.0f;
    height = clamp(height, -100.0f, 80.0f);

    for (int offset_x = -12; offset_x <= 12; offset_x++) {
        for (int offset_z = -12; offset_z <= 12; offset_z++) {
            long sample_x = (long)(half_x + offset_x);
            long sample_z = (long)(half_z + offset_z);
            if (sample_x * sample_x + sample_z * sample_z > 4096L
                    && simplex_noise_2d(permutations, noise_index, (double)sample_x, (double)sample_z) < -0.9f) {
                float scale = fmod(fabs((float)sample_x) * 3439.0f + fabs((float)sample_z) * 147.0f, 13.0f) + 9.0f;
                float local_x = (float)(remainder_x - offset_x * 2);
                float local_z = (float)(remainder_z - offset_z * 2);
                float candidate = 100.0f - sqrt(local_x * local_x + local_z * local_z) * scale;
                candidate = clamp(candidate, -100.0f, 80.0f);
                height = fmax(height, candidate);
            }
        }
    }
    return ((double)height - 8.0) / 128.0;
}

static double read_value(__global double* scratch, int base, int node) {
    return node < 0 ? 0.0 : scratch[base + node];
}

static double spaghetti_rarity_3d(double value) {
    if (value < -0.5) {
        return 0.75;
    }
    if (value < 0.0) {
        return 1.0;
    }
    return value < 0.5 ? 1.5 : 2.0;
}

static double spaghetti_rarity_2d(double value) {
    if (value < -0.75) {
        return 0.5;
    }
    if (value < -0.5) {
        return 0.75;
    }
    if (value < 0.5) {
        return 1.0;
    }
    return value < 0.75 ? 2.0 : 3.0;
}

static double weird_scaled_rarity(int mapper_type, double value) {
    return mapper_type == 2 ? spaghetti_rarity_2d(value) : spaghetti_rarity_3d(value);
}

static double clamp_to_nearest_unit(double value, int resolution) {
    int scaled = (int)(value * (double)resolution);
    float clamped = (float)(scaled + 1);
    return (double)(clamped / (float)resolution);
}

static double spline_value(__global const int* spline_ints,
                           __global const double* spline_locations,
                           __global const int* spline_value_nodes,
                           __global const double* spline_derivatives,
                           __global double* scratch,
                           int scratch_base,
                           int spline_index) {
    int int_base = spline_index * 3;
    int coordinate_node = spline_ints[int_base];
    int point_offset = spline_ints[int_base + 1];
    int point_count = spline_ints[int_base + 2];
    float coordinate = (float)read_value(scratch, scratch_base, coordinate_node);
    int last = point_count - 1;

    if (coordinate < (float)spline_locations[point_offset]) {
        float derivative = (float)spline_derivatives[point_offset];
        float value = (float)read_value(scratch, scratch_base, spline_value_nodes[point_offset]);
        float location = (float)spline_locations[point_offset];
        return (double)(derivative == 0.0f ? value : value + derivative * (coordinate - location));
    }

    if (coordinate >= (float)spline_locations[point_offset + last]) {
        float derivative = (float)spline_derivatives[point_offset + last];
        float value = (float)read_value(scratch, scratch_base, spline_value_nodes[point_offset + last]);
        float location = (float)spline_locations[point_offset + last];
        return (double)(derivative == 0.0f ? value : value + derivative * (coordinate - location));
    }

    int interval = 0;
    for (int i = 0; i < last; i++) {
        float next_location = (float)spline_locations[point_offset + i + 1];
        if (coordinate < next_location) {
            interval = i;
            break;
        }
    }

    float location0 = (float)spline_locations[point_offset + interval];
    float location1 = (float)spline_locations[point_offset + interval + 1];
    float delta = (coordinate - location0) / (location1 - location0);
    float value0 = (float)read_value(scratch, scratch_base, spline_value_nodes[point_offset + interval]);
    float value1 = (float)read_value(scratch, scratch_base, spline_value_nodes[point_offset + interval + 1]);
    float derivative0 = (float)spline_derivatives[point_offset + interval];
    float derivative1 = (float)spline_derivatives[point_offset + interval + 1];
    float distance = location1 - location0;
    float f8 = derivative0 * distance - (value1 - value0);
    float f9 = -derivative1 * distance + (value1 - value0);
    float result = value0 + delta * (value1 - value0) + delta * (1.0f - delta) * (f8 + delta * (f9 - f8));
    return (double)result;
}

static double evaluate_node(__global const int* node_ints,
                            __global const double* node_values,
                            __global const int* normal_ints,
                            __global const double* normal_values,
                            __global const int* perlin_ints,
                            __global const double* perlin_values,
                            __global const double* amplitudes,
                            __global const int* improved_indices,
                            __global const double* improved_values,
                            __global const int* permutations,
                            __global const int* spline_ints,
                            __global const double* spline_locations,
                            __global const int* spline_value_nodes,
                            __global const double* spline_derivatives,
                            __global double* scratch,
                            int scratch_base,
                            int node,
                            int x,
                            int y,
                            int z) {
    int int_base = node * 6;
    int type = node_ints[int_base];
    int left = node_ints[int_base + 1];
    int right = node_ints[int_base + 2];
    int extra_a = node_ints[int_base + 3];
    int extra_b = node_ints[int_base + 4];
    int value_base = node * 4;
    double value_a = node_values[value_base];
    double value_b = node_values[value_base + 1];
    double lhs = type == NODE_BLENDED_NOISE ? 0.0 : read_value(scratch, scratch_base, left);
    double rhs = type == NODE_BLENDED_NOISE ? 0.0 : read_value(scratch, scratch_base, right);

    switch (type) {
        case NODE_CONSTANT:
            return value_a;
        case NODE_ADD:
            return lhs + rhs;
        case NODE_MUL:
            return lhs == 0.0 ? 0.0 : lhs * rhs;
        case NODE_MIN:
            return fmin(lhs, rhs);
        case NODE_MAX:
            return fmax(lhs, rhs);
        case NODE_CLAMP:
            return clamp_double(lhs, value_a, value_b);
        case NODE_ABS:
            return fabs(lhs);
        case NODE_SQUARE:
            return lhs * lhs;
        case NODE_CUBE:
            return lhs * lhs * lhs;
        case NODE_HALF_NEGATIVE:
            return lhs > 0.0 ? lhs : lhs * 0.5;
        case NODE_QUARTER_NEGATIVE:
            return lhs > 0.0 ? lhs : lhs * 0.25;
        case NODE_SQUEEZE: {
            double clamped = clamp_double(lhs, -1.0, 1.0);
            return clamped / 2.0 - clamped * clamped * clamped / 24.0;
        }
        case NODE_INVERT:
            return 1.0 / lhs;
        case NODE_Y_CLAMPED_GRADIENT:
            return clamped_map((double)y, (double)extra_a, (double)extra_b, value_a, value_b);
        case NODE_RANGE_CHOICE:
            return lhs >= value_a && lhs < value_b ? rhs : read_value(scratch, scratch_base, extra_a);
        case NODE_NOISE:
            return normal_noise(normal_ints, normal_values, perlin_ints, perlin_values, amplitudes, improved_indices,
                    improved_values, permutations, extra_a, (double)x * value_a, (double)y * value_b, (double)z * value_a);
        case NODE_SHIFTED_NOISE:
            return normal_noise(normal_ints, normal_values, perlin_ints, perlin_values, amplitudes, improved_indices,
                    improved_values, permutations, extra_b,
                    (double)x * value_a + lhs,
                    (double)y * value_b + rhs,
                    (double)z * value_a + read_value(scratch, scratch_base, extra_a));
        case NODE_SHIFT_A:
            return normal_noise(normal_ints, normal_values, perlin_ints, perlin_values, amplitudes, improved_indices,
                    improved_values, permutations, extra_a, (double)x * 0.25, 0.0, (double)z * 0.25) * 4.0;
        case NODE_SHIFT_B:
            return normal_noise(normal_ints, normal_values, perlin_ints, perlin_values, amplitudes, improved_indices,
                    improved_values, permutations, extra_a, (double)z * 0.25, (double)x * 0.25, 0.0) * 4.0;
        case NODE_SHIFT:
            return normal_noise(normal_ints, normal_values, perlin_ints, perlin_values, amplitudes, improved_indices,
                    improved_values, permutations, extra_a, (double)x * 0.25, (double)y * 0.25, (double)z * 0.25) * 4.0;
        case NODE_SPLINE:
            return spline_value(spline_ints, spline_locations, spline_value_nodes, spline_derivatives, scratch, scratch_base, extra_a);
        case NODE_WEIRD_SCALED_SAMPLER: {
            double rarity = weird_scaled_rarity(extra_b, lhs);
            return rarity * fabs(normal_noise(normal_ints, normal_values, perlin_ints, perlin_values, amplitudes, improved_indices,
                    improved_values, permutations, extra_a, (double)x / rarity, (double)y / rarity, (double)z / rarity));
        }
        case NODE_CLAMP_TO_NEAREST_UNIT:
            return clamp_to_nearest_unit(lhs, extra_a);
        case NODE_BLENDED_NOISE:
            return blended_noise(
                    perlin_ints,
                    improved_indices,
                    improved_values,
                    permutations,
                    left,
                    right,
                    extra_a,
                    value_a,
                    value_b,
                    node_values[value_base + 2],
                    node_values[value_base + 3],
                    read_value(scratch, scratch_base, extra_b),
                    x,
                    y,
                    z);
        case NODE_END_ISLAND:
            return end_island_density(permutations, extra_a, x, z);
        case NODE_MARKER:
        case NODE_INTERPOLATED:
            return lhs;
        default:
            return 0.0;
    }
}

static void evaluate_graph_nodes(__global const int* node_ints,
                                 __global const double* node_values,
                                 __global const int* normal_ints,
                                 __global const double* normal_values,
                                 __global const int* perlin_ints,
                                 __global const double* perlin_values,
                                 __global const double* amplitudes,
                                 __global const int* improved_indices,
                                 __global const double* improved_values,
                                 __global const int* permutations,
                                 __global const int* spline_ints,
                                 __global const double* spline_locations,
                                 __global const int* spline_value_nodes,
                                 __global const double* spline_derivatives,
                                 __global double* scratch,
                                 int scratch_base,
                                 int x,
                                 int y,
                                 int z,
                                 int node_count,
                                 int required_usage) {
    for (int node = 0; node < node_count; node++) {
        int usage = node_ints[node * 6 + 5];
        if (required_usage != 0 && (usage & required_usage) == 0) {
            continue;
        }
        scratch[scratch_base + node] = evaluate_node(
                node_ints, node_values, normal_ints, normal_values, perlin_ints, perlin_values,
                amplitudes, improved_indices, improved_values, permutations, spline_ints,
                spline_locations, spline_value_nodes, spline_derivatives, scratch, scratch_base,
                node, x, y, z);
    }
}

static void evaluate_graph(__global const int* params,
                           __global const int* node_ints,
                           __global const double* node_values,
                           __global const int* normal_ints,
                           __global const double* normal_values,
                           __global const int* perlin_ints,
                           __global const double* perlin_values,
                           __global const double* amplitudes,
                           __global const int* improved_indices,
                           __global const double* improved_values,
                           __global const int* permutations,
                           __global const int* spline_ints,
                           __global const double* spline_locations,
                           __global const int* spline_value_nodes,
                           __global const double* spline_derivatives,
                           __global double* scratch,
                           int scratch_base,
                           int x,
                           int y,
                           int z) {
    evaluate_graph_nodes(node_ints, node_values, normal_ints, normal_values, perlin_ints, perlin_values,
            amplitudes, improved_indices, improved_values, permutations, spline_ints, spline_locations,
            spline_value_nodes, spline_derivatives, scratch, scratch_base, x, y, z,
            params[PARAM_NODE_COUNT], 0);
}

static int align_to_cpu_grid(int value) {
    int quotient = value / 4;
    if (value < 0 && value % 4 != 0) {
        quotient -= 1;
    }
    return quotient * 4;
}

__kernel void roadweaver_coarse_height_sample(
        __global const int* params,
        __global const int* node_ints,
        __global const double* node_values,
        __global const int* normal_ints,
        __global const double* normal_values,
        __global const int* perlin_ints,
        __global const double* perlin_values,
        __global const double* amplitudes,
        __global const int* improved_indices,
        __global const double* improved_values,
        __global const int* permutations,
        __global const int* spline_ints,
        __global const double* spline_locations,
        __global const int* spline_value_nodes,
        __global const double* spline_derivatives,
        __global double* scratch,
        __global int* heights
) {
    int index = get_global_id(0);
    int sample_count = params[PARAM_SAMPLE_COUNT];
    if (index >= sample_count) {
        return;
    }

    int sample_width = params[PARAM_SAMPLE_WIDTH];
    int sample_x = index % sample_width;
    int sample_z = index / sample_width;
    int x = align_to_cpu_grid(params[PARAM_MIN_X] + sample_x * params[PARAM_STEP]);
    int z = align_to_cpu_grid(params[PARAM_MIN_Z] + sample_z * params[PARAM_STEP]);
    int min_y = params[PARAM_MIN_Y];
    int max_y = params[PARAM_MAX_Y];
    int cell_height = params[PARAM_CELL_HEIGHT];
    int node_count = params[PARAM_NODE_COUNT];
    int root_node = params[PARAM_ROOT_NODE];
    int scratch_base = index * node_count;

    int result = min_y;
    for (int y = max_y; y >= min_y; y -= cell_height) {
        evaluate_graph(params, node_ints, node_values, normal_ints, normal_values, perlin_ints, perlin_values,
                amplitudes, improved_indices, improved_values, permutations, spline_ints, spline_locations,
                spline_value_nodes, spline_derivatives, scratch, scratch_base, x, y, z);
        double density = scratch[scratch_base + root_node];
        if (density > 0.390625) {
            result = y;
            break;
        }
    }
    heights[index] = result;
}

static int floor_div_int(int value, int divisor) {
    int quotient = value / divisor;
    int remainder = value % divisor;
    return remainder < 0 ? quotient - 1 : quotient;
}

static int lattice_point_index(__global const int* params, int cell_x, int cell_y, int cell_z) {
    int lattice_xz = params[ACC_CELL_COUNT_XZ] + 1;
    int lattice_y = params[ACC_CELL_COUNT_Y] + 1;
    return (cell_x * lattice_xz + cell_z) * lattice_y + cell_y;
}

static double lattice_value(__global const int* params,
                            __global const double* lattice,
                            int chunk_index,
                            int slot,
                            int point) {
    int index = (chunk_index * params[ACC_INTERPOLATOR_COUNT] + slot) * params[ACC_LATTICE_POINTS] + point;
    return lattice[index];
}

static double interpolated_value(__global const int* params,
                                 __global const int* chunks,
                                 __global const double* lattice,
                                 int chunk_index,
                                 int slot,
                                 int x,
                                 int y,
                                 int z) {
    int chunk_x = chunks[chunk_index * 2];
    int chunk_z = chunks[chunk_index * 2 + 1];
    int local_x = x - chunk_x * 16;
    int local_z = z - chunk_z * 16;
    int cell_width = params[ACC_CELL_WIDTH];
    int cell_height = params[ACC_CELL_HEIGHT];
    int cell_x = local_x / cell_width;
    int cell_z = local_z / cell_width;
    int absolute_cell_y = floor_div_int(y, cell_height);
    int cell_y = absolute_cell_y - params[ACC_MIN_CELL_Y];
    int in_cell_x = local_x - cell_x * cell_width;
    int in_cell_z = local_z - cell_z * cell_width;
    int in_cell_y = y - absolute_cell_y * cell_height;
    double delta_x = (double)in_cell_x / (double)cell_width;
    double delta_y = (double)in_cell_y / (double)cell_height;
    double delta_z = (double)in_cell_z / (double)cell_width;

    int p000 = lattice_point_index(params, cell_x, cell_y, cell_z);
    int p100 = lattice_point_index(params, cell_x + 1, cell_y, cell_z);
    int p010 = lattice_point_index(params, cell_x, cell_y + 1, cell_z);
    int p110 = lattice_point_index(params, cell_x + 1, cell_y + 1, cell_z);
    int p001 = lattice_point_index(params, cell_x, cell_y, cell_z + 1);
    int p101 = lattice_point_index(params, cell_x + 1, cell_y, cell_z + 1);
    int p011 = lattice_point_index(params, cell_x, cell_y + 1, cell_z + 1);
    int p111 = lattice_point_index(params, cell_x + 1, cell_y + 1, cell_z + 1);
    return lerp3(
            delta_x,
            delta_y,
            delta_z,
            lattice_value(params, lattice, chunk_index, slot, p000),
            lattice_value(params, lattice, chunk_index, slot, p100),
            lattice_value(params, lattice, chunk_index, slot, p010),
            lattice_value(params, lattice, chunk_index, slot, p110),
            lattice_value(params, lattice, chunk_index, slot, p001),
            lattice_value(params, lattice, chunk_index, slot, p101),
            lattice_value(params, lattice, chunk_index, slot, p011),
            lattice_value(params, lattice, chunk_index, slot, p111));
}

static void evaluate_graph_interpolated(__global const int* params,
                                        __global const int* chunks,
                                        __global const int* node_ints,
                                        __global const double* node_values,
                                        __global const int* normal_ints,
                                        __global const double* normal_values,
                                        __global const int* perlin_ints,
                                        __global const double* perlin_values,
                                        __global const double* amplitudes,
                                        __global const int* improved_indices,
                                        __global const double* improved_values,
                                        __global const int* permutations,
                                        __global const int* spline_ints,
                                        __global const double* spline_locations,
                                        __global const int* spline_value_nodes,
                                        __global const double* spline_derivatives,
                                        __global const double* lattice,
                                        __global double* scratch,
                                        int scratch_base,
                                        int chunk_index,
                                        int x,
                                        int y,
                                        int z) {
    int node_count = params[ACC_NODE_COUNT];
    for (int node = 0; node < node_count; node++) {
        int usage = node_ints[node * 6 + 5];
        if ((usage & (USAGE_FINAL_DENSITY | USAGE_BARRIER)) == 0) {
            continue;
        }
        int int_base = node * 6;
        int type = node_ints[int_base];
        double result;
        if (type == NODE_INTERPOLATED) {
            result = interpolated_value(params, chunks, lattice, chunk_index, node_ints[int_base + 3], x, y, z);
        } else {
            result = evaluate_node(
                    node_ints, node_values, normal_ints, normal_values, perlin_ints, perlin_values,
                    amplitudes, improved_indices, improved_values, permutations, spline_ints,
                    spline_locations, spline_value_nodes, spline_derivatives, scratch, scratch_base,
                    node, x, y, z);
        }
        scratch[scratch_base + node] = result;
    }
}

static int global_status_level(__global const int* params, int y) {
    return y < params[ACC_LAVA_THRESHOLD] ? params[ACC_LAVA_LEVEL] : params[ACC_SEA_LEVEL];
}

static int global_status_kind(__global const int* params, int y) {
    return y < params[ACC_LAVA_THRESHOLD] ? FLUID_LAVA : params[ACC_DEFAULT_FLUID_KIND];
}

static int status_state_kind(int level, int kind, int y) {
    return y < level ? kind : FLUID_AIR;
}

static int global_state_kind(__global const int* params, int y) {
    return status_state_kind(global_status_level(params, y), global_status_kind(params, y), y);
}

static int material_mask_for_kind(__global const int* params, int kind) {
    if (kind == FLUID_AIR) {
        return 0;
    }
    return kind == FLUID_LAVA ? params[ACC_LAVA_MASK] : params[ACC_DEFAULT_FLUID_MASK];
}

static double map_unclamped(double value,
                            double old_start,
                            double old_end,
                            double new_start,
                            double new_end) {
    return lerp((value - old_start) / (old_end - old_start), new_start, new_end);
}

static void compute_aquifer_status(__global const int* params,
                                   __global const int* node_ints,
                                   __global const double* node_values,
                                   __global const int* normal_ints,
                                   __global const double* normal_values,
                                   __global const int* perlin_ints,
                                   __global const double* perlin_values,
                                   __global const double* amplitudes,
                                   __global const int* improved_indices,
                                   __global const double* improved_values,
                                   __global const int* permutations,
                                   __global const int* spline_ints,
                                   __global const double* spline_locations,
                                   __global const int* spline_value_nodes,
                                   __global const double* spline_derivatives,
                                   __global const int* point_preliminary_indices,
                                   __global const int* preliminary_surfaces,
                                   __global double* scratch,
                                   int scratch_base,
                                   int aquifer_index,
                                   int x,
                                   int y,
                                   int z,
                                   __private int* result_level,
                                   __private int* result_kind) {
    int global_level = global_status_level(params, y);
    int global_kind = global_status_kind(params, y);
    int minimum_surface = 2147483647;
    int upper_y = y + 12;
    int lower_y = y - 12;
    int has_surface_fluid = 0;

    for (int offset_index = 0; offset_index < 13; offset_index++) {
        int offset_x = AQUIFER_SURFACE_OFFSETS[offset_index * 2];
        int offset_z = AQUIFER_SURFACE_OFFSETS[offset_index * 2 + 1];
        int preliminary_index = point_preliminary_indices[aquifer_index * 13 + offset_index];
        int preliminary = preliminary_surfaces[preliminary_index];
        if (preliminary < params[ACC_MIN_Y]) {
            preliminary = 2147483647;
        }
        int surface_with_margin = preliminary == 2147483647 ? -2147483641 : preliminary + 8;
        int center = offset_x == 0 && offset_z == 0;
        if (center && lower_y > surface_with_margin) {
            *result_level = global_level;
            *result_kind = global_kind;
            return;
        }

        int above_surface = upper_y > surface_with_margin;
        if (above_surface || center) {
            int sampled_level = global_status_level(params, surface_with_margin);
            int sampled_kind = global_status_kind(params, surface_with_margin);
            if (status_state_kind(sampled_level, sampled_kind, surface_with_margin) != FLUID_AIR) {
                if (center) {
                    has_surface_fluid = 1;
                }
                if (above_surface) {
                    *result_level = sampled_level;
                    *result_kind = sampled_kind;
                    return;
                }
            }
        }
        minimum_surface = min(minimum_surface, preliminary);
    }

    int node_count = params[ACC_NODE_COUNT];
    evaluate_graph_nodes(node_ints, node_values, normal_ints, normal_values, perlin_ints, perlin_values,
            amplitudes, improved_indices, improved_values, permutations, spline_ints, spline_locations,
            spline_value_nodes, spline_derivatives, scratch, scratch_base, x, y, z, node_count,
            USAGE_FLOODEDNESS | USAGE_EROSION | USAGE_DEPTH);
    double erosion = scratch[scratch_base + params[ACC_ROOT_EROSION]];
    double depth = scratch[scratch_base + params[ACC_ROOT_DEPTH]];
    double lower_threshold;
    double upper_threshold;
    if (erosion < -0.225f && depth > 0.9f) {
        lower_threshold = -1.0;
        upper_threshold = -1.0;
    } else {
        int distance_from_surface = minimum_surface + 8 - y;
        double surface_influence = has_surface_fluid
                ? clamped_map((double)distance_from_surface, 0.0, 64.0, 1.0, 0.0)
                : 0.0;
        double floodedness = clamp_double(scratch[scratch_base + params[ACC_ROOT_FLOODEDNESS]], -1.0, 1.0);
        double upper = map_unclamped(surface_influence, 1.0, 0.0, -0.3, 0.8);
        double lower = map_unclamped(surface_influence, 1.0, 0.0, -0.8, 0.4);
        lower_threshold = floodedness - lower;
        upper_threshold = floodedness - upper;
    }

    int fluid_level;
    if (upper_threshold > 0.0) {
        fluid_level = global_level;
    } else if (lower_threshold > 0.0) {
        int grid_x = floor_div_int(x, 16);
        int grid_y = floor_div_int(y, 40);
        int grid_z = floor_div_int(z, 16);
        evaluate_graph_nodes(node_ints, node_values, normal_ints, normal_values, perlin_ints, perlin_values,
                amplitudes, improved_indices, improved_values, permutations, spline_ints, spline_locations,
                spline_value_nodes, spline_derivatives, scratch, scratch_base, grid_x, grid_y, grid_z,
                node_count, USAGE_SPREAD);
        double spread = scratch[scratch_base + params[ACC_ROOT_SPREAD]] * 10.0;
        int quantized = floor_int(spread / 3.0) * 3;
        fluid_level = min(minimum_surface, grid_y * 40 + 20 + quantized);
    } else {
        fluid_level = params[ACC_WAY_BELOW_MIN_Y];
    }

    int fluid_kind = global_kind;
    if (fluid_level <= -10
            && fluid_level != params[ACC_WAY_BELOW_MIN_Y]
            && global_kind != FLUID_LAVA) {
        int grid_x = floor_div_int(x, 64);
        int grid_y = floor_div_int(y, 40);
        int grid_z = floor_div_int(z, 64);
        evaluate_graph_nodes(node_ints, node_values, normal_ints, normal_values, perlin_ints, perlin_values,
                amplitudes, improved_indices, improved_values, permutations, spline_ints, spline_locations,
                spline_value_nodes, spline_derivatives, scratch, scratch_base, grid_x, grid_y, grid_z,
                node_count, USAGE_LAVA);
        if (fabs(scratch[scratch_base + params[ACC_ROOT_LAVA]]) > 0.3) {
            fluid_kind = FLUID_LAVA;
        }
    }
    *result_level = fluid_level;
    *result_kind = fluid_kind;
}

static int aquifer_point_index(__global const int* params,
                               __global const int* chunks,
                               __global const int* aquifer_point_indices,
                               int chunk_index,
                               int grid_x,
                               int grid_y,
                               int grid_z) {
    int chunk_x = chunks[chunk_index * 2];
    int chunk_z = chunks[chunk_index * 2 + 1];
    int x_index = grid_x - (chunk_x - 1);
    int y_index = grid_y - params[ACC_AQUIFER_MIN_GRID_Y];
    int z_index = grid_z - (chunk_z - 1);
    int point = (y_index * 3 + z_index) * 3 + x_index;
    return aquifer_point_indices[chunk_index * params[ACC_AQUIFER_POINTS] + point];
}

static double aquifer_similarity(int first_distance, int second_distance) {
    int difference = second_distance - first_distance;
    if (difference < 0) {
        difference = -difference;
    }
    return 1.0 - (double)difference / 25.0;
}

static double aquifer_pressure(int y,
                               int first_level,
                               int first_kind,
                               int second_level,
                               int second_kind,
                               double barrier) {
    int first_state = status_state_kind(first_level, first_kind, y);
    int second_state = status_state_kind(second_level, second_kind, y);
    if ((first_state == FLUID_LAVA && second_state == FLUID_WATER)
            || (first_state == FLUID_WATER && second_state == FLUID_LAVA)) {
        return 2.0;
    }
    int level_difference = first_level - second_level;
    if (level_difference < 0) {
        level_difference = -level_difference;
    }
    if (level_difference == 0) {
        return 0.0;
    }

    double midpoint = 0.5 * (double)(first_level + second_level);
    double offset = (double)y + 0.5 - midpoint;
    double half_difference = (double)level_difference / 2.0;
    double remaining = half_difference - fabs(offset);
    double shaped;
    if (offset > 0.0) {
        shaped = remaining > 0.0 ? remaining / 1.5 : remaining / 2.5;
    } else {
        double shifted = 3.0 + remaining;
        shaped = shifted > 0.0 ? shifted / 3.0 : shifted / 10.0;
    }
    double barrier_value = shaped < -2.0 || shaped > 2.0 ? 0.0 : barrier;
    return 2.0 * (barrier_value + shaped);
}

static int aquifer_material_mask(__global const int* params,
                                 __global const int* chunks,
                                 __global const int* aquifer_positions,
                                 __global const int* aquifer_point_indices,
                                 __global const int* aquifer_status,
                                 __global double* scratch,
                                 int scratch_base,
                                 int chunk_index,
                                 int x,
                                 int y,
                                 int z,
                                 double density) {
    if (global_state_kind(params, y) == FLUID_LAVA) {
        return params[ACC_LAVA_MASK];
    }

    int base_grid_x = floor_div_int(x - 5, 16);
    int base_grid_y = floor_div_int(y + 1, 12);
    int base_grid_z = floor_div_int(z - 5, 16);
    int distance0 = 2147483647;
    int distance1 = 2147483647;
    int distance2 = 2147483647;
    int point0 = -1;
    int point1 = -1;
    int point2 = -1;

    for (int x_offset = 0; x_offset <= 1; x_offset++) {
        for (int y_offset = -1; y_offset <= 1; y_offset++) {
            for (int z_offset = 0; z_offset <= 1; z_offset++) {
                int point = aquifer_point_index(
                        params, chunks, aquifer_point_indices, chunk_index,
                        base_grid_x + x_offset,
                        base_grid_y + y_offset,
                        base_grid_z + z_offset);
                int position_base = point * 3;
                int dx = aquifer_positions[position_base] - x;
                int dy = aquifer_positions[position_base + 1] - y;
                int dz = aquifer_positions[position_base + 2] - z;
                int distance = dx * dx + dy * dy + dz * dz;
                if (distance0 >= distance) {
                    point2 = point1;
                    distance2 = distance1;
                    point1 = point0;
                    distance1 = distance0;
                    point0 = point;
                    distance0 = distance;
                } else if (distance1 >= distance) {
                    point2 = point1;
                    distance2 = distance1;
                    point1 = point;
                    distance1 = distance;
                } else if (distance2 >= distance) {
                    point2 = point;
                    distance2 = distance;
                }
            }
        }
    }

    int level0 = aquifer_status[point0 * 2];
    int kind0 = aquifer_status[point0 * 2 + 1];
    int state0 = status_state_kind(level0, kind0, y);
    double similarity01 = aquifer_similarity(distance0, distance1);
    if (similarity01 <= 0.0) {
        return material_mask_for_kind(params, state0);
    }
    if (state0 == FLUID_WATER && global_state_kind(params, y - 1) == FLUID_LAVA) {
        return material_mask_for_kind(params, state0);
    }

    int level1 = aquifer_status[point1 * 2];
    int kind1 = aquifer_status[point1 * 2 + 1];
    double barrier = scratch[scratch_base + params[ACC_ROOT_BARRIER]];
    double pressure01 = similarity01 * aquifer_pressure(y, level0, kind0, level1, kind1, barrier);
    if (density + pressure01 > 0.0) {
        return params[ACC_DEFAULT_BLOCK_MASK];
    }

    int level2 = aquifer_status[point2 * 2];
    int kind2 = aquifer_status[point2 * 2 + 1];
    double similarity02 = aquifer_similarity(distance0, distance2);
    if (similarity02 > 0.0) {
        double pressure02 = similarity01 * similarity02 * aquifer_pressure(y, level0, kind0, level2, kind2, barrier);
        if (density + pressure02 > 0.0) {
            return params[ACC_DEFAULT_BLOCK_MASK];
        }
    }

    double similarity12 = aquifer_similarity(distance1, distance2);
    if (similarity12 > 0.0) {
        double pressure12 = similarity01 * similarity12 * aquifer_pressure(y, level1, kind1, level2, kind2, barrier);
        if (density + pressure12 > 0.0) {
            return params[ACC_DEFAULT_BLOCK_MASK];
        }
    }
    return material_mask_for_kind(params, state0);
}

static int classify_accurate_material(__global const int* params,
                                      __global const int* chunks,
                                      __global const int* aquifer_positions,
                                      __global const int* aquifer_point_indices,
                                      __global const int* aquifer_status,
                                      __global double* scratch,
                                      int scratch_base,
                                      int chunk_index,
                                      int x,
                                      int y,
                                      int z,
                                      double density) {
    if (density > 0.0) {
        return params[ACC_DEFAULT_BLOCK_MASK];
    }
    if (params[ACC_AQUIFERS_ENABLED] == 0) {
        return material_mask_for_kind(params, global_state_kind(params, y));
    }
    return aquifer_material_mask(
            params, chunks, aquifer_positions, aquifer_point_indices, aquifer_status, scratch, scratch_base,
            chunk_index, x, y, z, density);
}

__kernel void roadweaver_accurate_lattice(
        __global const int* params,
        __global const int* chunks,
        __global const int* lattice_references,
        __global const int* node_ints,
        __global const double* node_values,
        __global const int* normal_ints,
        __global const double* normal_values,
        __global const int* perlin_ints,
        __global const double* perlin_values,
        __global const double* amplitudes,
        __global const int* improved_indices,
        __global const double* improved_values,
        __global const int* permutations,
        __global const int* spline_ints,
        __global const double* spline_locations,
        __global const int* spline_value_nodes,
        __global const double* spline_derivatives,
        __global const int* interpolated_nodes,
        __global double* lattice,
        __global double* scratch
) {
    int work_index = get_global_id(0);
    int lattice_points = params[ACC_LATTICE_POINTS];
    int index = params[ACC_SPARSE_LATTICE] != 0 ? lattice_references[work_index] : work_index;
    int chunk_index = index / lattice_points;
    if (chunk_index >= params[ACC_CHUNK_COUNT]) {
        return;
    }
    int point = index - chunk_index * lattice_points;
    int lattice_y = params[ACC_CELL_COUNT_Y] + 1;
    int lattice_xz = params[ACC_CELL_COUNT_XZ] + 1;
    int cell_y = point % lattice_y;
    int horizontal = point / lattice_y;
    int cell_z = horizontal % lattice_xz;
    int cell_x = horizontal / lattice_xz;
    int x = chunks[chunk_index * 2] * 16 + cell_x * params[ACC_CELL_WIDTH];
    int y = (params[ACC_MIN_CELL_Y] + cell_y) * params[ACC_CELL_HEIGHT];
    int z = chunks[chunk_index * 2 + 1] * 16 + cell_z * params[ACC_CELL_WIDTH];
    int scratch_base = work_index * params[ACC_NODE_COUNT];
    evaluate_graph_nodes(node_ints, node_values, normal_ints, normal_values, perlin_ints, perlin_values,
            amplitudes, improved_indices, improved_values, permutations, spline_ints, spline_locations,
            spline_value_nodes, spline_derivatives, scratch, scratch_base, x, y, z,
            params[ACC_NODE_COUNT], USAGE_INTERPOLATED_LATTICE);

    for (int slot = 0; slot < params[ACC_INTERPOLATOR_COUNT]; slot++) {
        int node = interpolated_nodes[slot];
        int lattice_index = (chunk_index * params[ACC_INTERPOLATOR_COUNT] + slot) * lattice_points + point;
        lattice[lattice_index] = scratch[scratch_base + node];
    }
}

__kernel void roadweaver_accurate_preliminary_init(
        __global const int* params,
        __global int* preliminary_surfaces
) {
    int index = get_global_id(0);
    preliminary_surfaces[index] = params[ACC_MIN_Y] - params[ACC_CELL_HEIGHT];
}

__kernel void roadweaver_accurate_preliminary(
        __global const int* params,
        __global const int* preliminary_positions,
        __global const int* node_ints,
        __global const double* node_values,
        __global const int* normal_ints,
        __global const double* normal_values,
        __global const int* perlin_ints,
        __global const double* perlin_values,
        __global const double* amplitudes,
        __global const int* improved_indices,
        __global const double* improved_values,
        __global const int* permutations,
        __global const int* spline_ints,
        __global const double* spline_locations,
        __global const int* spline_value_nodes,
        __global const double* spline_derivatives,
        __global double* scratch,
        volatile __global int* preliminary_surfaces
) {
    int index = get_global_id(0);
    int y_count = params[ACC_CELL_COUNT_Y] + 1;
    int point_index = index / y_count;
    int y_index = index - point_index * y_count;
    int position_base = point_index * 2;
    int y = params[ACC_MIN_Y] + y_index * params[ACC_CELL_HEIGHT];
    int scratch_base = index * params[ACC_NODE_COUNT];
    evaluate_graph_nodes(
            node_ints, node_values, normal_ints, normal_values, perlin_ints, perlin_values,
            amplitudes, improved_indices, improved_values, permutations, spline_ints, spline_locations,
            spline_value_nodes, spline_derivatives, scratch, scratch_base,
            preliminary_positions[position_base], y, preliminary_positions[position_base + 1],
            params[ACC_NODE_COUNT], USAGE_INITIAL_DENSITY);
    if (scratch[scratch_base + params[ACC_ROOT_INITIAL_DENSITY]] > 0.390625) {
        atomic_max(&preliminary_surfaces[point_index], y);
    }
}

__kernel void roadweaver_accurate_aquifer(
        __global const int* params,
        __global const int* chunks,
        __global const int* aquifer_positions,
        __global const int* point_preliminary_indices,
        __global const int* preliminary_surfaces,
        __global const int* node_ints,
        __global const double* node_values,
        __global const int* normal_ints,
        __global const double* normal_values,
        __global const int* perlin_ints,
        __global const double* perlin_values,
        __global const double* amplitudes,
        __global const int* improved_indices,
        __global const double* improved_values,
        __global const int* permutations,
        __global const int* spline_ints,
        __global const double* spline_locations,
        __global const int* spline_value_nodes,
        __global const double* spline_derivatives,
        __global const int* interpolated_nodes,
        __global const double* lattice,
        __global int* aquifer_status,
        __global double* scratch
) {
    int index = get_global_id(0);
    if (index >= params[ACC_AQUIFER_UNIQUE_POINTS]) {
        return;
    }
    int position_base = index * 3;
    int level;
    int kind;
    compute_aquifer_status(
            params, node_ints, node_values, normal_ints, normal_values, perlin_ints, perlin_values,
            amplitudes, improved_indices, improved_values, permutations, spline_ints, spline_locations,
            spline_value_nodes, spline_derivatives, point_preliminary_indices, preliminary_surfaces,
            scratch, index * params[ACC_NODE_COUNT], index,
            aquifer_positions[position_base], aquifer_positions[position_base + 1], aquifer_positions[position_base + 2],
            &level, &kind);
    aquifer_status[index * 2] = level;
    aquifer_status[index * 2 + 1] = kind;
}

__kernel void roadweaver_accurate_height_init(
        __global const int* params,
        __global int* world_surface,
        __global int* ocean_floor,
        __global int* motion_blocking
) {
    int column_index = get_global_id(0);
    world_surface[column_index] = params[ACC_MIN_Y];
    ocean_floor[column_index] = params[ACC_MIN_Y];
    motion_blocking[column_index] = params[ACC_MIN_Y];
}

__kernel void roadweaver_accurate_height_parallel(
        __global const int* params,
        __global const int* chunks,
        __global const int* columns,
        __global const int* aquifer_positions,
        __global const int* aquifer_point_indices,
        __global const int* node_ints,
        __global const double* node_values,
        __global const int* normal_ints,
        __global const double* normal_values,
        __global const int* perlin_ints,
        __global const double* perlin_values,
        __global const double* amplitudes,
        __global const int* improved_indices,
        __global const double* improved_values,
        __global const int* permutations,
        __global const int* spline_ints,
        __global const double* spline_locations,
        __global const int* spline_value_nodes,
        __global const double* spline_derivatives,
        __global const double* lattice,
        __global const int* aquifer_status,
        __global double* scratch,
        volatile __global int* world_surface,
        volatile __global int* ocean_floor,
        volatile __global int* motion_blocking
) {
    int work_index = get_global_id(0);
    int column_index = work_index / ACC_HEIGHT_PARALLEL_LANES;
    int lane = work_index - column_index * ACC_HEIGHT_PARALLEL_LANES;
    int column_base = column_index * 3;
    int chunk_index = columns[column_base];
    if (chunk_index >= params[ACC_CHUNK_COUNT]) {
        return;
    }
    int x = chunks[chunk_index * 2] * 16 + columns[column_base + 1];
    int z = chunks[chunk_index * 2 + 1] * 16 + columns[column_base + 2];
    int scratch_base = work_index * params[ACC_NODE_COUNT];
    int local_world_surface = params[ACC_MIN_Y];
    int local_ocean_floor = params[ACC_MIN_Y];
    int local_motion_blocking = params[ACC_MIN_Y];
    int max_y = params[ACC_MIN_Y] + params[ACC_HEIGHT];

    for (int y = params[ACC_MIN_Y] + lane; y < max_y; y += ACC_HEIGHT_PARALLEL_LANES) {
        evaluate_graph_interpolated(
                params, chunks, node_ints, node_values, normal_ints, normal_values, perlin_ints, perlin_values,
                amplitudes, improved_indices, improved_values, permutations, spline_ints, spline_locations,
                spline_value_nodes, spline_derivatives, lattice, scratch, scratch_base, chunk_index, x, y, z);
        double density = scratch[scratch_base + params[ACC_ROOT_FINAL_DENSITY]];
        int material_mask = classify_accurate_material(
                params, chunks, aquifer_positions, aquifer_point_indices, aquifer_status, scratch, scratch_base,
                chunk_index, x, y, z, density);
        int sample_height = y + 1;
        if ((material_mask & MATERIAL_WORLD_SURFACE) != 0) {
            local_world_surface = max(local_world_surface, sample_height);
        }
        if ((material_mask & MATERIAL_OCEAN_FLOOR) != 0) {
            local_ocean_floor = max(local_ocean_floor, sample_height);
        }
        if ((material_mask & MATERIAL_MOTION_BLOCKING) != 0) {
            local_motion_blocking = max(local_motion_blocking, sample_height);
        }
    }

    if (local_world_surface > params[ACC_MIN_Y]) {
        atomic_max(&world_surface[column_index], local_world_surface);
    }
    if (local_ocean_floor > params[ACC_MIN_Y]) {
        atomic_max(&ocean_floor[column_index], local_ocean_floor);
    }
    if (local_motion_blocking > params[ACC_MIN_Y]) {
        atomic_max(&motion_blocking[column_index], local_motion_blocking);
    }
}

__kernel void roadweaver_accurate_height(
        __global const int* params,
        __global const int* chunks,
        __global const int* columns,
        __global const int* aquifer_positions,
        __global const int* aquifer_point_indices,
        __global const int* node_ints,
        __global const double* node_values,
        __global const int* normal_ints,
        __global const double* normal_values,
        __global const int* perlin_ints,
        __global const double* perlin_values,
        __global const double* amplitudes,
        __global const int* improved_indices,
        __global const double* improved_values,
        __global const int* permutations,
        __global const int* spline_ints,
        __global const double* spline_locations,
        __global const int* spline_value_nodes,
        __global const double* spline_derivatives,
        __global const int* interpolated_nodes,
        __global const double* lattice,
        __global const int* aquifer_status,
        __global double* scratch,
        __global int* world_surface,
        __global int* ocean_floor,
        __global int* motion_blocking
) {
    int index = get_global_id(0);
    int column_base = index * 3;
    int chunk_index = columns[column_base];
    if (chunk_index >= params[ACC_CHUNK_COUNT]) {
        return;
    }
    int local_x = columns[column_base + 1];
    int local_z = columns[column_base + 2];
    int x = chunks[chunk_index * 2] * 16 + local_x;
    int z = chunks[chunk_index * 2 + 1] * 16 + local_z;
    int scratch_base = index * params[ACC_NODE_COUNT];
    int unresolved = MATERIAL_WORLD_SURFACE | MATERIAL_OCEAN_FLOOR | MATERIAL_MOTION_BLOCKING;
    int world_surface_height = params[ACC_MIN_Y];
    int ocean_floor_height = params[ACC_MIN_Y];
    int motion_blocking_height = params[ACC_MIN_Y];
    int top_y = params[ACC_MIN_Y] + params[ACC_HEIGHT] - 1;

    for (int y = top_y; y >= params[ACC_MIN_Y]; y--) {
        evaluate_graph_interpolated(
                params, chunks, node_ints, node_values, normal_ints, normal_values, perlin_ints, perlin_values,
                amplitudes, improved_indices, improved_values, permutations, spline_ints, spline_locations,
                spline_value_nodes, spline_derivatives, lattice, scratch, scratch_base, chunk_index, x, y, z);
        double density = scratch[scratch_base + params[ACC_ROOT_FINAL_DENSITY]];
        int material_mask = classify_accurate_material(
                params, chunks, aquifer_positions, aquifer_point_indices, aquifer_status, scratch, scratch_base,
                chunk_index, x, y, z, density);

        int height = y + 1;
        if ((unresolved & MATERIAL_WORLD_SURFACE) != 0 && (material_mask & MATERIAL_WORLD_SURFACE) != 0) {
            world_surface_height = height;
            unresolved &= ~MATERIAL_WORLD_SURFACE;
        }
        if ((unresolved & MATERIAL_OCEAN_FLOOR) != 0 && (material_mask & MATERIAL_OCEAN_FLOOR) != 0) {
            ocean_floor_height = height;
            unresolved &= ~MATERIAL_OCEAN_FLOOR;
        }
        if ((unresolved & MATERIAL_MOTION_BLOCKING) != 0 && (material_mask & MATERIAL_MOTION_BLOCKING) != 0) {
            motion_blocking_height = height;
            unresolved &= ~MATERIAL_MOTION_BLOCKING;
        }
        if (unresolved == 0) {
            break;
        }
    }

    world_surface[index] = world_surface_height;
    ocean_floor[index] = ocean_floor_height;
    motion_blocking[index] = motion_blocking_height;
}
