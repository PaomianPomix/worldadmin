package net.shiroha233.roadweaver.planning.neoforge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.shiroha233.roadweaver.api.RoadWeaverWorlds;
import net.shiroha233.roadweaver.config.structure.StructureDiscoveryService;
import net.shiroha233.roadweaver.features.path.decoration.text.SignTextService;
import net.shiroha233.roadweaver.generation.IdleRoadGenerationService;
import net.shiroha233.roadweaver.generation.InitialGenManager;
import net.shiroha233.roadweaver.generation.RoadGenerationService;
import net.shiroha233.roadweaver.planning.RoadPlanningService;
import net.shiroha233.roadweaver.runtime.CacheManager;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import java.util.ArrayList;
import java.util.List;

/** Lifecycle never scans/generates spawn terrain; demand is admitted from ticks or the companion API. */
public final class ServerPlanningHooks {
    private static int tick;
    private ServerPlanningHooks() {}
    public static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(ServerPlanningHooks::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerPlanningHooks::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(ServerPlanningHooks::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerPlanningHooks::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerPlanningHooks::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(ServerPlanningHooks::onLevelLoad);
    }
    private static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && RoadWeaverWorlds.supports(level)) {
            net.shiroha233.roadweaver.persistence.files.StructureFileStorage.ensureLoadedAsync(level)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) System.getLogger(ServerPlanningHooks.class.getName()).log(
                                System.Logger.Level.ERROR, "Unable to preload road state for " + level.dimension().location(), failure);
                    });
        }
    }
    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // Initialize before levels/spawn chunks exist. LevelLoad preloads and
        // structure callbacks must keep their epoch and caches through Started.
        tick = 0;
        CacheManager.onServerStarted();
        ThreadPoolManager.onServerStarted(event.getServer());
        SignTextService.clearPending();
        IdleRoadGenerationService.onServerStarted();
        RoadGenerationService.onServerStarted();
    }
    private static void onServerStarted(ServerStartedEvent event) {
        if (event.getServer().overworld() != null) StructureDiscoveryService.discoverFromLevel(event.getServer().overworld());
    }
    private static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        int thisTick = tick++;
        if (thisTick % 20 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!RoadWeaverWorlds.supports(player.serverLevel())) continue;
                SignTextService.onChunkReady(player.serverLevel(), player.chunkPosition());
                RoadPlanningService.planAroundPlayer(player);
                IdleRoadGenerationService.tickPlayer(player);
            }
        }
        List<ServerLevel> worlds = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) if (RoadWeaverWorlds.supports(level)) worlds.add(level);
        // Rotate first admission so one busy world cannot monopolize the shared worker budget.
        for (int i = 0; i < worlds.size(); i++) {
            ServerLevel level = worlds.get(Math.floorMod(thisTick + i, worlds.size()));
            IdleRoadGenerationService.tick(level);
            RoadGenerationService.tick(level);
            SignTextService.tick(level);
        }
    }
    private static void onServerStopping(ServerStoppingEvent event) {
        ThreadPoolManager.onServerStopping();
        RoadGenerationService.onServerStopping();
        RoadPlanningService.resetAll();
        CacheManager.onServerStopping(event.getServer().getAllLevels());
        SignTextService.clearPending();
        tick = 0;
    }
    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            // Refuse before any cache is cleared. WorldAdmin also checks the combined API first.
            if (RoadGenerationService.hasPendingWork(level) || RoadPlanningService.hasPendingWork(level)) {
                throw new IllegalStateException("RoadWeaver work must drain before dimension unload: " + level.dimension().location());
            }
            RoadGenerationService.onDimensionUnload(level);
            IdleRoadGenerationService.onDimensionUnload(level);
            InitialGenManager.onDimensionUnload(level);
            CacheManager.onDimensionUnload(level);
        }
    }
}
