package net.shiroha233.roadweaver.runtime;

import net.minecraft.server.level.ServerLevel;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Keeps a level leased until asynchronous work and its server callback have both finished. */
public final class LevelTaskTracker {
    private static final LevelScopedState<State> STATES = new LevelScopedState<>();

    private static final class State {
        final AtomicInteger pending = new AtomicInteger();
        final Set<CompletableFuture<?>> responses = ConcurrentHashMap.newKeySet();
    }

    private LevelTaskTracker() {}

    public static <T> CompletableFuture<T> track(Object level, Supplier<CompletableFuture<T>> submit) {
        State state = STATES.get(level, State::new);
        CompletableFuture<T> response = new CompletableFuture<>();
        state.pending.incrementAndGet();
        state.responses.add(response);
        try {
            // The accounting callback belongs to the source; cancelling the response cannot release the lease early.
            submit.get().whenComplete((value, failure) -> {
                state.pending.decrementAndGet();
                state.responses.remove(response);
                if (failure == null) response.complete(value);
                else response.completeExceptionally(failure);
            });
        } catch (Throwable failure) {
            state.pending.decrementAndGet();
            state.responses.remove(response);
            response.completeExceptionally(failure);
        }
        return response;
    }

    public static boolean hasPendingWork(Object level) {
        State state = STATES.peek(level);
        return state != null && state.pending.get() != 0;
    }

    public static void clear(Object level) {
        if (hasPendingWork(level)) throw new IllegalStateException("World network tasks must drain before unloading");
        STATES.remove(level);
    }

    public static void shutdown() {
        for (State state : STATES.valuesSnapshot()) {
            for (CompletableFuture<?> response : state.responses) {
                response.completeExceptionally(new CancellationException("RoadWeaver server stopped"));
            }
        }
        STATES.clear();
    }

    public static CompletableFuture<Void> runOnServer(ServerLevel level, Runnable action) {
        if (level == null) return CompletableFuture.completedFuture(null);
        var server = level.getServer();
        var dimension = level.dimension();
        long epoch = ThreadPoolManager.currentEpoch();
        return track(level, () -> {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            Runnable callback = () -> {
                try {
                    if (!ThreadPoolManager.isEpoch(epoch) || server.getLevel(dimension) != level) {
                        throw new CancellationException("World is no longer loaded");
                    }
                    action.run();
                    completion.complete(null);
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            };
            try {
                if (server.isSameThread()) callback.run();
                else server.execute(callback);
            } catch (RuntimeException rejected) {
                completion.completeExceptionally(rejected);
            }
            return completion;
        });
    }
}
