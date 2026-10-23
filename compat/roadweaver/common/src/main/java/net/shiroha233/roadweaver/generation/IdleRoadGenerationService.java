package net.shiroha233.roadweaver.generation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.shiroha233.roadweaver.api.RoadWeaverWorlds;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Per-world idle planning windows. All road generation uses the one bounded shared dispatcher. */
public final class IdleRoadGenerationService {
    private IdleRoadGenerationService() {}
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final Map<ServerLevel, Map<UUID, Window>> WORLDS = new IdentityHashMap<>();
    private static final class Window {
        int x, z, radius;
        CompletableFuture<Void> planning = CompletableFuture.completedFuture(null);
        Window(int x, int z, int radius) { this.x = x; this.z = z; this.radius = radius; }
    }
    public static void onServerStarted() { WORLDS.clear(); }
    public static void onServerStopping() { WORLDS.clear(); }

    public static void tickPlayer(ServerPlayer player) {
        if (player == null || !RoadWeaverWorlds.supports(player.serverLevel())) return;
        ServerLevel level = player.serverLevel();
        if (!StructureFileStorage.isReady(level)) { StructureFileStorage.ensureLoadedAsync(level); return; }
        ModConfig config = ConfigService.get();
        if (!config.roadAppearance().roadsEnabled() || !config.performance().idleGenerationEnabled()) return;
        if (ThreadPoolManager.sharedBacklog() >= 32) return;
        int radius = Math.max(1, config.planning().dynamicPlanEnabled()
                ? config.planning().dynamicPlanRadiusChunks() : config.planning().initialPlanRadiusChunks());
        int x = player.chunkPosition().x, z = player.chunkPosition().z;
        Map<UUID, Window> windows = WORLDS.computeIfAbsent(level, ignored -> new HashMap<>());
        Window window = windows.get(player.getUUID());
        if (window == null) {
            window = new Window(x, z, radius); windows.put(player.getUUID(), window);
            schedule(level, window); return;
        }
        if (!window.planning.isDone()) return;
        int distance = Math.max(Math.abs(x - window.x), Math.abs(z - window.z));
        if (distance >= 8) {
            window.x = x; window.z = z;
            if (distance > window.radius) window.radius = radius;
            schedule(level, window); return;
        }
        if (window.planning.isCompletedExceptionally()) { schedule(level, window); return; }
        // Do not expand while there are known roads left to generate in this window.
        List<StructureConnection> connections = WorldDataProvider.getInstance().getStructureConnections(level);
        if (connections != null) for (StructureConnection connection : connections) {
            if (connection.status() != ConnectionStatus.PLANNED && connection.status() != ConnectionStatus.GENERATING) continue;
            int cx = (int) (((long) connection.from().getX() + connection.to().getX()) >> 5);
            int cz = (int) (((long) connection.from().getZ() + connection.to().getZ()) >> 5);
            if (Math.abs(cx - window.x) <= window.radius && Math.abs(cz - window.z) <= window.radius) return;
        }
        // Idle expansion itself must be finite; operators can request larger regions explicitly.
        int maximum = Math.min(256, Math.max(radius, radius * 2));
        if (window.radius < maximum) { window.radius = Math.min(maximum, window.radius + 32); schedule(level, window); }
    }

    private static void schedule(ServerLevel level, Window window) {
        int minX = (window.x - window.radius) * 16, minZ = (window.z - window.radius) * 16;
        int maxX = (window.x + window.radius) * 16, maxZ = (window.z + window.radius) * 16;
        window.planning = RoadPlanningService.planRectAsync(level, minX, minZ, maxX, maxZ);
        window.planning.whenComplete((ignored, failure) -> {
            if (failure != null) LOGGER.warn("Idle road planning failed in {}", level.dimension().location(), failure);
        });
    }

    public static void tick(ServerLevel level) {
        Map<UUID, Window> windows = WORLDS.get(level);
        if (windows == null) return;
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer player : level.players()) present.add(player.getUUID());
        // Retain a departed player's pending future until it really finishes.
        windows.entrySet().removeIf(entry -> !present.contains(entry.getKey()) && entry.getValue().planning.isDone());
        if (windows.isEmpty()) WORLDS.remove(level);
    }

    public static boolean hasPendingWork(ServerLevel level) {
        Map<UUID, Window> windows = WORLDS.get(level);
        return windows != null && windows.values().stream().anyMatch(window -> !window.planning.isDone());
    }
    public static void onDimensionUnload(ServerLevel level) {
        if (hasPendingWork(level)) throw new IllegalStateException("Idle road planning still active in " + level.dimension().location());
        WORLDS.remove(level);
    }
    // Compatibility for upstream callers: work is no longer owned by a second generation pool.
    public static boolean shouldReserveForIdle(ServerLevel level, StructureConnection connection) { return false; }
    public static boolean isManagedByIdle(ServerLevel level, StructureConnection connection) { return false; }
    public static boolean shouldReserveForIdleCached(StructureConnection connection, PlannedPathKey key, boolean enabled) { return false; }
    public static boolean isManagedByIdleCached(StructureConnection connection, PlannedPathKey key, boolean enabled) { return false; }
}
