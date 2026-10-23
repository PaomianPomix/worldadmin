package net.shiroha233.roadweaver.generation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.shiroha233.roadweaver.api.RoadWeaverWorlds;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.features.path.config.PathFeatureConfig;
import net.shiroha233.roadweaver.features.path.pathlogic.core.Road;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.path.PlannedPathCache;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.postprocess.RoadSnapService;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/** Server-thread-owned queues; workers only calculate/persist road data, never place level blocks. */
public final class RoadGenerationService {
    private RoadGenerationService() {}
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final int MAX_PENDING_PER_WORLD = 4096;
    private static final int MAX_REGION_CANDIDATES = 512;
    private static final int PLACEMENT_PADDING = 512;
    private static final Map<ServerLevel, WorldState> WORLDS = new IdentityHashMap<>();
    private static final ResourceLocation ROAD_CF_ID = ResourceLocation.fromNamespaceAndPath("roadweaver", "road_feature");
    private static int globallyRunning;
    private static long currentTick;

    private static final class Work {
        final StructureConnection connection;
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        CompletableFuture<Void> worker;
        Work(StructureConnection connection) { this.connection = connection; }
    }
    private static final class WorldState {
        final Map<PlannedPathKey, Work> pending = new LinkedHashMap<>();
        final ArrayDeque<Work> queue = new ArrayDeque<>();
        final Map<PlannedPathKey, Integer> failures = new HashMap<>();
        final Set<CompletableFuture<?>> preparations = new HashSet<>();
        boolean refreshing;
        List<BlockPos> players = List.of();
        int lastPlayerTick = Integer.MIN_VALUE;
        int lastRefreshTick = Integer.MIN_VALUE;
        int running;
        volatile boolean closed;
    }

    public static long currentTick() { return currentTick; }
    public static void onServerStarted() { WORLDS.clear(); globallyRunning = 0; currentTick = 0; }
    public static void onServerStopping() {
        for (WorldState state : WORLDS.values()) {
            state.closed = true;
            for (CompletableFuture<?> preparation : new ArrayList<>(state.preparations)) {
                preparation.completeExceptionally(new CancellationException("Server stopping"));
            }
            state.preparations.clear();
            for (Work work : state.pending.values()) {
                work.completion.completeExceptionally(new CancellationException("Server stopping"));
                if (work.worker != null) work.worker.cancel(true);
            }
        }
        WORLDS.clear(); globallyRunning = 0;
        IdleRoadGenerationService.onServerStopping();
        InitialGenManager.onServerStopping();
    }

