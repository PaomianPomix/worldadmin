package dev.worldadmin.compat;

import dev.worldadmin.WorldAdminConfig;
import dev.worldadmin.pregen.core.LoadGate;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Optional DH 3.3.3 chunk-worldgen admission. Road planning is initiated on the main thread;
 * the original DH generator resumes on its own executor only after its road future succeeds.
 */
public final class DistantHorizonsRoadGate {
    private static final System.Logger LOGGER = System.getLogger(DistantHorizonsRoadGate.class.getName());
    private static final int MAX_WAITING = 64;
    private static final int GENERATION_WIDTH_CHUNKS = 4;
    private static final int DEPENDENCY_HALO_CHUNKS = 8;
    private static final Map<MinecraftServer, State> STATES = new ConcurrentHashMap<>();
    private static final AtomicLong INTERCEPTED = new AtomicLong();
    private static final AtomicLong DELEGATED = new AtomicLong();
    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();
    private static volatile String lastStatus = "DH road gate: no FEATURES requests observed (DH 3.3.3 companion integration)";
    private static final ClassValue<Field> LEVEL_FIELDS = new ClassValue<>() {
        @Override protected Field computeValue(Class<?> type) {
            try { return type.getField("serverLevelWrapper"); }
            catch (NoSuchFieldException failure) { throw new IllegalStateException("Unsupported DH generator wrapper", failure); }
        }
    };
    private DistantHorizonsRoadGate() {}

    /** Called by the optional mixin on DH's thread; no game state is mutated here. */
    public static CompletableFuture<Void> wrap(Object generator, int minChunkX, int minChunkZ, byte detail,
                                               Object mode, ExecutorService executor,
                                               Supplier<CompletableFuture<Void>> original) {
        String modeName = mode instanceof Enum<?> enumeration ? enumeration.name() : String.valueOf(mode);
        if (!requiresRoads(detail, modeName)) return original.get();
        try {
            Object wrapper = LEVEL_FIELDS.get(generator.getClass()).get(generator);
            Object rawLevel = wrapper.getClass().getMethod("getWrappedMcObject").invoke(wrapper);
            if (!(rawLevel instanceof ServerLevel level)) {
                return CompletableFuture.failedFuture(new IllegalStateException("DH server wrapper did not expose a ServerLevel"));
            }
            INTERCEPTED.incrementAndGet();
            return enqueue(level, minChunkX, minChunkZ, executor, original);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
            return CompletableFuture.failedFuture(cause);
        }
    }

    public static boolean requiresRoads(byte detail, String mode) {
        return detail == 0 && ("FEATURES".equals(mode) || "INTERNAL_SERVER".equals(mode));
    }

    /** First two DH arguments are CHUNK coordinates; detail 0 covers four chunks per axis. */
    public static int[] preparationBounds(int minChunkX, int minChunkZ) {
        long minX = ((long) minChunkX - DEPENDENCY_HALO_CHUNKS) * 16;
        long minZ = ((long) minChunkZ - DEPENDENCY_HALO_CHUNKS) * 16;
        long maxX = ((long) minChunkX + GENERATION_WIDTH_CHUNKS + DEPENDENCY_HALO_CHUNKS) * 16 - 1;
        long maxZ = ((long) minChunkZ + GENERATION_WIDTH_CHUNKS + DEPENDENCY_HALO_CHUNKS) * 16 - 1;
        if (minX < Integer.MIN_VALUE || minZ < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE || maxZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("DH chunk coordinates overflow block coordinates");
        }
        return new int[] {(int) minX, (int) minZ, (int) maxX, (int) maxZ};
    }

