package dev.worldadmin.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Opt-in runtime proof of independent road storage and actual native FEATURES placement. */
public final class RoadWeaverSmokeFixture {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("worldadmin_road_smoke", Comparator.comparingLong(ChunkPos::toLong));
    private static final Map<MinecraftServer, Probe> ACTIVE = new ConcurrentHashMap<>();
    private RoadWeaverSmokeFixture() {}
    public record Result(String message, int chunkX, int chunkZ, Set<String> expectedMaterialIds) {
        public Result { expectedMaterialIds = Set.copyOf(expectedMaterialIds); }
    }

    public static CompletableFuture<Result> start(ServerLevel generatedA, ServerLevel untouchedB) {
        if (generatedA == null || untouchedB == null || generatedA == untouchedB) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Road isolation probe needs two distinct loaded worlds"));
        }
        MinecraftServer server = generatedA.getServer();
        if (!server.isSameThread() || untouchedB.getServer() != server) throw new IllegalArgumentException("Run both-world probe on its server thread");
        if (ACTIVE.containsKey(server)) return CompletableFuture.failedFuture(new IllegalStateException("Road probe already active"));
        try {
            Method inspect = Class.forName("net.shiroha233.roadweaver.api.RoadNetworkApi")
                    .getMethod("inspectRegionAsync", ServerLevel.class, int.class, int.class, int.class, int.class);
            Probe probe = new Probe(generatedA, untouchedB, inspection(inspect, generatedA), inspection(inspect, untouchedB));
            ACTIVE.put(server, probe);
            return probe.result;
        } catch (Throwable failure) { return CompletableFuture.failedFuture(unwrap(failure)); }
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Map<String, Object>> inspection(Method method, ServerLevel level) throws ReflectiveOperationException {
        Object result = method.invoke(null, level, -3072, -3072, 3071, 3071);
        if (!(result instanceof CompletableFuture<?> future)) throw new IllegalStateException("Road inspector returned no future");
        return future.thenApply(value -> {
            if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Road inspector returned no diagnostic map");
            return (Map<String, Object>) map;
        });
    }

