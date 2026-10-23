package net.shiroha233.roadweaver.api;

import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.generation.RoadGenerationService;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.runtime.LevelScopedState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Bounded per-level planning barriers. All registry/queue mutations run on the server thread. */
final class RegionPreparation {
    private static final int CORE_BLOCKS = PreparationGrid.CORE_BLOCKS;
    private static final int MAX_PENDING_TILES = 64;
    private static final int MAX_REMEMBERED_TILES = 4096;
    private static final LevelScopedState<State> STATES = new LevelScopedState<>();
    private static final AtomicInteger PENDING = new AtomicInteger();
    private record Tile(int x, int z) {}
    private static final class State {
        final LinkedHashMap<Tile, CompletableFuture<Void>> tiles = new LinkedHashMap<>();
        CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        int pending;
        volatile boolean closed;
    }

    private RegionPreparation() {}

    static CompletableFuture<Void> prepare(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        if (!RoadWeaverWorlds.supports(level)) return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported RoadWeaver world"));
        if (!level.getServer().isSameThread()) return onMain(level, () -> prepare(level, minX, minZ, maxX, maxZ));
        if (minX > maxX || minZ > maxZ || (long) maxX - minX > CORE_BLOCKS || (long) maxZ - minZ > CORE_BLOCKS
                || minX < -29_990_000 || minZ < -29_990_000 || maxX > 29_990_000 || maxZ > 29_990_000) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Request must be a valid bounded region of at most 1025 blocks per axis"));
        }
        if (!net.shiroha233.roadweaver.persistence.files.StructureFileStorage.isReady(level)) {
            return net.shiroha233.roadweaver.persistence.files.StructureFileStorage.ensureLoadedAsync(level)
                    .thenCompose(ignored -> onMain(level, () -> prepare(level, minX, minZ, maxX, maxZ)));
        }
        State state = STATES.get(level, State::new);
        var futures = new ArrayList<CompletableFuture<Void>>();
        synchronized (state) {
        for (int x = PreparationGrid.tileFor(minX); x <= PreparationGrid.tileFor(maxX); x++) {
            for (int z = PreparationGrid.tileFor(minZ); z <= PreparationGrid.tileFor(maxZ); z++) {
                Tile tile = new Tile(x, z);
                CompletableFuture<Void> future = state.tiles.get(tile);
                if (future == null) future = enqueue(level, state, tile);
                futures.add(future);
            }
        }
        }
        // Returning a dependent future prevents callers cancelling our shared tile leases.
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenCompose(ignored -> onMain(level,
                        () -> RoadGenerationService.prepareRegionAsync(level, minX, minZ, maxX, maxZ)));
    }

    private static CompletableFuture<Void> enqueue(ServerLevel level, State state, Tile tile) {
        if (PENDING.get() >= MAX_PENDING_TILES) return CompletableFuture.failedFuture(new RejectedExecutionException("Road planning queue is full; retry after work drains"));
        if (state.tiles.size() >= MAX_REMEMBERED_TILES) {
            var iterator = state.tiles.entrySet().iterator();
            while (iterator.hasNext() && state.tiles.size() >= MAX_REMEMBERED_TILES) {
                if (iterator.next().getValue().isDone()) iterator.remove();
            }
        }
        PENDING.incrementAndGet();
        state.pending++;
        CompletableFuture<Void> result = new CompletableFuture<>();
        state.tiles.put(tile, result);
        int minX = PreparationGrid.minBlock(tile.x);
        int minZ = PreparationGrid.minBlock(tile.z);
        int maxX = PreparationGrid.maxBlock(tile.x);
        int maxZ = PreparationGrid.maxBlock(tile.z);
        CompletableFuture<Void> scheduled = state.tail.handle((ignored, priorFailure) -> null)
                .thenCompose(ignored -> state.closed ? closedFuture()
                        : onMain(level, () -> RoadPlanningService.planRectAsync(level, minX, minZ, maxX, maxZ)))
                .thenCompose(ignored -> state.closed ? closedFuture()
                        : onMain(level, () -> RoadGenerationService.prepareRegionAsync(level, minX, minZ, maxX, maxZ)));
        state.tail = result;
        scheduled.whenComplete((ignored, failure) -> {
          if (state.closed) return;
          try { level.getServer().execute(() -> finish(state, tile, result, failure)); }
          catch (RuntimeException rejected) { finish(state, tile, result, rejected); }
        });
        return result;
    }

    private static void finish(State state, Tile tile, CompletableFuture<Void> result, Throwable failure) {
        synchronized (state) {
            if (state.closed || result.isDone()) return;
            state.pending--;
            PENDING.decrementAndGet();
            if (failure != null) state.tiles.remove(tile, result);
        }
        if (failure == null) result.complete(null); else result.completeExceptionally(failure);
    }

    static boolean hasPending(ServerLevel level) {
        State state = STATES.peek(level);
        if (state == null) return false;
        synchronized (state) { return state.pending != 0; }
    }

    static void clear(ServerLevel level) {
        if (hasPending(level)) throw new IllegalStateException("Road preparation is still draining");
        STATES.remove(level);
    }

    static void shutdown() {
        for (State state : STATES.valuesSnapshot()) {
            ArrayList<CompletableFuture<Void>> closing;
            synchronized (state) {
                state.closed = true;
                closing = new ArrayList<>(state.tiles.values());
                state.pending = 0;
            }
            for (CompletableFuture<Void> future : closing) {
                future.completeExceptionally(new java.util.concurrent.CancellationException("RoadWeaver server stopped"));
            }
        }
        STATES.clear();
        PENDING.set(0);
    }

    private static CompletableFuture<Void> closedFuture() {
        return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("Road preparation is closed"));
    }

    private static CompletableFuture<Void> onMain(ServerLevel level, Supplier<CompletableFuture<Void>> action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Runnable run = () -> {
            try {
                if (level.getServer().getLevel(level.dimension()) != level) throw new IllegalStateException("Road preparation world is unloaded");
                action.get().whenComplete((value, failure) -> {
                    if (failure == null) result.complete(null); else result.completeExceptionally(failure);
                });
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        };
        if (level.getServer().isSameThread()) run.run(); else level.getServer().execute(run);
        return result;
    }
}
