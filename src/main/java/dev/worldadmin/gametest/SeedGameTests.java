package dev.worldadmin.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldadmin.WorldAdmin;
import dev.worldadmin.world.WorldManager;
import dev.worldadmin.world.seed.WorldSeedLock;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Runtime coverage for seed consumers that initialize before a ServerLevel constructor returns. */
@GameTestHolder(WorldAdmin.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeedGameTests {
    private static final String BATCH = "worldadmin_seeds";
    private static final long SEED = -8123456789012345678L;
    private static final ChunkPos SAVED_CHUNK = new ChunkPos(7, 9);
    private static final ChunkPos NEW_CHUNK = new ChunkPos(57, -61);
    private static final ResourceLocation SEQUENCE = ResourceLocation.fromNamespaceAndPath(WorldAdmin.MOD_ID, "seed_test");
    private static final TicketType<ChunkPos> TICKET = TicketType.create("worldadmin_seed_test",
            Comparator.comparingLong(ChunkPos::toLong));
    private static final List<State> ACTIVE = new ArrayList<>();

    @GameTest(template="empty",batch="worldadmin_seed_commands",timeoutTicks=1200)
    public static void defaultTemplateSeedAndRandomCommands(GameTestHelper helper) throws Exception {
        MinecraftServer server=helper.getLevel().getServer();
        String fixed="seed_arg_"+UUID.randomUUID().toString().replace("-","");
        String random="seed_rand_"+UUID.randomUUID().toString().replace("-","");
        helper.assertTrue(command(server,"wa create "+fixed+" seed "+Long.MAX_VALUE)==1,"Default-template seed shortcut failed");
        helper.assertTrue(server.getLevel(WorldManager.dimensionKey(fixed)).getSeed()==Long.MAX_VALUE,"Maximum long seed lost precision");
        verifyManifestSeed(helper,server,fixed,Long.MAX_VALUE);
        helper.assertTrue(command(server,"wa unload "+fixed)==1,"Fixed-seed command fixture did not unload");
        helper.assertTrue(command(server,"wa create "+random+" seed random")==1,"Random seed command failed");
        long chosen=server.getLevel(WorldManager.dimensionKey(random)).getSeed();
        verifyManifestSeed(helper,server,random,chosen);
        helper.assertTrue(command(server,"wa unload "+random)==1,"Random fixture did not unload");
        helper.assertTrue(command(server,"wa load "+random)==1,"Random seed fixture did not reload");
        helper.assertTrue(server.getLevel(WorldManager.dimensionKey(random)).getSeed()==chosen,"Random seed was regenerated on reload");
        helper.assertTrue(command(server,"wa unload "+random)==1,"Reloaded random fixture did not unload");
        helper.succeed();
    }

    /** Uses the Nether template because GameTest's normal overworld is flat and cannot prove noise seeding. */
    // GameTest advances ticks without the dedicated server's normal 50 ms pacing.
    // Bound real generation time separately so its worker tasks can make progress.
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 2_000_000)
    public static void independentSeedsPersistAndGenerateDeterministically(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        State state = new State();
        ACTIVE.add(state);
        try {
            long originalSeed = server.overworld().getSeed();
            for (int i = 0; i < state.worlds.length; i++) {
                World world = state.worlds[i];
                long seed = i == 2 ? 0L : SEED;
                helper.assertTrue(command(server, "wa create " + world.name + " minecraft:the_nether seed " + seed) == 1,
                        "Explicit seed create command failed");
                world.level = server.getLevel(WorldManager.dimensionKey(world.name));
                helper.assertTrue(world.level != null, "Seeded level was not published");
                helper.assertTrue(world.level.getChunkSource().getLoadedChunksCount() == 0,
                        "Seeded creation synchronously generated spawn chunks");
                verifySeedConsumers(helper, world.level, seed);
                verifyManifestSeed(helper, server, world.name, seed);
                helper.assertTrue(command(server, "wa seed " + world.name) == 1, "Seed query command failed");
            }
            helper.assertTrue(server.overworld().getSeed() == originalSeed
                            && server.getWorldData().worldGenOptions().seed() == originalSeed,
                    "Creating seeded worlds changed the server's global seed");
            ServerLevel first = state.worlds[0].level;
            ServerLevel second = state.worlds[1].level;
            ServerLevel different = state.worlds[2].level;
            helper.assertTrue(Arrays.equals(noiseFingerprint(first.getChunkSource().randomState()),
                            noiseFingerprint(second.getChunkSource().randomState())),
                    "Equal seeds produced different noise state");
            helper.assertFalse(Arrays.equals(noiseFingerprint(first.getChunkSource().randomState()),
                            noiseFingerprint(different.getChunkSource().randomState())),
                    "Different seeds still produced the same noise state");
            helper.assertTrue(first.getRandomSequences() != second.getRandomSequences()
                            && first.getRandomSequences() != server.overworld().getRandomSequences(),
                    "Managed worlds share mutable random sequences");
            RandomSource oracle = new RandomSequences(SEED).get(SEQUENCE);
            long draw1 = oracle.nextLong();
            long draw2 = oracle.nextLong();
            state.nextSequenceValue = oracle.nextLong();
            helper.assertTrue(first.getRandomSequence(SEQUENCE).nextLong() == draw1
                            && second.getRandomSequence(SEQUENCE).nextLong() == draw1,
                    "World random sequences were not initialized with the independent seed");
            helper.assertTrue(first.getRandomSequence(SEQUENCE).nextLong() == draw2
                            && second.getRandomSequence(SEQUENCE).nextLong() == draw2,
                    "Consuming one world's random sequence advanced another world");
            request(state.worlds[0], SAVED_CHUNK);
            state.startTick = server.getTickCount();
            helper.assertFalse(state.worlds[0].future.isDone(), "Fresh FULL generation blocked inside request submission");
            helper.onEachTick(() -> advance(helper, server, state));
        } catch (Exception failure) {
            releaseAll(state);
            state.finished = true;
            helper.fail("Seed setup failed: " + failure);
        }
    }

    private static void advance(GameTestHelper helper, MinecraftServer server, State state) {
        if (state.finished || server.getTickCount() <= state.startTick) return;
        World first = state.worlds[0];
        World second = state.worlds[1];
        try {
            if (System.nanoTime() - state.startedNanos > 120_000_000_000L) {
                throw new IllegalStateException("120 second wall-clock timeout: " + diagnostic(state));
            }
            switch (state.stage) {
                case 0 -> {
                    if (!first.future.isDone()) return;
                    state.savedFingerprint = chunkFingerprint(completed(helper, first));
                    release(first);
                    state.stage++;
                }
                case 1 -> {
                    if (first.level.getChunkSource().chunkMap.hasWork()) return;
                    ServerLevel old = first.level;
                    helper.assertTrue(command(server, "wa unload " + first.name) == 1, "Seeded world did not unload");
                    verifyMismatchedLockRefusesLoad(helper, server, first);
                    helper.assertTrue(command(server, "wa load " + first.name) == 1, "Seeded world did not reload");
                    first.level = server.getLevel(WorldManager.dimensionKey(first.name));
                    helper.assertTrue(first.level != null && first.level != old, "Reload reused the old level");
                    verifySeedConsumers(helper, first.level, SEED);
                    verifyManifestSeed(helper, server, first.name, SEED);
                    helper.assertTrue(first.level.getRandomSequence(SEQUENCE).nextLong() == state.nextSequenceValue,
                            "Reload lost the independent world's saved random sequence position");
                    request(first, SAVED_CHUNK);
                    state.stage++;
                }
                case 2 -> {
                    if (!first.future.isDone()) return;
                    helper.assertTrue(chunkFingerprint(completed(helper, first)) == state.savedFingerprint,
                            "Reload changed already generated blocks or biomes");
                    release(first);
                    // Distant coordinates were never requested in either world before this reload.
                    request(first, NEW_CHUNK);
                    request(second, NEW_CHUNK);
                    state.stage++;
                }
                case 3 -> {
                    if (!first.future.isDone() || !second.future.isDone()) return;
                    helper.assertTrue(chunkFingerprint(completed(helper, first)) == chunkFingerprint(completed(helper, second)),
                            "New terrain generated after reload differs from a fresh world with the same seed");
                    release(first);
                    release(second);
                    state.stage++;
                }
                case 4 -> {
                    for (World world : state.worlds) {
                        if (world.level.getChunkSource().chunkMap.hasWork()) return;
                    }
                    for (World world : state.worlds) {
                        helper.assertTrue(command(server, "wa unload " + world.name) == 1, "Seed test cleanup unload failed");
                    }
                    state.finished = true;
                    ACTIVE.remove(state);
                    helper.succeed();
                }
                default -> helper.fail("Unexpected seed test stage " + state.stage);
            }
        } catch (Exception failure) {
            releaseAll(state);
            state.finished = true;
            helper.fail("Seed lifecycle stage " + state.stage + " failed: " + failure);
        }
    }

    private static String diagnostic(State state) {
        StringBuilder result = new StringBuilder("phase=").append(state.stage);
        for (int i = 0; i < state.worlds.length; i++) {
            World world = state.worlds[i];
            result.append("; world[").append(i).append("] ticket=").append(world.ticket)
                    .append(", future=").append(world.future == null ? "none"
                            : world.future.isCompletedExceptionally() ? "failed"
                            : world.future.isDone() ? "done" : "pending")
                    .append(", chunkWork=").append(world.level != null && world.level.getChunkSource().chunkMap.hasWork());
        }
        return result.toString();
    }

    private static void verifyMismatchedLockRefusesLoad(GameTestHelper helper, MinecraftServer server, World world) throws Exception {
        Path lock = server.storageSource.getDimensionPath(WorldManager.dimensionKey(world.name)).resolve(WorldSeedLock.FILE_NAME);
        String saved = Files.readString(lock);
        JsonObject changed = JsonParser.parseString(saved).getAsJsonObject();
        changed.addProperty("seed", Long.toString(SEED ^ 1L));
        try {
            Files.writeString(lock, changed.toString());
            helper.assertTrue(command(server, "wa load " + world.name) == 0,
                    "Load accepted a seed binding inconsistent with the world's manifest");
            helper.assertTrue(server.getLevel(WorldManager.dimensionKey(world.name)) == null,
                    "Rejected seed mismatch still published a live world");
            helper.assertTrue(Files.readString(lock).equals(changed.toString()),
                    "Rejected load overwrote the conflicting seed binding");
        } finally {
            Files.writeString(lock, saved);
        }
    }

    private static void verifySeedConsumers(GameTestHelper helper, ServerLevel level, long seed) throws Exception {
        helper.assertTrue(level.getSeed() == seed, "ServerLevel.getSeed does not match the configured seed");
        helper.assertTrue(level.getChunkSource().getGeneratorState().getLevelSeed() == seed,
                "Structure placement state was constructed with the server seed");
        Object check = field(ServerLevel.class, "structureCheck").get(level);
        helper.assertTrue(field(check.getClass(), "seed").getLong(check) == seed,
                "StructureCheck was constructed with the server seed");
        helper.assertTrue(field(BiomeManager.class, "biomeZoomSeed").getLong(level.getBiomeManager()) == BiomeManager.obfuscateSeed(seed),
                "Biome zoom retained the server's obfuscated seed");
        helper.assertTrue(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator,
                "Nether fixture must use a real noise generator");
        NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        RandomState expected = RandomState.create(generator.generatorSettings().value(),
                level.registryAccess().lookupOrThrow(Registries.NOISE), seed);
        helper.assertTrue(Arrays.equals(noiseFingerprint(expected), noiseFingerprint(level.getChunkSource().randomState())),
                "ChunkMap initialized density/aquifer randomness with a different seed");
    }

    private static long[] noiseFingerprint(RandomState state) {
        long[] values = new long[24];
        for (int i = 0; i < 8; i++) {
            int x = 113 + i * 137;
            int y = 17 + i * 11;
            int z = -239 - i * 173;
            DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(x, y, z);
            values[i * 3] = Double.doubleToLongBits(state.router().finalDensity().compute(point));
            values[i * 3 + 1] = Double.doubleToLongBits(state.router().temperature().compute(point));
            values[i * 3 + 2] = state.aquiferRandom().at(x, y, z).nextLong();
        }
        return values;
    }

    private static long chunkFingerprint(ChunkAccess chunk) {
        long hash = 0xcbf29ce484222325L;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    hash = (hash ^ Block.getId(chunk.getBlockState(pos.set(minX + x, y, minZ + z)))) * 0x100000001b3L;
                }
            }
        }
        for (int y = chunk.getMinBuildHeight() >> 2; y < chunk.getMaxBuildHeight() >> 2; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int biome = chunk.getNoiseBiome((minX >> 2) + x, y, (minZ >> 2) + z).unwrapKey().orElseThrow().location().hashCode();
                    hash = (hash ^ biome) * 0x100000001b3L;
                }
            }
        }
        return hash;
    }

    private static void request(World world, ChunkPos pos) {
        world.ticket = pos;
        world.level.getChunkSource().addRegionTicket(TICKET, pos, 0, pos, false);
        world.future = world.level.getChunkSource().getChunkFutureMainThread(pos.x, pos.z, ChunkStatus.FULL, true);
    }

    private static ChunkAccess completed(GameTestHelper helper, World world) {
        helper.assertFalse(world.future.isCompletedExceptionally(), "Seed test chunk generation failed");
        ChunkResult<ChunkAccess> result = world.future.getNow(null);
        helper.assertTrue(result != null && result.isSuccess(), "Seed test chunk was unavailable");
        ChunkAccess chunk = result.orElse(null);
        helper.assertTrue(chunk != null && chunk.getPos().equals(world.ticket), "Seed test returned the wrong chunk");
        return chunk;
    }

    private static void release(World world) {
        if (world.level != null && world.ticket != null) {
            world.level.getChunkSource().removeRegionTicket(TICKET, world.ticket, 0, world.ticket, false);
            world.ticket = null;
        }
    }

    private static void releaseAll(State state) {
        for (World world : state.worlds) release(world);
    }

    @AfterBatch(batch = BATCH)
    public static void releaseTicketsAfterBatch(ServerLevel level) {
        for (State state : ACTIVE) releaseAll(state);
        ACTIVE.clear();
    }

    private static int command(MinecraftServer server, String value) throws Exception {
        return server.getCommands().getDispatcher().execute(value, server.createCommandSourceStack().withSuppressedOutput());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void verifyManifestSeed(GameTestHelper helper, MinecraftServer server, String name, long seed) throws Exception {
        JsonObject manifest = JsonParser.parseString(Files.readString(
                server.getWorldPath(LevelResource.ROOT).resolve("worldadmin/worlds.json"))).getAsJsonObject();
        for (JsonElement value : manifest.getAsJsonArray("worlds")) {
            JsonObject entry = value.getAsJsonObject();
            if (name.equals(entry.get("name").getAsString())) {
                helper.assertTrue(entry.has("seed") && entry.get("seed").getAsLong() == seed,
                        "Manifest did not preserve the exact 64-bit world seed");
                return;
            }
        }
        helper.fail("Seeded world is missing from the manifest");
    }

    private static final class State {
        private final World[] worlds = { new World(), new World(), new World() };
        private final long startedNanos = System.nanoTime();
        private int stage;
        private int startTick;
        private long nextSequenceValue;
        private long savedFingerprint;
        private boolean finished;
    }

    private static final class World {
        private final String name = "seedtest_" + UUID.randomUUID().toString().replace("-", "");
        private ServerLevel level;
        private ChunkPos ticket;
        private CompletableFuture<ChunkResult<ChunkAccess>> future;
    }
}
