package dev.worldadmin.gametest;

import dev.worldadmin.compat.DistantHorizonsRoadGate;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

/** Actual DH 3.3.3 runtime probe, excluded from the release JAR with the other smoke helpers. */
public final class DistantHorizonsRoadFixture {
    private static final String DH = "com.seibel.distanthorizons.";
    private static final Map<MinecraftServer, Probe> ACTIVE = new ConcurrentHashMap<>();
    private DistantHorizonsRoadFixture() {}

    public static CompletableFuture<String> start(ServerLevel level, int minChunkX, int minChunkZ) {
        return start(level, minChunkX, minChunkZ, Set.of());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static CompletableFuture<String> start(ServerLevel level, int minChunkX, int minChunkZ, Set<String> expectedMaterialIds) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("DH smoke probe must start on the server thread");
        if ((minChunkX & 3) != 0 || (minChunkZ & 3) != 0) throw new IllegalArgumentException("DH detail-0 area must align to four chunks");
        if (!level.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Probe requires an overworld dimension type"));
        }
        if (ACTIVE.containsKey(level.getServer())) return CompletableFuture.failedFuture(new IllegalStateException("DH smoke probe already active"));
        Object generator = null;
        Object source = null;
        try {
            Class<?> wrapperClass = Class.forName(DH + "common.wrappers.world.ServerLevelWrapper_neoforge");
            Object wrapper = wrapperClass.getMethod("getWrapper", ServerLevel.class).invoke(null, level);
            if (wrapperClass.getMethod("getWrappedMcObject").invoke(wrapper) != level) {
                throw new IllegalStateException("DH wrapper does not use the actual managed ServerLevel");
            }
            Object dhLevel = wrapperClass.getMethod("getDhLevel").invoke(wrapper);
            if (dhLevel == null) throw new IllegalStateException("DH LevelEvent.Load did not register the managed dimension");
            Class<?> generatorClass = Class.forName(DH + "core.generation.DhWorldGenerator");
            Method allowed = generatorClass.getDeclaredMethod("chunkGenAllowedForDetailLevel", byte.class);
            allowed.setAccessible(true);
            if (!Boolean.TRUE.equals(allowed.invoke(null, (byte) 0))) {
                throw new IllegalStateException("DH chunk generation is disabled; release the normal pregeneration lease before this probe");
            }
            generator = generatorClass.getConstructor(Class.forName(DH + "core.level.IDhServerLevel")).newInstance(dhLevel);
            long section = (long) Class.forName(DH + "core.pos.DhSectionPos")
                    .getMethod("encode", byte.class, int.class, int.class)
                    .invoke(null, (byte) 6, Math.floorDiv(minChunkX, 4), Math.floorDiv(minChunkZ, 4));
            Class<?> sourceClass = Class.forName(DH + "core.dataObjects.fullData.sources.FullDataSourceV2");
            source = sourceClass.getMethod("createEmpty", long.class).invoke(null, section);
            ExecutorService executor = (ExecutorService) Class.forName(DH + "core.util.threading.ThreadPoolUtil")
                    .getMethod("getWorldGenExecutor").invoke(null);
            if (executor == null || executor.isShutdown()) throw new IllegalStateException("DH worldgen executor is unavailable");
            Class<? extends Enum> modeClass = (Class<? extends Enum>) Class.forName(DH + "api.enums.worldGeneration.EDhApiDistantGeneratorMode");
            Object mode = Enum.valueOf(modeClass, "FEATURES");
            Method generate = generatorClass.getMethod("generateLod", int.class, int.class, int.class, int.class,
                    byte.class, Class.forName(DH + "api.objects.data.IDhApiFullDataSource"), modeClass,
                    ExecutorService.class, Consumer.class);
            Probe probe = new Probe(level, generator, source, DistantHorizonsRoadGate.interceptedCount(),
                    DistantHorizonsRoadGate.delegatedCount(), DistantHorizonsRoadGate.completedCount(), executor,
                    Set.copyOf(expectedMaterialIds));
            ACTIVE.put(level.getServer(), probe);
            try {
                Object result = generate.invoke(generator, minChunkX, minChunkZ, Math.floorDiv(minChunkX, 4),
                        Math.floorDiv(minChunkZ, 4), (byte) 0, source, mode, executor, (Consumer<Object>) ignored -> {});
                if (!(result instanceof CompletableFuture<?> future)) throw new IllegalStateException("DH returned no generation future");
                probe.generation = future;
                return probe.result;
            } catch (Throwable failure) {
                // Invocation failed before returning an asynchronous task; no delegate can use the source.
                ACTIVE.remove(level.getServer(), probe);
                close(generator, source);
                return CompletableFuture.failedFuture(unwrap(failure));
            }
        } catch (Throwable failure) {
            close(generator, source);
            return CompletableFuture.failedFuture(unwrap(failure));
        }
    }

