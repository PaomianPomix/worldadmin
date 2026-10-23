package dev.worldadmin.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One worker, one replaceable pending snapshot; no unbounded disk-write queue. */
public final class AsyncJsonStore implements AutoCloseable {
    private final Path path;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "worldadmin-state-io");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<String> pending = new AtomicReference<>();
    private boolean scheduled;
    private volatile IOException failure;

    public AsyncJsonStore(Path path) { this.path = path; }
    public IOException failure() { return failure; }

    public synchronized void save(String json) {
        pending.set(json);
        if (!scheduled) {
            scheduled = true;
            worker.execute(this::drain);
        }
    }

    private void drain() {
        while (true) {
            String json;
            synchronized (this) {
                json = pending.getAndSet(null);
                if (json == null) { scheduled = false; return; }
            }
            try {
                Files.createDirectories(path.getParent());
                var temporary = path.resolveSibling(path.getFileName() + ".tmp");
                Files.writeString(temporary, json, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) { failure = e; }
        }
    }

    @Override public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                failure = new IOException("State writer did not finish within 10 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = new IOException("Interrupted closing state writer", e);
        }
    }
}
