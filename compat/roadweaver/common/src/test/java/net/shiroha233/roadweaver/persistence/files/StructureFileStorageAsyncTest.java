package net.shiroha233.roadweaver.persistence.files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StructureFileStorageAsyncTest {
    @TempDir Path directory;

    @BeforeEach void start() {
        ThreadPoolManager.onServerStarted(null);
        StructureFileStorage.onServerStarted();
    }
    @AfterEach void stop() {
        ThreadPoolManager.onServerStopping();
        StructureFileStorage.shutdown();
    }

    private StructureFileStorage.Entry open(Object key, String name) throws Exception {
        var entry = StructureFileStorage.openEntry(key, directory.resolve(name).resolve("state.json"),
                directory.resolve(name).resolve("legacy.dat"));
        entry.loading.get(5, TimeUnit.SECONDS);
        return entry;
    }

    @Test
    void memoryRemainsAuthoritativeUntilCheckpointAndReload() throws Exception {
        Object firstLevel = new Object();
        var entry = open(firstLevel, "first");
        Files.createDirectories(entry.path.getParent());
        Files.writeString(entry.path, "{\"meta\":{\"external\":\"changed\"}}");
        assertFalse(entry.data.meta.containsKey("external"));
        synchronized (entry.lock) {
            entry.data.meta.put("owner", "first-world");
            StructureFileStorage.markDirty(entry);
            assertThrows(IllegalStateException.class, () -> StructureFileStorage.closeEntry(firstLevel));
        }
        StructureFileStorage.requestFlush(entry).get(5, TimeUnit.SECONDS);
        assertFalse(StructureFileStorage.hasPendingEntry(firstLevel));
        StructureFileStorage.closeEntry(firstLevel);

        var reloaded = open(new Object(), "first");
        assertEquals("first-world", reloaded.data.meta.get("owner"));
        assertFalse(reloaded.data.meta.containsKey("external"));
    }

    @Test
    void equalWorldKeysStayIsolatedAndCheckpointLatestChanges() throws Exception {
        record World(String dimension) {}
        World first = new World("same-id"), second = new World("same-id");
        var a = open(first, "a");
        var b = open(second, "b");
        synchronized (a.lock) {
            for (int i = 0; i < 20; i++) {
                a.data.meta.put("revision", Integer.toString(i));
                StructureFileStorage.markDirty(a);
            }
        }
        StructureFileStorage.requestFlush(a).get(5, TimeUnit.SECONDS);
        StructureFileStorage.closeEntry(first);
        assertTrue(b.data.meta.isEmpty());
        assertEquals("19", open(new Object(), "a").data.meta.get("revision"));
    }

    @Test
    void legacyOnlySaveFailsWithoutOverwritingAndExistingJsonWins() throws Exception {
        Path legacy = directory.resolve("legacy.dat");
        Path state = directory.resolve("state.json");
        Files.writeString(legacy, "preserve legacy bytes");
        var blocked = StructureFileStorage.openEntry(new Object(), state, legacy);
        assertThrows(ExecutionException.class, () -> blocked.loading.get(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(state));
        assertEquals("preserve legacy bytes", Files.readString(legacy));

        Files.writeString(state, "{}");
        var existing = StructureFileStorage.openEntry(new Object(), state, legacy);
        existing.loading.get(5, TimeUnit.SECONDS);
        assertTrue(existing.data.connections.isEmpty());
        assertEquals("preserve legacy bytes", Files.readString(legacy));
    }
}
