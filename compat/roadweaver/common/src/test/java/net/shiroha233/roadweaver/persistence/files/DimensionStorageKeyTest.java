package net.shiroha233.roadweaver.persistence.files;

import net.minecraft.resources.ResourceLocation;
import net.shiroha233.roadweaver.map.tile.storage.ServerMapTilePathResolver;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DimensionStorageKeyTest {
    @Test
    void preservesExistingOverworldFilesAndMigrationMarkers() {
        ResourceLocation overworld = ResourceLocation.parse("minecraft:overworld");
        assertEquals("minecraft_overworld", FileStoragePathResolver.dimensionKey(overworld));
        assertEquals("minecraft_overworld", ServerMapTilePathResolver.dimensionKey(overworld));
    }

    @Test
    void distinctResourceIdsCannotAliasFlattenedOrWindowsReservedPaths() {
        List<String> ids = List.of("mod:a/b", "mod:a_b", "mod_a:b", "mod:ab",
                "worldadmin:con", "worldadmin:aux", "worldadmin:build", "minecraft:overworld");
        HashSet<String> keys = new HashSet<>();
        for (String id : ids) {
            ResourceLocation dimension = ResourceLocation.parse(id);
            String key = FileStoragePathResolver.dimensionKey(dimension);
            assertTrue(keys.add(key), "Storage path collision for " + id);
            assertTrue(key.matches("[a-z0-9_]+"), key);
            assertEquals(key, ServerMapTilePathResolver.dimensionKey(dimension));
        }
    }
}