    /** Root smoke harness calls this each tick; never waits for DH or cancels its pooled-source owner. */
    public static void tick(MinecraftServer server) {
        Probe probe = ACTIVE.get(server);
        if (probe == null || probe.generation == null) return;
        if (!probe.generation.isDone()) {
            try { probe.generator.getClass().getMethod("preGeneratorTaskStart").invoke(probe.generator); }
            catch (Throwable failure) { probe.housekeepingFailure = unwrap(failure); }
            return;
        }
        if (probe.verification == null) {
            // The pooled source is now immutable. Decode it on DH's executor so inspecting
            // all encoded vertical spans cannot turn the smoke test into a slow game tick.
            try { probe.verification = CompletableFuture.supplyAsync(() -> verifySource(probe), probe.executor); }
            catch (RuntimeException failure) { probe.verification = CompletableFuture.failedFuture(failure); }
            return;
        }
        if (!probe.verification.isDone()) return;
        ACTIVE.remove(server, probe);
        String report = null;
        Throwable problem = null;
        try {
            report = probe.verification.getNow(null);
        } catch (Throwable failure) {
            problem = unwrap(failure);
        } finally {
            close(probe.generator, probe.source);
        }
        if (problem == null) probe.result.complete(report); else probe.result.completeExceptionally(problem);
    }

    private static String verifySource(Probe probe) {
        try {
            if (probe.generation.isCompletedExceptionally()) {
                probe.generation.handle((ignored, failure) -> { throw new IllegalStateException("DH generation failed", failure); }).getNow(null);
            }
            if (probe.housekeepingFailure != null) throw new IllegalStateException("DH housekeeping failed", probe.housekeepingFailure);
            long intercepted = DistantHorizonsRoadGate.interceptedCount() - probe.intercepted;
            long delegated = DistantHorizonsRoadGate.delegatedCount() - probe.delegated;
            long completed = DistantHorizonsRoadGate.completedCount() - probe.completed;
            if (intercepted < 1 || delegated < 1 || completed < 1) {
                throw new IllegalStateException("Actual DH call did not traverse the planning mixin: " + intercepted + "/" + delegated + "/" + completed);
            }
            Object[] columns = (Object[]) probe.source.getClass().getField("dataPoints").get(probe.source);
            int nonempty = 0;
            for (Object column : columns) if (column instanceof Collection<?> values && !values.isEmpty()) nonempty++;
            if (nonempty == 0 || probe.source.getClass().getField("isEmpty").getBoolean(probe.source)) {
                throw new IllegalStateException("DH returned an empty LOD data source");
            }
            String roadProof = inspectRoadMaterial(probe, columns);
            return "DH FEATURES generated " + nonempty + " populated LOD columns in "
                    + probe.level.dimension().location() + "; intercepted/delegated/completed=" + intercepted + "/" + delegated + "/" + completed
                    + roadProof;
        } catch (Throwable failure) {
            throw new CompletionException(unwrap(failure));
        }
    }

