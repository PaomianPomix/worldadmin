package dev.worldadmin.world.seed;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class WorldSeedLockTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, Long.MAX_VALUE, 9_007_199_254_740_993L, -123, 0, 123})
    void createsAndReopensExactSeedBinding(long seed) throws IOException {
        Path world = directory.resolve("nested/dimension");
        WorldSeedLock.verifyOrCreate(world, seed, true);
        Path lock = world.resolve(WorldSeedLock.FILE_NAME);
        var document = JsonParser.parseString(Files.readString(lock)).getAsJsonObject();
        assertEquals(1, document.get("version").getAsInt());
        assertTrue(document.getAsJsonPrimitive("seed").isString());
        assertEquals(Long.toString(seed), document.get("seed").getAsString());
        WorldSeedLock.verifyOrCreate(world, seed, false);
        WorldSeedLock.verifyOrCreate(world, seed, true);
        assertOnlyLockRemains(world);
    }

    @Test
    void refusesChangedSeedAndLeavesOriginalBytesIntact() throws IOException {
        Path world = directory.resolve("dimension");
        WorldSeedLock.verifyOrCreate(world, Long.MAX_VALUE, true);
        Path lock = world.resolve(WorldSeedLock.FILE_NAME);
        byte[] original = Files.readAllBytes(lock);
        assertThrows(IOException.class, () -> WorldSeedLock.verifyOrCreate(world, Long.MIN_VALUE, true));
        assertThrows(IOException.class, () -> WorldSeedLock.verifyOrCreate(world, 0, false));
        assertArrayEquals(original, Files.readAllBytes(lock));
        assertOnlyLockRemains(world);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"version\":2,\"seed\":\"0\"}",
            "{\"version\":9223372036854775808,\"seed\":\"0\"}",
            "{\"version\":\"1\",\"seed\":\"0\"}",
            "{\"version\":1,\"seed\":\"0.1\"}",
            "{\"version\":1,\"seed\":9223372036854775808}",
            "{\"version\":1,\"seed\":true}",
            "{\"version\":1,\"seed\":null}",
            "{\"version\":1}",
            "{\"seed\":\"0\"}",
            "{\"version\":1,\"seed\":\"0\",\"seed\":\"0\"}",
            "{\"version\":1,\"version\":1,\"seed\":\"0\"}",
            "{\"version\":1,\"seed\":\"0\",\"unexpected\":true}",
            "{version:1,seed:0}",
            "{/*comment*/\"version\":1,\"seed\":0}",
            "{\"version\":1,\"seed\":0} {}",
            "{\"version\":1,\"seed\":",
            "[]", "null", ""
    })
    void rejectsInvalidLocksWithoutOverwritingThem(String contents) throws IOException {
        Path lock = directory.resolve(WorldSeedLock.FILE_NAME);
        Files.writeString(lock, contents);
        assertThrows(IOException.class, () -> WorldSeedLock.verifyOrCreate(directory, 0, true));
        assertEquals(contents, Files.readString(lock));
        assertOnlyLockRemains(directory);
    }

    @Test
    void readsLegacyNumericSeedWithoutLosingPrecision() throws IOException {
        Files.writeString(directory.resolve(WorldSeedLock.FILE_NAME),
                "{\"version\":1,\"seed\":9223372036854775807}");
        WorldSeedLock.verifyOrCreate(directory, Long.MAX_VALUE, false);
    }

    @Test
    void forbiddenMissingLockDoesNotCreateWorldDirectory() {
        Path world = directory.resolve("not-created/dimension");
        assertThrows(IOException.class, () -> WorldSeedLock.verifyOrCreate(world, 7, false));
        assertFalse(Files.exists(world.getParent()));
    }

    @Test
    void forbiddenMissingLockLeavesExistingWorldFilesUntouched() throws IOException {
        Path world = Files.createDirectory(directory.resolve("dimension"));
        Path region = Files.writeString(world.resolve("r.0.0.mca"), "existing generated chunks");
        assertThrows(IOException.class, () -> WorldSeedLock.verifyOrCreate(world, 7, false));
        assertEquals("existing generated chunks", Files.readString(region));
        try (var files = Files.list(world)) {
            assertEquals(List.of(region), files.toList());
        }
    }

    @Test
    void concurrentCreatorsWithSameSeedAllObserveSameCompleteLock() throws Exception {
        Path world = directory.resolve("dimension");
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(12)) {
            List<Future<Void>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                results.add(workers.submit(() -> {
                    start.await();
                    WorldSeedLock.verifyOrCreate(world, Long.MIN_VALUE, true);
                    return null;
                }));
            }
            start.countDown();
            for (var result : results) result.get();
        }
        WorldSeedLock.verifyOrCreate(world, Long.MIN_VALUE, false);
        assertOnlyLockRemains(world);
    }

    @Test
    void competingSeedsNeverReplaceTheWinner() throws Exception {
        Path world = directory.resolve("dimension");
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = workers.submit(competingCreator(world, 11, start));
            Future<Boolean> second = workers.submit(competingCreator(world, 22, start));
            start.countDown();
            boolean firstWon = first.get();
            boolean secondWon = second.get();
            assertNotEquals(firstWon, secondWon, "Exactly one distinct seed may become immutable");
            WorldSeedLock.verifyOrCreate(world, firstWon ? 11 : 22, false);
            assertThrows(IOException.class, () -> WorldSeedLock.verifyOrCreate(world, firstWon ? 22 : 11, true));
        }
        assertOnlyLockRemains(world);
    }

    @Test
    void createsWithoutHardLinksAndStillRefusesToOverwrite() throws IOException {
        Path archive = directory.resolve("world.zip");
        try (var filesystem = FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
            Path world = filesystem.getPath("/dimension");
            WorldSeedLock.verifyOrCreate(world, Long.MAX_VALUE, true);
            WorldSeedLock.verifyOrCreate(world, Long.MAX_VALUE, false);
            assertThrows(IOException.class, () -> WorldSeedLock.verifyOrCreate(world, Long.MIN_VALUE, true));
            assertOnlyLockRemains(world);
        }
    }

    private static Callable<Boolean> competingCreator(Path world, long seed, CountDownLatch start) {
        return () -> {
            start.await();
            try {
                WorldSeedLock.verifyOrCreate(world, seed, true);
                return true;
            } catch (IOException losingSeed) {
                return false;
            }
        };
    }

    private static void assertOnlyLockRemains(Path world) throws IOException {
        try (var files = Files.list(world)) {
            assertEquals(List.of(world.resolve(WorldSeedLock.FILE_NAME)), files.toList());
        }
    }
}
