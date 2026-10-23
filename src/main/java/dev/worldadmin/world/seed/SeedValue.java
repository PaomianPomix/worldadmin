package dev.worldadmin.world.seed;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;

/** Reads seeds without routing their 64 bits through a floating-point number. */
public final class SeedValue {
    private SeedValue() {}

    public static long read(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("World seed must be a signed 64-bit integer");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isString() && !primitive.isNumber()) {
            throw new IllegalArgumentException("World seed must be a signed 64-bit integer");
        }
        try {
            return new BigDecimal(primitive.getAsString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("World seed must be a signed 64-bit integer", exception);
        }
    }
}