    private static CompletableFuture<Void> enqueue(ServerLevel level, int x, int z, ExecutorService executor,
                                                    Supplier<CompletableFuture<Void>> original) {
        MinecraftServer server = level.getServer();
        if (server.isStopped() || server.isShutdown()) return CompletableFuture.failedFuture(new CancellationException("Server stopped"));
        int[] bounds = preparationBounds(x, z);
        State state = STATES.computeIfAbsent(server, ignored -> new State());
        Request request = new Request(level, bounds, executor, original);
        synchronized (state) {
            if (state.stopped) return CompletableFuture.failedFuture(new CancellationException("DH road gate stopped"));
            if (state.suspended) return CompletableFuture.failedFuture(new CancellationException("DH admissions suspended for World Admin pregeneration"));
            if (state.unloading.contains(level)) {
                return CompletableFuture.failedFuture(new CancellationException("DH target dimension is unloading"));
            }
            if (state.waiting.size() >= MAX_WAITING) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("DH road preparation queue is full"));
            }
            state.byLevel.merge(level, 1, Integer::sum);
            state.waiting.addLast(request);
        }
        return request.result;
    }

    /** Called once from the server's Post tick, with the same measured tick sample as normal pregeneration. */
    public static void tick(MinecraftServer server, long tickNanos) {
        State state = STATES.get(server);
        if (state == null || state.stopped) return;
        if (!server.isSameThread()) throw new IllegalStateException("DH road admission requires the server thread");
        if (state.gate == null) {
            LoadGate.Config config = WorldAdminConfig.loadGate();
            state.gate = new LoadGate(new LoadGate.Config(1, config.tickEwmaAlpha(), config.tickPauseNanos(),
                    config.tickRecoverNanos(), config.spikeTickNanos(), config.spikeCooldownNanos(),
                    config.heapPauseFraction(), config.heapRecoverFraction(), config.minSubmissionIntervalNanos(),
                    config.maxSubmissionIntervalNanos()));
        }
        long now = System.nanoTime();
        state.gate.recordTick(Math.max(0, tickNanos), now);
        Request expired = null;
        synchronized (state) {
            if (state.active != null && !state.active.finished.get() && !state.active.delegated
                    && now - state.active.admittedNanos > WorldAdminConfig.ROAD_TIMEOUT_SECONDS.get() * 1_000_000_000L) {
                // Seal the individual request before releasing the lock: an executor may
                // start its delayed continuation concurrently with timeout completion.
                state.active.rejectedBeforeDelegation = true;
                expired = state.active;
            }
        }
        if (expired != null) {
            finish(state, expired, new TimeoutException("DH road preparation or delegation exceeded its timeout"));
            LOGGER.log(System.Logger.Level.WARNING,
                    "DH road preparation timed out in " + expired.level.dimension().location()
                            + "; retained RoadWeaver work will drain independently");
        }
        synchronized (state) {
            if (state.active != null && state.active.finished.get()) state.active = null;
            if (state.suspended) {
                lastStatus = "DH road gate: suspended for World Admin pregeneration; active=" + (state.active == null ? 0 : 1);
                return;
            }
            Runtime runtime = Runtime.getRuntime();
            double heap = (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();
            LoadGate.Decision decision = state.gate.evaluate(now, heap, state.active == null ? 0 : 1);
            lastStatus = "DH road gate: queued=" + state.waiting.size() + ", active=" + (state.active == null ? 0 : 1)
                    + ", admission=" + decision.reason() + ", completed=" + COMPLETED.get() + ", failed=" + FAILED.get();
            if (!decision.allowed() || state.waiting.isEmpty()) return;
            state.active = state.waiting.removeFirst();
            state.active.admittedNanos = now;
        }
        Request request = state.active;
        state.gate.recordSubmission(now);
        if (server.getLevel(request.level.dimension()) != request.level) {
            finish(state, request, new IllegalStateException("DH target dimension was unloaded before admission"));
            return;
        }
        try {
            CompletableFuture<Void> roads = RoadWeaverBridge.prepareRegion(request.level,
                    request.bounds[0], request.bounds[1], request.bounds[2], request.bounds[3]);
            roads.thenComposeAsync(ignored -> {
                synchronized (state) {
                    if (state.stopped || state.suspended || request.rejectedBeforeDelegation || request.finished.get()) {
                        throw new CancellationException("DH road request cancelled before delegated generation");
                    }
                    request.delegated = true;
                    DELEGATED.incrementAndGet();
                }
                CompletableFuture<Void> generation = request.original.get();
                if (generation == null) throw new IllegalStateException("DH generator returned no future");
                return generation;
            }, request.executor).whenComplete((ignored, failure) -> finish(state, request, failure));
        } catch (Throwable failure) { finish(state, request, failure); }
    }

    private static void finish(State state, Request request, Throwable failure) {
        if (!request.finished.compareAndSet(false, true)) return;
        synchronized (state) {
            state.byLevel.computeIfPresent(request.level, (level, count) -> count <= 1 ? null : count - 1);
        }
        if (failure == null) { COMPLETED.incrementAndGet(); request.result.complete(null); }
        else { FAILED.incrementAndGet(); request.result.completeExceptionally(failure); }
        if (state.stopped && state.byLevel.isEmpty()) STATES.remove(request.level.getServer(), state);
    }

    public static boolean hasPendingWork(ServerLevel level) {
        State state = STATES.get(level.getServer());
        return state != null && state.byLevel.getOrDefault(level, 0) > 0;
    }

    /**
     * Atomically reject new DH requests and verify that previously accepted work has drained.
     * Keep this seal after a successful unload: a late DH callback must not reopen the old level.
     */
    public static boolean beginUnload(ServerLevel level) {
        State state = STATES.computeIfAbsent(level.getServer(), ignored -> new State());
        synchronized (state) {
            if (state.stopped || state.byLevel.getOrDefault(level, 0) != 0) return false;
            state.unloading.add(level);
            return true;
        }
    }

    /** Reopen admission only when an aborted unload leaves this exact level usable. */
    public static void cancelUnload(ServerLevel level) {
        State state = STATES.get(level.getServer());
        if (state == null) return;
        synchronized (state) {
            if (!state.stopped) state.unloading.remove(level);
        }
    }

    /**
     * Seal admission before changing DH's generator configuration. Queued and road-only
     * requests can safely fail; a delegated generator keeps its source until it really ends.
     * Returns true only when disabling DH cannot discard a live delegated request.
     */
    public static boolean pauseForPregeneration(MinecraftServer server) {
        State state = STATES.computeIfAbsent(server, ignored -> new State());
        ArrayList<Request> rejected = new ArrayList<>();
        boolean delegatedRunning;
        synchronized (state) {
            state.suspended = true;
            Request request;
            while ((request = state.waiting.pollFirst()) != null) rejected.add(request);
            if (state.active != null && !state.active.delegated && !state.active.finished.get()) {
                state.active.rejectedBeforeDelegation = true;
                rejected.add(state.active);
            }
            delegatedRunning = state.active != null && state.active.delegated && !state.active.finished.get();
        }
        for (Request request : rejected) {
            finish(state, request, new CancellationException("DH admission suspended before delegated generation"));
        }
        return !delegatedRunning;
    }

    /** Reopen only after DH's temporary pregeneration override has been released. */
    public static void resumeAfterPregeneration(MinecraftServer server) {
        State state = STATES.get(server);
        if (state == null) return;
        synchronized (state) {
            if (!state.stopped) state.suspended = false;
        }
    }

    /** Reject work not yet admitted; an active delegate retains its future until it actually ends. */
    public static void stop(MinecraftServer server) {
        State state = STATES.get(server);
        if (state == null) return;
        ArrayList<Request> rejected = new ArrayList<>();
        synchronized (state) {
            state.stopped = true;
            Request request;
            while ((request = state.waiting.pollFirst()) != null) rejected.add(request);
            // DH may synchronously close its queue later in this event. If it has not started
            // touching the pooled source, release it immediately without waiting for a road
            // callback that itself needs the now-stopping main thread.
            if (state.active != null && !state.active.delegated) {
                rejected.add(state.active);
            }
        }
        // Completing a future can invoke DH callbacks inline; never run those while
        // holding the admission lock that DH's own threads need when enqueueing.
        for (Request request : rejected) finish(state, request, new CancellationException("Server stopping before DH generation"));
        lastStatus = "DH road gate: stopping; active work is draining";
        if (state.byLevel.isEmpty()) STATES.remove(server, state);
    }
    public static String status() { return lastStatus; }
    public static long interceptedCount() { return INTERCEPTED.get(); }
    public static long delegatedCount() { return DELEGATED.get(); }
    public static long completedCount() { return COMPLETED.get(); }

    private static final class State {
        final ArrayDeque<Request> waiting = new ArrayDeque<>();
        final Map<ServerLevel, Integer> byLevel = new ConcurrentHashMap<>();
        // ServerLevel retains identity equality. Weak keys retain the closed identity only
        // as long as another integration still references it, without leaking old levels.
        final Set<ServerLevel> unloading = Collections.newSetFromMap(new WeakHashMap<>());
        volatile boolean stopped;
        boolean suspended;
        LoadGate gate;
        Request active;
    }
    private static final class Request {
        final ServerLevel level;
        final int[] bounds;
        final ExecutorService executor;
        final Supplier<CompletableFuture<Void>> original;
        // DH recycles its pooled source when this future completes. Cancellation must not
        // report completion while the original generator is still writing that source.
        final CompletableFuture<Void> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        };
        final AtomicBoolean finished = new AtomicBoolean();
        long admittedNanos;
        boolean delegated;
        boolean rejectedBeforeDelegation;
        Request(ServerLevel level, int[] bounds, ExecutorService executor, Supplier<CompletableFuture<Void>> original) {
            this.level = level; this.bounds = bounds; this.executor = executor; this.original = original;
        }
    }
}
