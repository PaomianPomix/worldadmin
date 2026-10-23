package net.shiroha233.roadweaver.generation;

import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.api.RoadWeaverWorlds;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressSnapshot;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Legacy initial-generation entry points now enqueue work and never block the server tick. */
public final class InitialGenManager {
    private InitialGenManager() {}
    private static final Map<ServerLevel, CompletableFuture<Void>> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, CompletableFuture<Boolean>> EXISTING_ROADS = new ConcurrentHashMap<>();
    public static boolean isActive() { return ACTIVE.values().stream().anyMatch(future -> !future.isDone()); }
    public static int getTotal() { return ACTIVE.size(); }
    public static int getDone() { return (int) ACTIVE.values().stream().filter(future -> future.isDone() && !future.isCompletedExceptionally()).count(); }
    public static int getGenerating() { return (int) ACTIVE.values().stream().filter(future -> !future.isDone()).count(); }
    public static int getFailed() { return (int) ACTIVE.values().stream().filter(CompletableFuture::isCompletedExceptionally).count(); }
    public static InitialGenerationProgressSnapshot getProgressSnapshot() { return InitialGenerationProgressTracker.snapshot(isActive()); }

    public static void begin(ServerLevel level) {
        if (!RoadWeaverWorlds.supports(level) || hasPendingWork(level)) return;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Initial road admission requires the server thread");
        if (!StructureFileStorage.isReady(level)) {
            StructureFileStorage.ensureLoadedAsync(level).thenRunAsync(() -> begin(level), level.getServer());
            return;
        }
        int radius = Math.max(1, ConfigService.get().planning().initialPlanRadiusChunks());
        int x = level.getSharedSpawnPos().getX(), z = level.getSharedSpawnPos().getZ();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        ACTIVE.put(level, completion);
        RoadPlanningService.initialPlanAsync(level).whenComplete((ignored, failure) -> level.getServer().execute(() -> {
            if (ACTIVE.get(level) != completion) { completion.cancel(false); return; }
            if (failure != null) { completion.completeExceptionally(failure); return; }
            RoadGenerationService.prepareRegionAsync(level, x - radius * 16, z - radius * 16, x + radius * 16, z + radius * 16)
                    .whenComplete((result, error) -> {
                        if (error == null) completion.complete(null); else completion.completeExceptionally(error);
                    });
        }));
    }
    public static boolean shouldRunInitialGeneration(ServerLevel level) {
        if (!RoadWeaverWorlds.supports(level)) return false;
        CompletableFuture<Void> active = ACTIVE.get(level);
        if (active != null && !active.isDone()) return false;
        if (!StructureFileStorage.isReady(level)) { StructureFileStorage.ensureLoadedAsync(level); return false; }
        var connections = WorldDataProvider.getInstance().getStructureConnections(level);
        if (connections != null && !connections.isEmpty()) return false;
        CompletableFuture<Boolean> check = EXISTING_ROADS.computeIfAbsent(level, world ->
                net.shiroha233.roadweaver.runtime.ThreadPoolManager.supplyAsync(
                        net.shiroha233.roadweaver.runtime.ThreadPoolManager.TaskRole.MAP, () -> RoadShardStorage.hasAnyRoad(world)));
        return check.isDone() && !check.isCompletedExceptionally() && !check.getNow(true);
    }
    /** @deprecated Kept for binary/source compatibility. All progress is driven by normal ticks. */
    @Deprecated public static void blockUntilDone(ServerLevel level) { if (!ACTIVE.containsKey(level)) begin(level); }
    public static void update(ServerLevel level) {}
    public static boolean hasPendingWork(ServerLevel level) {
        CompletableFuture<Void> future = ACTIVE.get(level);
        CompletableFuture<Boolean> check = EXISTING_ROADS.get(level);
        return future != null && !future.isDone() || check != null && !check.isDone();
    }
    public static void onDimensionUnload(ServerLevel level) {
        if (hasPendingWork(level)) throw new IllegalStateException("Initial road planning still active");
        ACTIVE.remove(level);
        EXISTING_ROADS.remove(level);
    }
    public static void onServerStopping() { ACTIVE.values().forEach(future -> future.cancel(false)); ACTIVE.clear(); EXISTING_ROADS.clear(); }
}
