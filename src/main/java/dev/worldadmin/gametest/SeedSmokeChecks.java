package dev.worldadmin.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldadmin.WorldAdmin;
import dev.worldadmin.compat.DistantHorizonsBridge;
import dev.worldadmin.compat.DistantHorizonsRoadGate;
import dev.worldadmin.compat.RoadWeaverBridge;
import dev.worldadmin.world.WorldManager;
import dev.worldadmin.world.seed.WorldSeedLock;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Dedicated seed checks against the installed overworld generator; excluded from release JARs. */
@EventBusSubscriber(modid = WorldAdmin.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class SeedSmokeChecks {
    private static final boolean ENABLED = Boolean.getBoolean("worldadmin.seedSmoke");
    private static final long SEED = -8123456789012345678L;
    private static final ChunkPos SAVED_CHUNK = new ChunkPos(7, 9);
    private static final ChunkPos NEW_CHUNK = new ChunkPos(57, -61);
    private static final TicketType<ChunkPos> TICKET = TicketType.create("worldadmin_seed_smoke",
            Comparator.comparingLong(ChunkPos::toLong));
    private static final String DH = "com.seibel.distanthorizons.";
    private static final Capture OUTPUT = new Capture();
    private static final List<String> CHECKS = new ArrayList<>();
    private static final List<World> WORLDS = new ArrayList<>();
    private static final List<String> LEGACY_WORLDS = new ArrayList<>();
    private static final LinkedHashMap<String, Object> DETAILS = new LinkedHashMap<>();
    private static DistantHorizonsBridge dhLease;
    private static long started;
    private static long serverSeed;
    private static long savedFingerprint;
    private static long freshFingerprint;
    private static int stage;
    private static int cleanupIndex;
    private static boolean ended;
    private static Throwable startupFailure;

    private SeedSmokeChecks() {}

    @SubscribeEvent public static void beforeStarted(ServerAboutToStartEvent event) {
        if (!ENABLED) return;
        try {
            Path manifestPath = event.getServer().getWorldPath(LevelResource.ROOT).resolve("worldadmin/worlds.json");
            if (!Files.exists(manifestPath)) return;
            JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
            if (manifest.get("version").getAsInt() == 1) {
                for (JsonElement element : manifest.getAsJsonArray("worlds")) {
                    LEGACY_WORLDS.add(element.getAsJsonObject().get("name").getAsString());
                }
            }
        } catch (Throwable failure) {
            startupFailure = failure;
        }
    }

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!ENABLED) return;
        started = System.nanoTime();
        serverSeed = event.getServer().getWorldData().worldGenOptions().seed();
        String prefix = "seed_smoke_" + UUID.randomUUID().toString().replace("-", "");
        WORLDS.add(new World(prefix + "_a", SEED));
        WORLDS.add(new World(prefix + "_b", 0L));
        WORLDS.add(new World(prefix + "_c", SEED));
        dhLease = new DistantHorizonsBridge();
        DETAILS.put("serverSeed", Long.toString(serverSeed));
        DETAILS.put("scope", "Native FULL chunk seed determinism and DH generation parameters; "
                + "RoadWeaver planning/road blocks and rendered DH LOD are not exercised by this fixture.");
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (!ENABLED || started == 0 || ended) return;
        MinecraftServer server = event.getServer();
        try {
            if (System.nanoTime() - started > 900_000_000_000L) {
                throw new IllegalStateException("Seed smoke timed out at stage " + stage);
            }
            World first = WORLDS.get(0);
            World control = WORLDS.get(2);
            switch (stage) {
                case 0 -> {
                    if (System.nanoTime() - started < 5_000_000_000L) return;
                    if (startupFailure != null) throw new IllegalStateException("Could not snapshot legacy manifest", startupFailure);
                    verifyLegacyMigration(server);
                    check(dhLease.acquire(), "Optional DH generation temporarily suspended for native chunk checks");
                    stage++;
                }
                case 1, 2, 3 -> {
                    World world = WORLDS.get(stage - 1);
                    check(command(server, "wa create " + world.name + " minecraft:overworld seed " + world.seed) == 1,
                            "Created live overworld template with explicit seed " + world.seed);
                    world.level = server.getLevel(WorldManager.dimensionKey(world.name));
                    check(world.level != null && world.level.getChunkSource().getLoadedChunksCount() == 0,
                            "Seeded creation generated no spawn chunks: " + world.name);
                    verifyWorld(server, world);
                    stage++;
                }
                case 4 -> {
                    check(Arrays.equals(noiseFingerprint(first.level.getChunkSource().randomState()),
                                    noiseFingerprint(control.level.getChunkSource().randomState())),
                            "Equal seeds produce equal terrain, biome-climate and aquifer noise");
                    check(!Arrays.equals(noiseFingerprint(first.level.getChunkSource().randomState()),
                                    noiseFingerprint(WORLDS.get(1).level.getChunkSource().randomState())),
                            "Zero and negative seeds produce distinct noise");
                    request(first, SAVED_CHUNK);
                    check(!first.future.isDone(), "Fresh FULL request returned before generation completed");
                    stage++;
                }
                case 5 -> {
                    if (!first.future.isDone()) return;
                    savedFingerprint = fingerprint(completed(first));
                    release(first);
                    check(true, "Generated native FULL chunk before reload");
                    stage++;
                }
                case 6 -> {
                    if (busy(first)) return;
                    ServerLevel original = first.level;
                    unload(server, first);
                    querySeed(server, first);
                    check(new WorldManager(server).seed(first.name) == first.seed,
                            "Fresh manifest reader recovers exact 64-bit seed while world is unloaded");
                    check(command(server, "wa load " + first.name) == 1, "Reloaded persisted seed world");
                    first.level = server.getLevel(WorldManager.dimensionKey(first.name));
                    check(first.level != null && first.level != original, "Reload constructed a new ServerLevel");
                    verifyWorld(server, first);
                    request(first, SAVED_CHUNK);
                    stage++;
                }
                case 7 -> {
                    if (!first.future.isDone()) return;
                    check(fingerprint(completed(first)) == savedFingerprint,
                            "Persisted FULL chunk retains all block states and biomes after reload");
                    release(first);
                    stage++;
                }
                case 8 -> {
                    if (busy(first)) return;
                    request(first, NEW_CHUNK);
                    stage++;
                }
                case 9 -> {
                    if (!first.future.isDone()) return;
                    freshFingerprint = fingerprint(completed(first));
                    release(first);
                    stage++;
                }
                case 10 -> {
                    if (busy(first)) return;
                    request(control, NEW_CHUNK);
                    stage++;
                }
                case 11 -> {
                    if (!control.future.isDone()) return;
                    check(fingerprint(completed(control)) == freshFingerprint,
                            "New FULL terrain after reload matches fresh same-seed control, including blocks and biomes");
                    release(control);
                    DETAILS.put("savedChunkFingerprint", Long.toUnsignedString(savedFingerprint));
                    DETAILS.put("newChunkFingerprint", Long.toUnsignedString(freshFingerprint));
                    stage++;
                }
                case 12 -> {
                    if (cleanupIndex < WORLDS.size()) {
                        World world = WORLDS.get(cleanupIndex);
                        if (busy(world)) return;
                        unload(server, world);
                        querySeed(server, world);
                        cleanupIndex++;
                        return;
                    }
                    check(server.overworld().getSeed() == serverSeed
                                    && server.getWorldData().worldGenOptions().seed() == serverSeed,
                            "All seed operations preserved the server's global seed");
                    finish(server, null);
                }
                default -> throw new IllegalStateException("Unknown seed smoke stage " + stage);
            }
        } catch (Throwable failure) {
            finish(server, failure);
        }
    }

    private static void verifyWorld(MinecraftServer server, World world) throws Exception {
        ServerLevel level = world.level;
        check(level.getSeed() == world.seed, "Level exposes exact seed: " + world.name);
        check(level.getChunkSource().getGeneratorState().getLevelSeed() == world.seed,
                "Structure placement uses independent seed: " + world.name);
        Object structureCheck = field(ServerLevel.class, "structureCheck").get(level);
        check(field(structureCheck.getClass(), "seed").getLong(structureCheck) == world.seed,
                "Native StructureCheck uses independent seed: " + world.name);
        check(field(BiomeManager.class, "biomeZoomSeed").getLong(level.getBiomeManager()) == BiomeManager.obfuscateSeed(world.seed),
                "Biome zoom uses independent seed: " + world.name);
        check(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator,
                "Live overworld template uses a real noise generator");
        NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        NoiseBasedChunkGenerator source = (NoiseBasedChunkGenerator) server.overworld().getChunkSource().getGenerator();
        check(generator.generatorSettings().equals(source.generatorSettings()),
                "Cloned world retains live registered noise settings");
        RandomState expected = RandomState.create(generator.generatorSettings().value(),
                level.registryAccess().lookupOrThrow(Registries.NOISE), world.seed);
        check(Arrays.equals(noiseFingerprint(expected), noiseFingerprint(level.getChunkSource().randomState())),
                "Terrain, biome-climate and aquifer noise match independent-seed oracle: " + world.name);
        check(server.getWorldData().worldGenOptions().seed() == serverSeed && server.overworld().getSeed() == serverSeed,
                "Managed world initialization preserves global seed");
        querySeed(server, world);
        verifyManifest(server, world);
        if (ModList.get().isLoaded("distanthorizons")) verifyDh(world);
        if (ModList.get().isLoaded("roadweaver")) verifyRoadFingerprint(world, generator);
        DETAILS.put("noiseSettings", generator.generatorSettings().unwrapKey().orElseThrow().location().toString());
        DETAILS.put("worldHeight", level.getHeight());
    }

    private static void verifyDh(World world) throws Exception {
        Class<?> wrapperClass = Class.forName(DH + "common.wrappers.world.ServerLevelWrapper_neoforge");
        Object wrapper = wrapperClass.getMethod("getWrapper", ServerLevel.class).invoke(null, world.level);
        Object dhLevel = wrapperClass.getMethod("getDhLevel").invoke(wrapper);
        check(dhLevel != null, "DH registered seeded world: " + world.name);
        Class<?> globalClass = Class.forName(DH + "common.wrappers.worldGeneration.params.GlobalWorldGenParams_neoforge");
        Object params = globalClass.getConstructor(Class.forName(DH + "core.level.IDhServerLevel")).newInstance(dhLevel);
        WorldOptions options = (WorldOptions) globalClass.getField("worldOptions").get(params);
        check(globalClass.getField("worldSeed").getLong(params) == world.seed && options.seed() == world.seed,
                "DH global worldSeed and WorldOptions use independent seed: " + world.name);
        check(globalClass.getField("randomState").get(params) == world.level.getChunkSource().randomState(),
                "DH uses the actual world's terrain noise state: " + world.name);
        check(options.generateStructures() == world.level.getServer().getWorldData().worldGenOptions().generateStructures(),
                "DH preserves server structure generation flag");
        Class<?> threadClass = Class.forName(DH + "common.wrappers.worldGeneration.params.ThreadWorldGenParams_neoforge");
        Object thread = threadClass.getMethod("getOrMake", globalClass).invoke(null, params);
        Object structureCheck = threadClass.getField("structCheck").get(thread);
        check(field(structureCheck.getClass(), "seed").getLong(structureCheck) == world.seed,
                "DH thread StructureCheck uses independent seed: " + world.name);
    }

    private static void verifyRoadFingerprint(World world, NoiseBasedChunkGenerator generator) throws Exception {
        Class<?> type = Class.forName("net.shiroha233.roadweaver.persistence.WorldgenFingerprintService");
        Object actual = type.getMethod("forLevel", ServerLevel.class).invoke(null, world.level);
        var create = type.getMethod("create", ResourceLocation.class, long.class, String.class,
                HolderLookup.Provider.class, Holder.class, int.class, int.class);
        String identity = (String) type.getMethod("generatorIdentity", ChunkGenerator.class).invoke(null, generator);
        int schema = type.getField("CURRENT_SCHEMA_VERSION").getInt(null);
        int dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        Object expected = create.invoke(null, world.level.dimension().location(), world.seed, identity,
                world.level.registryAccess(), generator.generatorSettings(), dataVersion, schema);
        Object wrongSeed = create.invoke(null, world.level.dimension().location(), world.seed ^ 0x527f20a41bd9e3L, identity,
                world.level.registryAccess(), generator.generatorSettings(), dataVersion, schema);
        check(actual.equals(expected) && !actual.equals(wrongSeed),
                "RoadWeaver sample-storage fingerprint includes exact independent seed: " + world.name);
        if (world.roadFingerprint != null) check(world.roadFingerprint.equals(actual),
                "RoadWeaver sample-storage fingerprint is stable across reload");
        world.roadFingerprint = actual;
    }

    private static void verifyManifest(MinecraftServer server, World world) throws Exception {
        JsonObject manifest = JsonParser.parseString(Files.readString(server.getWorldPath(LevelResource.ROOT)
                .resolve("worldadmin/worlds.json"))).getAsJsonObject();
        JsonObject found = null;
        for (JsonElement element : manifest.getAsJsonArray("worlds")) {
            JsonObject candidate = element.getAsJsonObject();
            if (candidate.get("name").getAsString().equals(world.name)) found = candidate;
        }
        check(found != null && Long.toString(world.seed).equals(found.get("seed").getAsString()),
                "Manifest preserves exact 64-bit seed: " + world.name);
        JsonObject lock = JsonParser.parseString(Files.readString(server.storageSource.getDimensionPath(world.level.dimension())
                .resolve(WorldSeedLock.FILE_NAME))).getAsJsonObject();
        check(Long.toString(world.seed).equals(lock.get("seed").getAsString()),
                "Immutable dimension lock matches configured seed: " + world.name);
    }

    private static void verifyLegacyMigration(MinecraftServer server) throws Exception {
        DETAILS.put("legacyV1WorldCount", LEGACY_WORLDS.size());
        if (LEGACY_WORLDS.isEmpty()) return;
        JsonObject manifest = JsonParser.parseString(Files.readString(server.getWorldPath(LevelResource.ROOT)
                .resolve("worldadmin/worlds.json"))).getAsJsonObject();
        check(manifest.get("version").getAsInt() == 2, "Legacy manifest upgraded from version 1 to version 2");
        for (String name : LEGACY_WORLDS) {
            JsonObject found = null;
            for (JsonElement element : manifest.getAsJsonArray("worlds")) {
                JsonObject entry = element.getAsJsonObject();
                if (name.equals(entry.get("name").getAsString())) found = entry;
            }
            check(found != null && Long.toString(serverSeed).equals(found.get("seed").getAsString())
                            && found.get("sharedRandomSequences").getAsBoolean(),
                    "Legacy world keeps previous server seed and shared random sequence behavior: " + name);
            JsonObject lock = JsonParser.parseString(Files.readString(server.storageSource
                    .getDimensionPath(WorldManager.dimensionKey(name)).resolve(WorldSeedLock.FILE_NAME))).getAsJsonObject();
            check(Long.toString(serverSeed).equals(lock.get("seed").getAsString()),
                    "Legacy world's immutable lock pins its original seed: " + name);
        }
    }

    private static void querySeed(MinecraftServer server, World world) throws Exception {
        check(command(server, "wa seed " + world.name) == 1
                        && OUTPUT.lines.stream().anyMatch(line -> line.endsWith(": " + world.seed)),
                "Seed query reports exact persisted value: " + world.name);
    }

    private static long[] noiseFingerprint(RandomState state) {
        long[] values = new long[24];
        for (int i = 0; i < 8; i++) {
            int x = 113 + i * 137, y = 17 + i * 11, z = -239 - i * 173;
            var point = new DensityFunction.SinglePointContext(x, y, z);
            values[i * 3] = Double.doubleToLongBits(state.router().finalDensity().compute(point));
            values[i * 3 + 1] = Double.doubleToLongBits(state.router().temperature().compute(point));
            values[i * 3 + 2] = state.aquiferRandom().at(x, y, z).nextLong();
        }
        return values;
    }

    private static long fingerprint(ChunkAccess chunk) {
        long hash = 0xcbf29ce484222325L;
        var pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++) {
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                hash = (hash ^ Block.getId(chunk.getBlockState(pos.set(minX + x, y, minZ + z)))) * 0x100000001b3L;
            }
        }
        for (int y = chunk.getMinBuildHeight() >> 2; y < chunk.getMaxBuildHeight() >> 2; y++) {
            for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                int biome = chunk.getNoiseBiome((minX >> 2) + x, y, (minZ >> 2) + z)
                        .unwrapKey().orElseThrow().location().hashCode();
                hash = (hash ^ biome) * 0x100000001b3L;
            }
        }
        return hash;
    }

    private static void request(World world, ChunkPos position) {
        world.ticket = position;
        world.level.getChunkSource().addRegionTicket(TICKET, position, 0, position, false);
        world.future = world.level.getChunkSource().getChunkFutureMainThread(position.x, position.z, ChunkStatus.FULL, true);
    }

    private static ChunkAccess completed(World world) {
        ChunkResult<ChunkAccess> result = world.future.getNow(null);
        if (result == null || !result.isSuccess() || result.orElse(null) == null) {
            throw new IllegalStateException("Native FULL generation did not return a chunk: " + world.name);
        }
        ChunkAccess chunk = result.orElse(null);
        if (!chunk.getPos().equals(world.ticket)) throw new IllegalStateException("Unexpected chunk position");
        return chunk;
    }

    private static void release(World world) {
        if (world.level != null && world.ticket != null) {
            world.level.getChunkSource().removeRegionTicket(TICKET, world.ticket, 0, world.ticket, false);
            world.ticket = null;
        }
        if (world.future != null && world.future.isDone()) world.future = null;
    }

    private static boolean busy(World world) {
        return world.level.getChunkSource().chunkMap.hasWork() || RoadWeaverBridge.busy(world.level)
                || DistantHorizonsRoadGate.hasPendingWork(world.level);
    }

    private static void unload(MinecraftServer server, World world) throws Exception {
        if (world.ticket != null || (world.future != null && !world.future.isDone())) {
            throw new IllegalStateException("Refusing test unload while a chunk request is owned");
        }
        check(command(server, "wa unload " + world.name) == 1, "Drained seeded world unloaded: " + world.name);
        check(server.getLevel(WorldManager.dimensionKey(world.name)) == null, "Unloaded world removed from server map");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static int command(MinecraftServer server, String command) throws Exception {
        OUTPUT.lines.clear();
        return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack().withSource(OUTPUT));
    }

    private static void check(boolean passed, String label) {
        if (!passed) throw new IllegalStateException(label + "; command output=" + OUTPUT.lines);
        CHECKS.add(label);
        com.mojang.logging.LogUtils.getLogger().info("Seed smoke: {}", label);
    }

    private static void finish(MinecraftServer server, Throwable failure) {
        ended = true;
        for (World world : WORLDS) {
            try { release(world); }
            catch (Throwable cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        if (dhLease != null) dhLease.release();
        var report = new LinkedHashMap<String, Object>();
        report.put("passed", failure == null);
        report.put("checks", CHECKS);
        report.put("details", DETAILS);
        report.put("stage", stage);
        report.put("elapsedSeconds", (System.nanoTime() - started) / 1_000_000_000.0);
        report.put("failure", failure == null ? null : failure.toString());
        report.put("mods", ModList.get().getMods().stream().map(mod -> mod.getModId() + "@" + mod.getVersion()).toList());
        try {
            Files.writeString(Path.of("seed-smoke-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch (Exception writeFailure) {
            com.mojang.logging.LogUtils.getLogger().error("Unable to write seed smoke report", writeFailure);
        } finally {
            if (failure != null) com.mojang.logging.LogUtils.getLogger().error("World Admin seed smoke failed", failure);
            else com.mojang.logging.LogUtils.getLogger().info("World Admin seed smoke passed: {} checks", CHECKS.size());
            server.halt(false);
        }
    }

    private static final class World {
        final String name;
        final long seed;
        ServerLevel level;
        ChunkPos ticket;
        CompletableFuture<ChunkResult<ChunkAccess>> future;
        Object roadFingerprint;
        World(String name, long seed) { this.name = name; this.seed = seed; }
    }

    private static final class Capture implements CommandSource {
        final List<String> lines = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { lines.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }
}