    /** Resolve IDs from actual encoded data points, not merely entries present in DH's palette. */
    private static String inspectRoadMaterial(Probe probe, Object[] columns) throws ReflectiveOperationException {
        if (probe.expectedMaterials.isEmpty()) return "";
        Object mapping = probe.source.getClass().getField("mapping").get(probe.source);
        Method getId = Class.forName(DH + "core.util.FullDataPointUtil").getMethod("getId", long.class);
        Method getBlock = mapping.getClass().getMethod("getBlockStateWrapper", int.class);
        Method getWrapped = Class.forName(DH + "api.interfaces.IDhApiUnsafeWrapper").getMethod("getWrappedMcObject");
        Method isAir = Class.forName(DH + "api.interfaces.block.IDhApiBlockStateWrapper").getMethod("isAir");
        Map<Integer, String> resolvedIds = new HashMap<>();
        Set<String> found = new HashSet<>();
        int roadSpans = 0;
        int roadColumns = 0;
        for (Object column : columns) {
            if (!(column instanceof Collection<?> values) || values.isEmpty()) continue;
            boolean containsRoad = false;
            for (Object value : values) {
                if (!(value instanceof Number encoded)) throw new IllegalStateException("Unexpected DH encoded data point type");
                int id = ((Number) getId.invoke(null, encoded.longValue())).intValue();
                String blockId = resolvedIds.get(id);
                if (blockId == null) {
                    Object blockWrapper = getBlock.invoke(mapping, id);
                    Object raw = getWrapped.invoke(blockWrapper);
                    if (raw instanceof BlockState state) blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    else if (Boolean.TRUE.equals(isAir.invoke(blockWrapper))) blockId = "minecraft:air";
                    else throw new IllegalStateException("DH material wrapper did not expose a Minecraft BlockState for palette ID " + id);
                    resolvedIds.put(id, blockId);
                }
                if (probe.expectedMaterials.contains(blockId)) {
                    roadSpans++;
                    containsRoad = true;
                    found.add(blockId);
                }
            }
            if (containsRoad) roadColumns++;
        }
        if (roadSpans == 0) {
            throw new IllegalStateException("DH LOD has terrain but no expected road material; expected=" + probe.expectedMaterials
                    + ", encoded block IDs=" + new java.util.TreeSet<>(resolvedIds.values()));
        }
        return "; encoded road materials=" + new java.util.TreeSet<>(found) + " in " + roadColumns
                + " LOD columns (" + roadSpans + " vertical spans)";
    }
    public static boolean active(MinecraftServer server) { return ACTIVE.containsKey(server); }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof InvocationTargetException invocation && invocation.getCause() != null ? invocation.getCause() : failure;
    }
    private static void close(Object generator, Object source) {
        if (source != null) {
            try { source.getClass().getMethod("recordLastSeen").invoke(source); } catch (ReflectiveOperationException ignored) {}
            try { source.getClass().getMethod("close").invoke(source); } catch (ReflectiveOperationException ignored) {}
        }
        if (generator != null) try { generator.getClass().getMethod("close").invoke(generator); } catch (ReflectiveOperationException ignored) {}
    }
    private static final class Probe {
        final ServerLevel level;
        final Object generator, source;
        final long intercepted, delegated, completed;
        final ExecutorService executor;
        final Set<String> expectedMaterials;
        final CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture<?> generation;
        CompletableFuture<String> verification;
        Throwable housekeepingFailure;
        Probe(ServerLevel level, Object generator, Object source, long intercepted, long delegated, long completed,
              ExecutorService executor, Set<String> expectedMaterials) {
            this.level = level; this.generator = generator; this.source = source;
            this.intercepted = intercepted; this.delegated = delegated; this.completed = completed;
            this.executor = executor; this.expectedMaterials = expectedMaterials;
        }
    }
}
