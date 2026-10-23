package dev.worldadmin.io;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AsyncJsonStoreTest {
    @TempDir
    Path directory;

    @Test
    void rapidUpdatesPersistTheLastCompleteUtf8SnapshotBeforeCloseReturns() throws IOException {
        Path file = directory.resolve("nested/jobs.json");
        AsyncJsonStore store = new AsyncJsonStore(file);
        String finalSnapshot;
        try (store) {
            for (int cursor = 0; cursor < 2_000; cursor++) {
                store.save(snapshot(cursor, "generating"));
            }
            finalSnapshot = snapshot(2_000, "预生成已暂停 — 检查点已保存");
            store.save(finalSnapshot);
        }

        assertNull(store.failure());
        String saved = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject parsed = JsonParser.parseString(saved).getAsJsonObject();
        assertEquals(2_000, parsed.get("cursor").getAsInt());
        assertEquals("预生成已暂停 — 检查点已保存", parsed.get("status").getAsString());
        assertEquals(finalSnapshot, saved);
    }

    @Test
    void replacesAnExistingSnapshotWithAShorterDocument() throws IOException {
        Path file = directory.resolve("jobs.json");
        String original = snapshot(41, "old state ".repeat(10_000));
        Files.writeString(file, original, StandardCharsets.UTF_8);
        String replacement = snapshot(42, "paused");
        AsyncJsonStore store = new AsyncJsonStore(file);
        try (store) {
            store.save(replacement);
        }

        assertNull(store.failure());
        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(replacement, saved, "Replacement must not retain bytes from the larger previous snapshot");
        assertEquals(42, JsonParser.parseString(saved).getAsJsonObject().get("cursor").getAsInt());
        assertFalse(Files.exists(file.resolveSibling("jobs.json.tmp")), "Successful replacement should leave no pending file");
    }

    @Test
    void exposesDirectoryCreationFailureAndPreservesTheBlockingFile() throws IOException {
        Path parentFile = directory.resolve("worldadmin");
        String existingContents = "Existing data must not be replaced with a directory";
        Files.writeString(parentFile, existingContents, StandardCharsets.UTF_8);
        AsyncJsonStore store = new AsyncJsonStore(parentFile.resolve("jobs.json"));
        try (store) {
            store.save(snapshot(10, "paused"));
        }

        IOException failure = store.failure();
        assertNotNull(failure, "Asynchronous write errors must remain visible after the worker exits");
        assertSame(failure, store.failure());
        assertTrue(Files.isRegularFile(parentFile));
        assertEquals(existingContents, Files.readString(parentFile, StandardCharsets.UTF_8));
    }

    @Test
    void failedReplacementLeavesThePreviousReadableCheckpointIntact() throws IOException {
        Path file = directory.resolve("jobs.json");
        String original = snapshot(75, "previous checkpoint");
        Files.writeString(file, original, StandardCharsets.UTF_8);
        // A directory at the temporary path makes writing fail on every platform,
        // including environments where permission changes do not restrict tests.
        Path blockedTemporary = file.resolveSibling("jobs.json.tmp");
        Files.createDirectory(blockedTemporary);
        AsyncJsonStore store = new AsyncJsonStore(file);
        try (store) {
            store.save(snapshot(99, "new checkpoint"));
        }

        assertNotNull(store.failure());
        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(original, saved);
        assertEquals(75, JsonParser.parseString(saved).getAsJsonObject().get("cursor").getAsInt());
        assertTrue(Files.isDirectory(blockedTemporary));
    }

    private static String snapshot(long cursor, String status) {
        JsonObject json = new JsonObject();
        json.addProperty("schema", 1);
        json.addProperty("cursor", cursor);
        json.addProperty("status", status);
        return json.toString();
    }
}