    public static void tick(MinecraftServer server) {
        Probe probe = ACTIVE.get(server);
        if (probe == null) return;
        try {
            if (System.nanoTime() - probe.started > 600_000_000_000L) throw new IllegalStateException("Road smoke probe timed out");
            if (probe.chunk == null) {
                if (!probe.inspectedA.isDone() || !probe.inspectedB.isDone()) return;
                Map<String, Object> a = probe.inspectedA.getNow(null), b = probe.inspectedB.getNow(null);
                if (a == null || b == null) throw new IllegalStateException("Road inspector returned no result");
                if (!probe.a.dimension().location().toString().equals(a.get("dimension"))
                        || !probe.b.dimension().location().toString().equals(b.get("dimension"))) {
                    throw new IllegalStateException("Road diagnostics resolved the wrong dimension");
                }
                long roadsA = number(a, "roadsInRegion"), roadsB = number(b, "roadsInRegion");
                if (roadsA <= 0) throw new IllegalStateException("No generated road records in planned world; refusing to skip placement proof: " + a);
                if (roadsB != 0 || number(b, "connectionsInWorld") != 0) {
                    throw new IllegalStateException("Road storage/cache leaked into untouched world: " + b);
                }
                if (number(a, "completedConnections") <= 0) throw new IllegalStateException("Roads exist without any completed connection");
                probe.roads = roadsA;
                probe.sample = new BlockPos(Math.toIntExact(number(a, "sampleX")), Math.toIntExact(number(a, "sampleY")), Math.toIntExact(number(a, "sampleZ")));
                addMaterials(probe.materials, a.get("sampleMaterials"));
                addMaterials(probe.materials, a.get("sampleSlabMaterials"));
                if (probe.materials.isEmpty()) throw new IllegalStateException("Road sample has no explicit material palette; cannot prove block placement");
                probe.pos = new ChunkPos(probe.sample);
                probe.a.getChunkSource().addRegionTicket(TICKET, probe.pos, 0, probe.pos, false);
                probe.ticketHeld = true;
                // The inspected road is already persisted. Do not add more planning work here.
                probe.chunk = probe.a.getChunkSource().getChunkFutureMainThread(probe.pos.x, probe.pos.z, ChunkStatus.FULL, true);
                return;
            }
            if (!probe.chunk.isDone()) return;
            ChunkResult<ChunkAccess> result = probe.chunk.getNow(null);
            if (result == null || !result.isSuccess()) throw new IllegalStateException("Native FULL request failed for the road sample");
            ChunkAccess chunk = result.orElse(null);
            if (chunk == null || !chunk.getPos().equals(probe.pos)) throw new IllegalStateException("Wrong road sample chunk returned");
            BlockPos match = null;
            Block matchedBlock = null;
            // Never call ServerLevel.getBlockState/getChunk; scan only this already-completed chunk.
            search: for (int x = Math.max(probe.pos.getMinBlockX(), probe.sample.getX() - 2);
                         x <= Math.min(probe.pos.getMaxBlockX(), probe.sample.getX() + 2); x++) {
                for (int z = Math.max(probe.pos.getMinBlockZ(), probe.sample.getZ() - 2);
                     z <= Math.min(probe.pos.getMaxBlockZ(), probe.sample.getZ() + 2); z++) {
                    for (int y = Math.max(chunk.getMinBuildHeight(), probe.sample.getY() - 4);
                         y <= Math.min(chunk.getMaxBuildHeight() - 1, probe.sample.getY() + 4); y++) {
                        BlockPos position = new BlockPos(x, y, z);
                        Block block = chunk.getBlockState(position).getBlock();
                        if (probe.materials.contains(block)) { match = position; matchedBlock = block; break search; }
                    }
                }
            }
            if (match == null) throw new IllegalStateException("Planned road has no matching placed block near " + probe.sample
                    + "; expected " + probe.materials.stream().map(BuiltInRegistries.BLOCK::getKey).toList());
            release(probe);
            ACTIVE.remove(server, probe);
            probe.result.complete(new Result("RoadWeaver: " + probe.roads + " road records in " + probe.a.dimension().location()
                    + ", zero roads/connections in untouched " + probe.b.dimension().location() + "; native FULL placed "
                    + BuiltInRegistries.BLOCK.getKey(matchedBlock) + " at " + match.toShortString(), probe.pos.x, probe.pos.z,
                    probe.materials.stream().map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                            .collect(java.util.stream.Collectors.toUnmodifiableSet())));
        } catch (Throwable failure) {
            release(probe);
            ACTIVE.remove(server, probe);
            probe.result.completeExceptionally(unwrap(failure));
        }
    }

    private static long number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw new IllegalStateException("Missing road diagnostic field " + key + ": " + map);
        return number.longValue();
    }
    private static void addMaterials(Set<Block> blocks, Object value) {
        if (value instanceof Collection<?> values) for (Object entry : values) {
            if (entry instanceof BlockState state) blocks.add(state.getBlock());
        }
    }
    private static void release(Probe probe) {
        if (probe.ticketHeld) {
            probe.a.getChunkSource().removeRegionTicket(TICKET, probe.pos, 0, probe.pos, false);
            probe.ticketHeld = false;
        }
    }
    private static Throwable unwrap(Throwable failure) {
        return failure instanceof InvocationTargetException invocation && invocation.getCause() != null ? invocation.getCause() : failure;
    }
    private static final class Probe {
        final ServerLevel a, b;
        final CompletableFuture<Map<String, Object>> inspectedA, inspectedB;
        final CompletableFuture<Result> result = new CompletableFuture<>();
        final Set<Block> materials = new HashSet<>();
        final long started = System.nanoTime();
        CompletableFuture<ChunkResult<ChunkAccess>> chunk;
        ChunkPos pos;
        BlockPos sample;
        boolean ticketHeld;
        long roads;
        Probe(ServerLevel a, ServerLevel b, CompletableFuture<Map<String, Object>> inspectedA, CompletableFuture<Map<String, Object>> inspectedB) {
            this.a = a; this.b = b; this.inspectedA = inspectedA; this.inspectedB = inspectedB;
        }
    }
}
