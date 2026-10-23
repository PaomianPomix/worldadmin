package dev.worldadmin.world.seed;

import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** A create-once seed binding stored alongside the dimension's generated data. */
public final class WorldSeedLock {
    public static final String FILE_NAME = "worldadmin-seed.json";

    private WorldSeedLock() {}

    public static void verifyOrCreate(Path dimensionDirectory, long requestedSeed, boolean allowMissing)
            throws IOException {
        Path lock = dimensionDirectory.resolve(FILE_NAME);
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
            verify(lock, requestedSeed);
            return;
        }
        if (!allowMissing) {
            throw new IOException("Missing immutable world seed lock: " + lock);
        }

        Files.createDirectories(dimensionDirectory);
        Path temporary = Files.createTempFile(dimensionDirectory, ".worldadmin-seed-", ".tmp");
        try {
            byte[] contents = ("{\"version\":1,\"seed\":\"" + requestedSeed + "\"}\n")
                    .getBytes(StandardCharsets.UTF_8);
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                writeAndForce(output, contents);
            }
            try {
                // ATOMIC_MOVE may silently replace an existing target, even without
                // REPLACE_EXISTING. A hard link publishes complete data atomically
                // and has create-new semantics, including across competing JVMs.
                Files.createLink(lock, temporary);
            } catch (FileAlreadyExistsException race) {
                // The winning seed must be verified, including for same-seed races.
            } catch (UnsupportedOperationException | FileSystemException unsupportedLink) {
                publishWithoutHardLinks(lock, contents);
            }
            verify(lock, requestedSeed);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        Files.deleteIfExists(temporary);
    }

    private static void publishWithoutHardLinks(Path lock, byte[] contents) throws IOException {
        // CREATE_NEW cannot overwrite a winner. On filesystems without hard links,
        // an interrupted write may leave an invalid lock; subsequent loads fail
        // closed instead of assigning a new seed to existing generated data.
        try (FileChannel output = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writeAndForce(output, contents);
        } catch (FileAlreadyExistsException race) {
            // The caller verifies the winner. A partial concurrent write fails closed.
        }
    }

    private static void writeAndForce(FileChannel output, byte[] contents) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(contents);
        while (buffer.hasRemaining()) {
            output.write(buffer);
        }
        output.force(true);
    }

    private static void verify(Path lock, long requestedSeed) throws IOException {
        Long version = null;
        Long seed = null;
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(lock, StandardCharsets.UTF_8))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "version" -> {
                        if (version != null || reader.peek() != JsonToken.NUMBER) {
                            throw new IOException("Invalid or duplicate seed lock version: " + lock);
                        }
                        version = SeedValue.read(new JsonPrimitive(reader.nextString()));
                    }
                    case "seed" -> {
                        if (seed != null || (reader.peek() != JsonToken.NUMBER && reader.peek() != JsonToken.STRING)) {
                            throw new IOException("Invalid or duplicate world seed: " + lock);
                        }
                        seed = SeedValue.read(new JsonPrimitive(reader.nextString()));
                    }
                    default -> throw new IOException("Unknown seed lock property '" + name + "': " + lock);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Trailing content in world seed lock: " + lock);
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new IOException("Invalid immutable world seed lock: " + lock, exception);
        }
        if (version == null || version != 1 || seed == null) {
            throw new IOException("Missing seed or unsupported immutable world seed lock version: " + lock);
        }
        if (seed != requestedSeed) {
            throw new IOException("World seed is immutable: requested " + requestedSeed + ", but existing world uses "
                    + seed + " (" + lock + ")");
        }
    }
}
