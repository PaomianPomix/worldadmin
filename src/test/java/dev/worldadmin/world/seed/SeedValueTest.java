package dev.worldadmin.world.seed;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeedValueTest {
    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, Long.MAX_VALUE, 9_007_199_254_740_993L, -9_007_199_254_740_993L, -1, 0, 1})
    void preservesAllBitsFromNumericAndStringSeeds(long expected) {
        assertEquals(expected, SeedValue.read(new JsonPrimitive(Long.toString(expected))));
        assertEquals(expected, SeedValue.read(JsonParser.parseString(Long.toString(expected))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775807.0", "9.223372036854775807E18"})
    void acceptsExactlyIntegralNumbersWithoutRounding(String value) {
        assertEquals(Long.MAX_VALUE, SeedValue.read(JsonParser.parseString(value)));
        assertEquals(Long.MAX_VALUE, SeedValue.read(new JsonPrimitive(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.25", "-0.5", "9223372036854775807.1", "9223372036854775808", "-9223372036854775809", "1e100"})
    void rejectsFractionsAndOverflowForBothRepresentations(String value) {
        assertThrows(IllegalArgumentException.class, () -> SeedValue.read(JsonParser.parseString(value)));
        assertThrows(IllegalArgumentException.class, () -> SeedValue.read(new JsonPrimitive(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " 1", "1 ", "one", "NaN", "Infinity", "0x100", "1_000", "--1"})
    void rejectsMalformedStrings(String value) {
        assertThrows(IllegalArgumentException.class, () -> SeedValue.read(new JsonPrimitive(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "[]", "{}", "null"})
    void rejectsNonNumericJsonTypes(String value) {
        assertThrows(IllegalArgumentException.class, () -> SeedValue.read(JsonParser.parseString(value)));
    }

    @Test
    void rejectsMissingAndNullValues() {
        assertThrows(IllegalArgumentException.class, () -> SeedValue.read(null));
        assertThrows(IllegalArgumentException.class, () -> SeedValue.read(JsonNull.INSTANCE));
    }
}
