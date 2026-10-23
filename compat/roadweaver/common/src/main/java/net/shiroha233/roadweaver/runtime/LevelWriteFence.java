package net.shiroha233.roadweaver.runtime;

import net.minecraft.server.level.ServerLevel;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;

/** Rejects late writes without retaining unloaded levels through permanent tombstones. */
public final class LevelWriteFence {
    private static final ArrayList<WeakReference<ServerLevel>> CLOSED = new ArrayList<>();

    private LevelWriteFence() {}

    public static void requireCurrentSession() {
        if (ThreadPoolManager.isStopping() || !ThreadPoolManager.isCurrentWorkerSession()) {
            throw new CancellationException("RoadWeaver worker session is stopped or stale");
        }
    }

    public static void requireActive(ServerLevel level) {
        requireCurrentSession();
        synchronized (CLOSED) {
            CLOSED.removeIf(reference -> reference.get() == null);
            for (WeakReference<ServerLevel> reference : CLOSED) {
                if (reference.get() == level) throw new CancellationException("World storage is closed");
            }
        }
        if (level == null || level.getServer().getLevel(level.dimension()) != level) {
            throw new CancellationException("World is no longer loaded");
        }
    }

    public static void close(ServerLevel level) {
        if (level == null) return;
        synchronized (CLOSED) {
            CLOSED.removeIf(reference -> reference.get() == null);
            for (WeakReference<ServerLevel> reference : CLOSED) {
                if (reference.get() == level) return;
            }
            CLOSED.add(new WeakReference<>(level));
        }
    }
}