    /** Spatial selection runs on the worker; only the bounded selected edges are admitted on main. */
    public static CompletableFuture<Void> prepareRegionAsync(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        requireMain(level);
        if (!RoadWeaverWorlds.supports(level)) return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported road dimension"));
        if (!StructureFileStorage.isReady(level)) {
            return StructureFileStorage.ensureLoadedAsync(level).thenComposeAsync(ignored ->
                    prepareRegionAsync(level, minX, minZ, maxX, maxZ), level.getServer());
        }
        if (!ConfigService.get().roadAppearance().roadsEnabled()) return CompletableFuture.completedFuture(null);
        WorldState state = WORLDS.computeIfAbsent(level, ignored -> new WorldState());
        CompletableFuture<Void> result = new CompletableFuture<>();
        state.preparations.add(result);
        selectRegion(level, minX, minZ, maxX, maxZ).whenComplete((selected, error) -> level.getServer().execute(() -> {
            if (!active(level, state)) { finishPreparation(state, result, new CancellationException("World closed")); return; }
            if (error != null) { finishPreparation(state, result, error); return; }
            if (!ConfigService.get().roadAppearance().roadsEnabled()) { finishPreparation(state, result, new CancellationException("Road generation disabled")); return; }
            try {
                List<CompletableFuture<Void>> waiting = new ArrayList<>();
                for (StructureConnection snapshot : selected) {
                    PlannedPathKey key = PlannedPathKey.of(snapshot);
                    StructureConnection connection = StructureFileStorage.getConnection(level, key);
                    if (connection == null || connection.status() == ConnectionStatus.COMPLETED) continue;
                    if (connection.status() == ConnectionStatus.FAILED) {
                        if (state.failures.getOrDefault(key, 0) >= 3) throw new IllegalStateException(
                                "Road failed three times; explicitly retry the affected region: " + connection.from() + " -> " + connection.to());
                        transition(level, connection, ConnectionStatus.PLANNED);
                    }
                    if (state.pending.size() >= MAX_PENDING_PER_WORLD && !state.pending.containsKey(key)) {
                        throw new RejectedExecutionException("Too many pending road connections in " + level.dimension().location());
                    }
                    waiting.add(enqueue(state, connection).completion);
                }
                CompletableFuture.allOf(waiting.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) ->
                        finishPreparation(state, result, failure));
            } catch (Throwable failure) { finishPreparation(state, result, failure); }
        }));
        return result.thenApply(ignored -> null);
    }

    /** Explicit operator reset; failed roads are never treated as completed terrain. */
    public static CompletableFuture<Integer> retryFailedRegion(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        requireMain(level);
        if (!StructureFileStorage.isReady(level)) return StructureFileStorage.ensureLoadedAsync(level).thenComposeAsync(
                ignored -> retryFailedRegion(level, minX, minZ, maxX, maxZ), level.getServer());
        WorldState state = WORLDS.computeIfAbsent(level, ignored -> new WorldState());
        CompletableFuture<Integer> result = new CompletableFuture<>();
        state.preparations.add(result);
        selectRegion(level, minX, minZ, maxX, maxZ).whenComplete((selected, error) -> level.getServer().execute(() -> {
            try {
                if (!active(level, state)) throw new CancellationException("World closed");
                if (error != null) throw new CompletionException(error);
                int reset = 0;
                for (StructureConnection snapshot : selected) {
                    PlannedPathKey key = PlannedPathKey.of(snapshot);
                    StructureConnection connection = StructureFileStorage.getConnection(level, key);
                    if (connection == null) continue;
                    if (connection.status() == ConnectionStatus.FAILED && !state.pending.containsKey(key)) {
                        state.failures.remove(key);
                        if (transition(level, connection, ConnectionStatus.PLANNED)) reset++;
                    }
                }
                result.complete(reset);
            } catch (Throwable failure) { result.completeExceptionally(failure); }
            finally { state.preparations.remove(result); }
        }));
        return result.thenApply(value -> value);
    }

    private static CompletableFuture<List<StructureConnection>> selectRegion(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        return ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.PLANNING, () -> {
            List<StructureConnection> selected = new ArrayList<>();
            var config = ConfigService.get().pathfindingCost();
            var mode = net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions.forLevel(level).effectiveMode();
            for (StructureConnection connection : WorldDataProvider.getInstance().getStructureConnections(level)) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Region selection interrupted");
                if (connection == null || connection.status() == ConnectionStatus.COMPLETED) continue;
                var path = PlannedPathCache.find(level, connection, mode, config);
                RoadInfluenceBounds bounds;
                if (path.isPresent() && !path.get().isEmpty()) {
                    int x0=Integer.MAX_VALUE,z0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,z1=Integer.MIN_VALUE;
                    for (BlockPos point : path.get()) { x0=Math.min(x0,point.getX()); z0=Math.min(z0,point.getZ()); x1=Math.max(x1,point.getX()); z1=Math.max(z1,point.getZ()); }
                    bounds = RoadInfluenceBounds.endpoints(x0,z0,x1,z1,PLACEMENT_PADDING);
                } else {
                    // The fallback calculator's search field is capped at a 2048-block margin;
                    // include grid snapping and placement/decorations as additional padding.
                    bounds = RoadInfluenceBounds.endpoints(connection.from().getX(), connection.from().getZ(),
                            connection.to().getX(), connection.to().getZ(), 2048+128+PLACEMENT_PADDING);
                }
                if (!bounds.intersects(minX,minZ,maxX,maxZ)) continue;
                if (selected.size() == MAX_REGION_CANDIDATES) throw new RejectedExecutionException(
                        "Region has more than 512 unfinished roads; reduce the preparation region");
                selected.add(connection);
            }
            return selected;
        });
    }

    private static boolean active(ServerLevel level, WorldState state) {
        return !state.closed && WORLDS.get(level) == state && !ThreadPoolManager.isStopping()
                && level.getServer().getLevel(level.dimension()) == level;
    }
    private static void finishPreparation(WorldState state, CompletableFuture<Void> result, Throwable failure) {
        state.preparations.remove(result);
        if (failure == null) result.complete(null); else result.completeExceptionally(failure);
    }

    public static void tick(ServerLevel level) {
        if (!RoadWeaverWorlds.supports(level)) return;
        requireMain(level);
        if (!StructureFileStorage.isReady(level)) { StructureFileStorage.ensureLoadedAsync(level); return; }
        currentTick = level.getServer().getTickCount();
        WorldState state = WORLDS.computeIfAbsent(level, ignored -> new WorldState());
        if (state.closed) return;
        if (!ConfigService.get().roadAppearance().roadsEnabled()) {
            // Queued futures must settle; running tasks remain leased until their real callback.
            Work queued;
            while ((queued = state.queue.poll()) != null) {
                state.pending.remove(PlannedPathKey.of(queued.connection));
                queued.completion.completeExceptionally(new CancellationException("Road generation disabled"));
            }
            return;
        }
        int tick = level.getServer().getTickCount();
        if (!state.refreshing && (state.lastRefreshTick == Integer.MIN_VALUE || tick - state.lastRefreshTick >= 20)) {
            state.lastRefreshTick = tick;
            refreshAsync(level, state);
        }
        if (state.queue.isEmpty() || state.running > 0 || globallyRunning >= ThreadPoolManager.workerLimit()) return;
        if (ThreadPoolManager.sharedBacklog() >= 128) return;
        if (state.lastPlayerTick == Integer.MIN_VALUE || tick - state.lastPlayerTick >= 5) {
            state.players = level.players().stream().map(ServerPlayer::blockPosition).toList();
            state.lastPlayerTick = tick;
        }
        Work work = pollNearest(state);
        if (work == null) return;
        if (!transition(level, work.connection, ConnectionStatus.GENERATING)) {
            state.pending.remove(PlannedPathKey.of(work.connection));
            work.completion.completeExceptionally(new IllegalStateException("Road connection disappeared while queued"));
            return;
        }
        state.running++; globallyRunning++;
        long epoch = ThreadPoolManager.currentEpoch();
        boolean notifyPlayers = !state.players.isEmpty();
        work.worker = ThreadPoolManager.runAsync(ThreadPoolManager.TaskRole.GENERATION, () -> {
            if (state.closed || !ThreadPoolManager.isEpoch(epoch)) throw new CancellationException("World generation session closed");
            if (!generateTask(level, work.connection)) throw new IllegalStateException("Road calculation failed: " + work.connection);
            if (state.closed || !ThreadPoolManager.isEpoch(epoch)) throw new CancellationException("World generation session closed");
            // This method transforms persisted RoadData only. Its upstream Async variant runs
            // the expensive geometry pass on the game thread, so keep it on this bounded worker.
            RoadSnapService.snapAroundConnection(level, work.connection.from(), work.connection.to());
            if (notifyPlayers) MapPatchService.publishRoadForConnectionAsync(level, work.connection);
        });
        work.worker.whenComplete((ignored, error) -> level.getServer().execute(() -> finish(level, state, work, epoch, error)));
    }

    private static void finish(ServerLevel level, WorldState state, Work work, long epoch, Throwable error) {
        if (state.closed || !ThreadPoolManager.isEpoch(epoch) || WORLDS.get(level) != state) return;
        Throwable failure = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (failure == null && !ConfigService.get().roadAppearance().roadsEnabled()) failure = new CancellationException("Road generation disabled");
        try {
            ConnectionStatus status = failure == null ? ConnectionStatus.COMPLETED
                    : failure instanceof RejectedExecutionException || failure instanceof CancellationException ? ConnectionStatus.PLANNED : ConnectionStatus.FAILED;
            if (!transition(level, work.connection, status)) throw new IllegalStateException("Generated connection no longer exists");
            if (status == ConnectionStatus.FAILED) state.failures.merge(PlannedPathKey.of(work.connection), 1, Integer::sum);
            else if (status == ConnectionStatus.COMPLETED) state.failures.remove(PlannedPathKey.of(work.connection));
            if (!state.players.isEmpty()) MapPatchService.publishConnection(level,
                    new StructureConnection(work.connection.from(), work.connection.to(), status));
            if (failure == null) work.completion.complete(null);
            else { work.completion.completeExceptionally(failure); LOGGER.warn("Road task failed in {}", level.dimension().location(), failure); }
        } catch (Throwable callbackFailure) {
            work.completion.completeExceptionally(callbackFailure);
            LOGGER.error("Road completion failed in {}", level.dimension().location(), callbackFailure);
        } finally {
            state.pending.remove(PlannedPathKey.of(work.connection));
            state.running--; globallyRunning--;
        }
    }

    public static boolean generateTask(ServerLevel level, StructureConnection connection) {
        if (level == null || connection == null || !RoadWeaverWorlds.supports(level)) return false;
        try {
            if (Thread.currentThread().isInterrupted()) return false;
            ConfiguredFeature<?, ?> feature = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE).get(ROAD_CF_ID);
            PathFeatureConfig featureConfig = feature != null && feature.config() instanceof PathFeatureConfig config ? config : new PathFeatureConfig();
            ModConfig config = ConfigService.get();
            if (!config.roadAppearance().roadsEnabled()) { PlannedPathCache.discard(level, connection); return true; }
            return new Road(level, connection, featureConfig, RoadGenerationConfig.from(config))
                    .generateRoad(config.pathfindingCost().aStarMaxSteps()) != null;
        } catch (Throwable failure) {
            LOGGER.warn("Road calculation failed in {}: {}", level.dimension().location(), connection, failure);
            return false;
        }
    }

    public static boolean hasPendingWork(ServerLevel level) {
        WorldState state = WORLDS.get(level);
        return state != null && (!state.pending.isEmpty() || state.running > 0 || state.refreshing || !state.preparations.isEmpty())
                || IdleRoadGenerationService.hasPendingWork(level) || InitialGenManager.hasPendingWork(level);
    }

    public static void onDimensionUnload(ServerLevel level) {
        if (hasPendingWork(level)) throw new IllegalStateException("RoadWeaver still has work in " + level.dimension().location());
        WorldState state = WORLDS.remove(level);
        if (state != null) { state.closed = true; state.queue.clear(); state.players = List.of(); }
        InitialGenManager.onDimensionUnload(level);
    }

    private static Work enqueue(WorldState state, StructureConnection connection) {
        return state.pending.computeIfAbsent(PlannedPathKey.of(connection), ignored -> {
            Work work = new Work(connection); state.queue.add(work); return work;
        });
    }
    private static Work pollNearest(WorldState state) {
        if (state.players.isEmpty()) return state.queue.poll();
        Work best = null;
        long bestDistance = Long.MAX_VALUE;
        int checked = 0;
        for (Work candidate : state.queue) {
            if (++checked > 64) break;
            for (BlockPos player : state.players) {
                long distance = Math.min(distance(player, candidate.connection.from()), distance(player, candidate.connection.to()));
                if (distance < bestDistance) { bestDistance = distance; best = candidate; }
            }
        }
        if (best != null) state.queue.remove(best); else best = state.queue.poll();
        return best;
    }
    private static long distance(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX(), dz = (long) a.getZ() - b.getZ(); return dx * dx + dz * dz;
    }
    private static boolean transition(ServerLevel level, StructureConnection connection, ConnectionStatus status) {
        return StructureFileStorage.transitionConnection(level, PlannedPathKey.of(connection), status);
    }

    private static void refreshAsync(ServerLevel level, WorldState state) {
        state.refreshing = true;
        ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.MAP, () -> {
            List<StructureConnection> candidates = new ArrayList<>();
            for (StructureConnection connection : WorldDataProvider.getInstance().getStructureConnections(level)) {
                if (connection.status() == ConnectionStatus.PLANNED || connection.status() == ConnectionStatus.GENERATING) {
                    candidates.add(connection);
                    if (candidates.size() == 64) break;
                }
            }
            return candidates;
        }).whenComplete((candidates, failure) -> level.getServer().execute(() -> {
            state.refreshing = false;
            if (!active(level, state) || failure != null || !ConfigService.get().roadAppearance().roadsEnabled()) return;
            for (StructureConnection snapshot : candidates) {
                if (state.pending.size() >= MAX_PENDING_PER_WORLD) break;
                PlannedPathKey key = PlannedPathKey.of(snapshot);
                StructureConnection current = StructureFileStorage.getConnection(level, key);
                if (current != null && (current.status() == ConnectionStatus.PLANNED || current.status() == ConnectionStatus.GENERATING)) enqueue(state, current);
            }
        }));
    }
    private static void requireMain(ServerLevel level) {
        if (level == null || !level.getServer().isSameThread()) throw new IllegalStateException("Road generation admission requires the server thread");
    }
}
