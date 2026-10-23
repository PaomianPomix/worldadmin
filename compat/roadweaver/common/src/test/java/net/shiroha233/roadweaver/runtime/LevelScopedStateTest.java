package net.shiroha233.roadweaver.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LevelScopedStateTest {
    private record World(String dimension) {}

    @Test
    void identicalChunkCoordinatesRemainIsolatedBetweenWorlds() {
        LevelScopedState<Map<Long, String>> states = new LevelScopedState<>();
        Object overworld = new World("minecraft:overworld");
        Object customWorld = new World("worldadmin:build");
        states.get(overworld, HashMap::new).put(42L, "overworld road");
        states.get(customWorld, HashMap::new).put(42L, "custom road");

        states.remove(customWorld);

        assertEquals("overworld road", states.peek(overworld).get(42L));
        assertNull(states.peek(customWorld));
    }

    @Test
    void reloadingSameDimensionDoesNotReuseOldLevelState() {
        LevelScopedState<Map<Long, String>> states = new LevelScopedState<>();
        World oldLevel = new World("worldadmin:build");
        World reloadedLevel = new World("worldadmin:build");
        assertEquals(oldLevel, reloadedLevel);
        Map<Long, String> oldState = states.get(oldLevel, HashMap::new);
        oldState.put(7L, "old pending sign");

        assertTrue(states.get(reloadedLevel, HashMap::new).isEmpty());
        states.remove(oldLevel);
        oldState.put(8L, "late callback");

        assertTrue(states.peek(reloadedLevel).isEmpty());
        assertEquals(1, states.valuesSnapshot().size());
    }
}
