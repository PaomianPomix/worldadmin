package net.shiroha233.roadweaver.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.core.model.StructureLocationData;
import net.shiroha233.roadweaver.generation.RoadGenerationService;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.persistence.WorldDataProvider;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import net.shiroha233.roadweaver.planning.RoadPlanningService;

import java.util.ArrayList;
import java.util.List;

public final class RoadNetworkApi {
    private RoadNetworkApi() {
    }

    public static int apiVersion() { return 1; }

    public static java.util.concurrent.CompletableFuture<Void> prepareRegionAsync(ServerLevel level,
            int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        return RegionPreparation.prepare(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
    }

    /** Explicit retry of failed edges influencing a region; returns the number reset. */
    public static java.util.concurrent.CompletableFuture<Integer> retryFailedRegion(ServerLevel level,
            int minX, int minZ, int maxX, int maxZ) {
        if (!RoadWeaverWorlds.supports(level) || minX > maxX || minZ > maxZ) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Invalid road retry region"));
        }
        return java.util.concurrent.CompletableFuture.supplyAsync(
                () -> RoadGenerationService.retryFailedRegion(level, minX, minZ, maxX, maxZ), level.getServer())
                .thenCompose(future -> future);
    }

    /** Diagnostic read for smoke tests/admin tools; disk reads stay on the bounded worker. */
    public static java.util.concurrent.CompletableFuture<java.util.Map<String, Object>> inspectRegionAsync(
            ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        if (!RoadWeaverWorlds.supports(level)) return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalArgumentException("Unsupported RoadWeaver world"));
        if (minX > maxX || minZ > maxZ || (long) maxX-minX > 8192 || (long) maxZ-minZ > 8192) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Diagnostic region must be bounded"));
        }
        return net.shiroha233.roadweaver.runtime.LevelTaskTracker.track(level,
                () -> StructureFileStorage.ensureLoadedAsync(level).thenCompose(loaded -> net.shiroha233.roadweaver.runtime.ThreadPoolManager.supplyAsync(
                        net.shiroha233.roadweaver.runtime.ThreadPoolManager.TaskRole.MAP, () -> {
            var values = new java.util.LinkedHashMap<String, Object>();
            var connections = WorldDataProvider.getInstance().getStructureConnections(level);
            var roads = new ArrayList<>(net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ));
            values.put("dimension", level.dimension().location().toString());
            values.put("structuresInRegion", StructureFileStorage.queryRect(level, minX, minZ, maxX, maxZ).size());
            values.put("connectionsInWorld", connections.size());
            values.put("completedConnections", connections.stream().filter(c -> c.status() == ConnectionStatus.COMPLETED).count());
            values.put("failedConnections", connections.stream().filter(c -> c.status() == ConnectionStatus.FAILED).count());
            values.put("roadsInRegion", roads.size());
            values.put("segmentsInRegionRoads", roads.stream().mapToLong(r -> r.roadSegmentList().size()).sum());
            roads.sort(java.util.Comparator.comparingInt(r -> r.materials().isEmpty() ? 1 : 0));
            outer: for (var road : roads) {
                for (int step=0; step<road.roadSegmentList().size(); step++) {
                    int i = (road.roadSegmentList().size()/2 + step) % road.roadSegmentList().size();
                    BlockPos pos = road.roadSegmentList().get(i).middlePos();
                    if (pos.getX()<minX || pos.getX()>maxX || pos.getZ()<minZ || pos.getZ()>maxZ) continue;
                    if ((pos.getX() & 15) < 2 || (pos.getX() & 15) > 13 || (pos.getZ() & 15) < 2 || (pos.getZ() & 15) > 13) continue;
                    int y = road.targetY()!=null && i<road.targetY().size() ? road.targetY().get(i) : pos.getY();
                    values.put("sampleX", pos.getX()); values.put("sampleY", y); values.put("sampleZ", pos.getZ());
                    values.put("sampleMaterials", List.copyOf(road.materials()));
                    values.put("sampleSlabMaterials", List.copyOf(road.slabMaterials()));
                    break outer;
                }
            }
            return java.util.Map.copyOf(values);
        })));
    }

    public static boolean hasPendingWork(ServerLevel level) {
        return RegionPreparation.hasPending(level) || RoadPlanningService.hasPendingWork(level)
                || StructureFileStorage.hasPendingWork(level)
                || RoadGenerationService.hasPendingWork(level)
                || net.shiroha233.roadweaver.runtime.LevelTaskTracker.hasPendingWork(level)
                || net.shiroha233.roadweaver.features.path.pathlogic.core.StructureAvoidanceService.hasPendingWork(level);
    }

    public static void releaseWorld(ServerLevel level) {
        if (level == null) return;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Release worlds on the server thread");
        if (hasPendingWork(level)) throw new IllegalStateException("RoadWeaver work must drain before unloading this world");
        net.shiroha233.roadweaver.runtime.CacheManager.onDimensionUnload(level);
        RegionPreparation.clear(level);
    }

    /** Lifecycle cleanup called by CacheManager, after its shared work has drained. */
    public static void forgetPreparedWorld(ServerLevel level) { RegionPreparation.clear(level); }

    /** Shutdown invalidates preparation leases; callers receive an exceptional future. */
    public static void onServerStopping() { RegionPreparation.shutdown(); }

    public static void registerStructureEndpoint(ServerLevel level, BlockPos pos) {
        registerStructureEndpoint(level, pos, null, false);
    }

    public static void registerStructureEndpoint(ServerLevel level, BlockPos pos, boolean autoConnect) {
        registerStructureEndpoint(level, pos, null, autoConnect);
    }

    public static void registerStructureEndpoint(ServerLevel level, BlockPos pos, String structureId,
            boolean autoConnect) {
        if (level == null || pos == null || !RoadWeaverWorlds.supports(level)) return;
        if (deferUntilReady(level, () -> registerStructureEndpoint(level, pos, structureId, autoConnect))) return;
        synchronized (StructureFileStorage.class) {
        if (level == null || pos == null || !RoadWeaverWorlds.supports(level))
            return;
        WorldDataProvider provider = WorldDataProvider.getInstance();
        StructureLocationData existing = provider.getStructureLocations(level);
        List<BlockPos> locations = existing != null ? new ArrayList<>(existing.structureLocations())
                : new ArrayList<>();
        List<StructureInfo> infos = existing != null ? new ArrayList<>(existing.structureInfos())
                : new ArrayList<>();

        if (structureId != null && !structureId.isEmpty()) {
            StructureInfo info = new StructureInfo(pos, structureId);
            infos.add(info);
            if (!locations.contains(pos)) {
                locations.add(pos);
            }
        } else {
            if (!locations.contains(pos)) {
                locations.add(pos);
            }
        }

        StructureLocationData updated = new StructureLocationData(locations, infos);
        provider.setStructureLocations(level, updated);

        String id = (structureId != null && !structureId.isEmpty()) ? structureId : "unknown";
        StructureFileStorage.addStructures(
                level,
                java.util.List.of(new StructureInfo(new BlockPos(pos.getX(), 0, pos.getZ()), id)),
                StructureFileStorage.SOURCE_MANUAL
        );
        }
    }

    public static void ensureConnection(ServerLevel level, BlockPos from, BlockPos to) {
        ensureConnection(level, from, to, false);
    }

    public static void ensureConnection(ServerLevel level, BlockPos from, BlockPos to, boolean generateImmediately) {
        if (level == null || from == null || to == null || !RoadWeaverWorlds.supports(level)) return;
        if (deferUntilReady(level, () -> ensureConnection(level, from, to, generateImmediately))) return;
        synchronized (StructureFileStorage.class) {
        if (level == null || from == null || to == null || !RoadWeaverWorlds.supports(level))
            return;
        if (from.equals(to))
            return;

        WorldDataProvider provider = WorldDataProvider.getInstance();
        StructureLocationData existing = provider.getStructureLocations(level);
        List<BlockPos> locations = existing != null ? new ArrayList<>(existing.structureLocations())
                : new ArrayList<>();
        List<StructureInfo> infos = existing != null ? new ArrayList<>(existing.structureInfos())
                : new ArrayList<>();

        boolean changed = false;
        if (!locations.contains(from)) {
            locations.add(from);
            changed = true;
        }
        if (!locations.contains(to)) {
            locations.add(to);
            changed = true;
        }
        if (changed) {
            StructureLocationData updated = new StructureLocationData(locations, infos);
            provider.setStructureLocations(level, updated);
        }

        StructureFileStorage.addStructures(
                level,
                java.util.List.of(
                        new StructureInfo(new BlockPos(from.getX(), 0, from.getZ()), "unknown"),
                        new StructureInfo(new BlockPos(to.getX(), 0, to.getZ()), "unknown")
                ),
                StructureFileStorage.SOURCE_MANUAL
        );

        List<StructureConnection> existingConns = provider.getStructureConnections(level);
        List<StructureConnection> list = existingConns != null ? new ArrayList<>(existingConns)
                : new ArrayList<>();
        boolean exists = false;
        for (StructureConnection c : list) {
            if (sameEdge(c, from, to)) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            StructureConnection connection = new StructureConnection(from, to, ConnectionStatus.PLANNED);
            list.add(connection);
            provider.setStructureConnections(level, list);
            MapPatchService.publishConnection(level, connection);
        }

        if (generateImmediately) {
            // Direct generateTask may sample terrain for seconds; never invoke it from the caller thread.
            RoadGenerationService.prepareRegionAsync(level,
                    Math.min(from.getX(), to.getX()), Math.min(from.getZ(), to.getZ()),
                    Math.max(from.getX(), to.getX()), Math.max(from.getZ(), to.getZ()));
        }
        }
    }
    public static void planRegion(ServerLevel level, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        if (level == null || !RoadWeaverWorlds.supports(level))
            return;
        RoadPlanningService.planRectAsync(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
    }

    private static boolean sameEdge(StructureConnection c, BlockPos a, BlockPos b) {
        BlockPos af = c.from();
        BlockPos at = c.to();
        return (af.equals(a) && at.equals(b)) || (af.equals(b) && at.equals(a));
    }

    private static boolean deferUntilReady(ServerLevel level, Runnable operation) {
        if (level.getServer().isSameThread() && StructureFileStorage.isReady(level)) return false;
        net.shiroha233.roadweaver.runtime.LevelTaskTracker.track(level,
                () -> StructureFileStorage.ensureLoadedAsync(level).thenCompose(ignored ->
                        net.shiroha233.roadweaver.runtime.LevelTaskTracker.runOnServer(level, operation)))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) System.getLogger(RoadNetworkApi.class.getName()).log(
                            System.Logger.Level.WARNING, "Deferred RoadWeaver API operation failed in " + level.dimension().location(), failure);
                });
        return true;
    }
}
