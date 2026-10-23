/* 文件职责：实现 NeoForge 平台道路地图的请求、快照与增量状态网络通信。 */
package net.shiroha233.roadweaver.network.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.shiroha233.roadweaver.RoadWeaver;
import net.shiroha233.roadweaver.api.RoadNetworkApi;
import net.shiroha233.roadweaver.client.map.ClientMapAccessGuard;
import net.shiroha233.roadweaver.client.map.MapLoadPhase;
import net.shiroha233.roadweaver.client.map.RoadMapScreen;
import net.shiroha233.roadweaver.client.map.data.MapDataCollector;
import net.shiroha233.roadweaver.client.map.data.MapSnapshot;
import net.shiroha233.roadweaver.client.map.data.MapSnapshotCache;
import net.shiroha233.roadweaver.client.map.data.MapSnapshotPatch;
import net.shiroha233.roadweaver.client.map.data.MapAutomaticPlanningSamplingCache;
import net.shiroha233.roadweaver.map.permission.MapAccessService;
import net.shiroha233.roadweaver.network.MapNetworkPayloads;
import net.shiroha233.roadweaver.map.search.MapStructureSearchService;
import net.shiroha233.roadweaver.map.search.MapSearchResult;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.runtime.LevelTaskTracker;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.api.RoadWeaverWorlds;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingActivities;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingBounds;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** NeoForge 平台网络通信实现。 */
public final class MapNetworkNeoForge {
    private MapNetworkNeoForge() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(MapNetworkNeoForge::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(RoadWeaver.MOD_ID);

        registrar.playToServer(MapNetworkPayloads.REQ_RECT, MapNetworkPayloads.MapRequestRectPayload.CODEC, MapNetworkNeoForge::handleRequestRect);
        registrar.playToServer(MapNetworkPayloads.TP_REQ, MapNetworkPayloads.MapTeleportPayload.CODEC, MapNetworkNeoForge::handleTeleportRequest);
        registrar.playToServer(MapNetworkPayloads.MAN_REQ, MapNetworkPayloads.MapManualConnectPayload.CODEC, MapNetworkNeoForge::handleManualConnect);
        registrar.playToServer(MapNetworkPayloads.SEARCH_REQ, MapNetworkPayloads.MapSearchRequestPayload.CODEC, MapNetworkNeoForge::handleSearchRequest);

        registrar.playToClient(MapNetworkPayloads.SNAP, MapNetworkPayloads.MapSnapshotPayload.CODEC, MapNetworkNeoForge::handleSnapshot);
        registrar.playToClient(MapNetworkPayloads.PATCH, MapNetworkPayloads.MapPatchPayload.CODEC, MapNetworkNeoForge::handlePatch);
        registrar.playToClient(MapNetworkPayloads.AUTO_PLANNING_SAMPLING, MapNetworkPayloads.MapAutomaticPlanningSamplingPayload.CODEC, MapNetworkNeoForge::handleAutomaticPlanningSampling);
        registrar.playToClient(MapNetworkPayloads.TP_ACK, MapNetworkPayloads.MapTeleportAckPayload.CODEC, MapNetworkNeoForge::handleTeleportAck);
        registrar.playToClient(MapNetworkPayloads.ACCESS_SYNC, MapNetworkPayloads.MapAccessSyncPayload.CODEC, MapNetworkNeoForge::handleAccessSync);
        registrar.playToClient(MapNetworkPayloads.SEARCH_RESP, MapNetworkPayloads.MapSearchResponsePayload.CODEC, MapNetworkNeoForge::handleSearchResponse);
    }

    private static void handleRequestRect(MapNetworkPayloads.MapRequestRectPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!MapAccessService.canOpenMap(player)) {
                syncMapAccess(player);
                return;
            }

            ServerLevel level = player.serverLevel();
            ResourceLocation dimension = level.dimension().location();
            if (!dimension.equals(payload.dimension())) return;
            LevelTaskTracker.track(level, () -> StructureFileStorage.ensureLoadedAsync(level).thenCompose(loaded ->
                    ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.MAP, () -> {
                MapSnapshot snapshot = buildSnapshot(level, payload.phase(), payload.minX(), payload.minZ(), payload.maxX(), payload.maxZ());
                return new MapNetworkPayloads.MapSnapshotPayload(
                        payload.requestSeq(), dimension, payload.phase(), payload.responseIndex(), snapshot,
                        AutomaticPlanningSamplingActivities.snapshot(level));
            })).thenCompose(reply -> LevelTaskTracker.runOnServer(level, () -> {
                if (canReply(player, level)) PacketDistributor.sendToPlayer(player, reply);
            })));
        });
    }

    private static void handleSnapshot(MapNetworkPayloads.MapSnapshotPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            MapAutomaticPlanningSamplingCache.replace(
                    payload.dimension(),
                    payload.automaticPlanningSamplingBounds());
            if (minecraft.screen instanceof RoadMapScreen screen) {
                screen.acceptSnapshotPart(payload.requestSeq(), payload.dimension(), payload.phase(), payload.responseIndex(), payload.snapshot());
            }
        });
    }

    private static void handlePatch(MapNetworkPayloads.MapPatchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof RoadMapScreen screen) {
                screen.acceptPatch(payload.dimension(), payload.patch());
            } else {
                MapSnapshotCache.applyPatch(payload.dimension(), payload.patch());
            }
        });
    }

    private static void handleTeleportRequest(MapNetworkPayloads.MapTeleportPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!player.isCreative() && !player.hasPermissions(2)) {
                PacketDistributor.sendToPlayer(player, new MapNetworkPayloads.MapTeleportAckPayload(false, 0, 0, 0));
                return;
            }

            ServerLevel level = player.serverLevel();
            int x = payload.x();
            int z = payload.z();
            LevelTaskTracker.track(level, () -> level.getChunkSource()
                    .getChunkFuture(x >> 4, z >> 4, ChunkStatus.FULL, true)
                    .thenCompose(ignored -> LevelTaskTracker.runOnServer(level, () -> {
                        if (player.isRemoved() || player.hasDisconnected() || player.serverLevel() != level) return;
                        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        y = y <= level.getMinBuildHeight() ? level.getSeaLevel() + 1 : y + 1;
                        player.teleportTo(level, x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());
                        PacketDistributor.sendToPlayer(player, new MapNetworkPayloads.MapTeleportAckPayload(true, x, y, z));
                    })));
        });
    }

    private static void handleTeleportAck(MapNetworkPayloads.MapTeleportAckPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            if (payload.success()) {
                player.displayClientMessage(
                        Component.translatable("gui.roadweaver.map.teleport.success_pos", payload.x(), payload.y(), payload.z()), true);
            } else {
                player.displayClientMessage(Component.translatable("gui.roadweaver.map.teleport.denied"), true);
            }
        });
    }

    private static void handleAccessSync(MapNetworkPayloads.MapAccessSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientMapAccessGuard.applyServerState(Minecraft.getInstance(), payload.allowed()));
    }

    private static void handleAutomaticPlanningSampling(
            MapNetworkPayloads.MapAutomaticPlanningSamplingPayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> MapAutomaticPlanningSamplingCache.replace(
                payload.dimension(),
                payload.bounds()));
    }

    private static void handleSearchRequest(MapNetworkPayloads.MapSearchRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!MapAccessService.canOpenMap(player)) {
                PacketDistributor.sendToPlayer(player, new MapNetworkPayloads.MapSearchResponsePayload(
                        payload.requestSeq(), payload.dimension(), false, java.util.List.of()));
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!level.dimension().location().equals(payload.dimension())
                    || !MapStructureSearchService.tryBeginRequest(player.getUUID())) {
                PacketDistributor.sendToPlayer(player, new MapNetworkPayloads.MapSearchResponsePayload(
                        payload.requestSeq(), payload.dimension(), false, java.util.List.of()));
                return;
            }
            var playerId = player.getUUID();
            LevelTaskTracker.track(level, () -> {
                CompletableFuture<List<MapSearchResult>> search;
                try {
                    search = StructureFileStorage.ensureLoadedAsync(level).thenCompose(loaded ->
                            ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.MAP,
                                    () -> MapStructureSearchService.search(level, payload.query())));
                } catch (RuntimeException rejected) {
                    search = CompletableFuture.failedFuture(rejected);
                }
                return search.handle((results, failure) -> new MapNetworkPayloads.MapSearchResponsePayload(
                                payload.requestSeq(), payload.dimension(), failure == null,
                                failure == null ? results : java.util.List.of()))
                        .thenCompose(reply -> LevelTaskTracker.runOnServer(level, () -> {
                            if (canReply(player, level)) PacketDistributor.sendToPlayer(player, reply);
                        }));
            }).whenComplete((ignored, failure) -> MapStructureSearchService.finishRequest(playerId));
        });
    }

    private static void handleSearchResponse(MapNetworkPayloads.MapSearchResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof RoadMapScreen screen) {
                screen.acceptSearchResults(payload.requestSeq(), payload.dimension(), payload.success(), payload.results());
            }
        });
    }

    private static void handleManualConnect(MapNetworkPayloads.MapManualConnectPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!player.hasPermissions(2)) {
                player.displayClientMessage(Component.translatable("gui.roadweaver.map.manual_connect.denied"), true);
                return;
            }
            if (!RoadWeaverWorlds.supports(player.serverLevel())) return;
            RoadNetworkApi.ensureConnection(player.serverLevel(), payload.from(), payload.to());
        });
    }

    private static boolean canReply(ServerPlayer player, ServerLevel level) {
        return !player.isRemoved() && !player.hasDisconnected()
                && player.serverLevel() == level && MapAccessService.canOpenMap(player);
    }

    private static MapSnapshot buildSnapshot(ServerLevel level,
                                             MapLoadPhase phase,
                                             int minX,
                                             int minZ,
                                             int maxX,
                                             int maxZ) {
        return switch (phase) {
            case STRUCTURES -> MapDataCollector.buildStructuresSnapshot(level, minX, minZ, maxX, maxZ);
            case ROADS -> MapDataCollector.buildRoadsSnapshot(level, minX, minZ, maxX, maxZ);
            case CONNECTIONS -> MapDataCollector.buildConnectionsSnapshot(level, minX, minZ, maxX, maxZ);
        };
    }

    public static void requestSnapshot(int requestSeq,
                                       ResourceLocation dimensionId,
                                       MapLoadPhase phase,
                                       int responseIndex,
                                       int minX,
                                       int minZ,
                                       int maxX,
                                       int maxZ) {
        PacketDistributor.sendToServer(new MapNetworkPayloads.MapRequestRectPayload(
                requestSeq, dimensionId, phase, responseIndex, minX, minZ, maxX, maxZ));
    }

    public static void requestTeleport(int x, int y, int z) {
        PacketDistributor.sendToServer(new MapNetworkPayloads.MapTeleportPayload(x, y, z));
    }

    public static void requestManualConnect(int ax, int az, int bx, int bz) {
        PacketDistributor.sendToServer(new MapNetworkPayloads.MapManualConnectPayload(
                new BlockPos(ax, 0, az), new BlockPos(bx, 0, bz)));
    }

    public static void requestSearch(int requestSeq, ResourceLocation dimensionId, String query) {
        PacketDistributor.sendToServer(new MapNetworkPayloads.MapSearchRequestPayload(requestSeq, dimensionId, query));
    }

    public static void broadcastPatch(ServerPlayer player, ResourceLocation dimensionId, MapSnapshotPatch patch) {
        if (player == null || dimensionId == null || patch == null || patch.isEmpty()) return;
        PacketDistributor.sendToPlayer(player, new MapNetworkPayloads.MapPatchPayload(dimensionId, patch));
    }

    public static void broadcastAutomaticPlanningSampling(ServerPlayer player,
                                                          ResourceLocation dimensionId,
                                                          List<AutomaticPlanningSamplingBounds> bounds) {
        if (player == null || dimensionId == null) return;
        PacketDistributor.sendToPlayer(player,
                new MapNetworkPayloads.MapAutomaticPlanningSamplingPayload(dimensionId, bounds));
    }

    public static void syncMapAccess(ServerPlayer player) {
        if (player != null) {
            PacketDistributor.sendToPlayer(player, new MapNetworkPayloads.MapAccessSyncPayload(MapAccessService.canOpenMap(player)));
        }
    }
}
