package dev.worldadmin.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.worldadmin.WorldAdmin;
import dev.worldadmin.world.WorldManager;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Development-only integration tests. GameTestHooks excludes these from production runs. */
@GameTestHolder(WorldAdmin.MOD_ID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = WorldAdmin.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class WorldLifecycleGameTests {
    private static final String BATCH = "worldadmin_lifecycle";
    private static final ResourceLocation EMPTY = ResourceLocation.fromNamespaceAndPath(WorldAdmin.MOD_ID, "empty");
    private static final TicketType<ChunkPos> TICKET = TicketType.create("worldadmin_gametest",
            Comparator.comparingLong(ChunkPos::toLong));
    private static final Map<ResourceLocation, TestState> ACTIVE = new HashMap<>();

    /** A cached one-block template avoids shipping test-only structure files in the datapack. */
    @SubscribeEvent
    public static void installTestTemplate(ServerStartedEvent event) {
        if (!GameTestHooks.isGametestEnabled()) return;
        CompoundTag tag = new CompoundTag();
        tag.put("size", ints(1, 1, 1));
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        ListTag palette = new ListTag();
        palette.add(air);
        tag.put("palette", palette);
        CompoundTag block = new CompoundTag();
        block.put("pos", ints(0, 0, 0));
        block.putInt("state", 0);
        ListTag blocks = new ListTag();
        blocks.add(block);
        tag.put("blocks", blocks);
        tag.put("entities", new ListTag());
        event.getServer().getStructureManager().getOrCreate(EMPTY)
                .load(event.getServer().registryAccess().lookupOrThrow(Registries.BLOCK), tag);
    }

    /**
     * Exercises the registered commands and the mod's live manager, not a second manager instance.
     * Writes one marker block, unloads, recreates the ServerLevel and reads the saved chunk back.
     */
    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 1200)
    public static void createGenerateUnloadReload(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String name = "gametest_" + UUID.randomUUID().toString().replace("-", "");
        TestState state = new TestState(name);
        ACTIVE.put(WorldManager.dimensionKey(name).location(), state);
        try {
            helper.assertTrue(command(server, "wa create " + name + " minecraft:overworld") == 1,
                    "The real create command failed");
            state.level = server.getLevel(WorldManager.dimensionKey(name));
            helper.assertTrue(state.level != null, "Created dimension was not published to the server");
            helper.assertTrue(state.loads == 1, "Create must emit exactly one native LevelEvent.Load");
            helper.assertTrue(state.level.getChunkSource().getLoadedChunksCount() == 0,
                    "Create synchronously loaded/generated spawn chunks");
            helper.assertTrue(state.level.getSeed() == server.overworld().getSeed(),
                    "The documented shared seed was not preserved");
            LevelStem template = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD);
            helper.assertTrue(template != null, "Missing overworld template");
            helper.assertTrue(state.level.getChunkSource().getGenerator() != template.generator(),
                    "Template generator instance was reused instead of cloned");
            state.generatorJson = encode(server, template.generator());
            helper.assertTrue(state.generatorJson.equals(encode(server, state.level.getChunkSource().getGenerator())),
                    "Cloned generator codec/settings differ from the registered template");
            helper.assertTrue(state.level.dimensionTypeRegistration().unwrapKey().equals(template.type().unwrapKey()),
                    "Cloned dimension type differs from the template");
            helper.assertTrue(command(server, "wa create " + name + " minecraft:overworld") == 0,
                    "A duplicate create unexpectedly overwrote the existing dimension");
            helper.assertTrue(server.getLevel(WorldManager.dimensionKey(name)) == state.level,
                    "Rejected duplicate create replaced the live level");
            verifyManifest(helper, server, state.name, true);
            requestChunk(state);
            helper.assertFalse(state.future.isDone(),
                    "A fresh FULL chunk request completed inside submission; check for a blocking getChunkFuture call");
            helper.assertTrue(command(server, "wa unload " + name) == 0,
                    "Unload must reject a world with an active generation ticket");
            state.startServerTick = server.getTickCount();
            helper.onEachTick(() -> advance(helper, server, state));
        } catch (Exception failure) {
            releaseTicket(state);
            helper.fail("Lifecycle setup failed: " + failure);
        }
    }

    private static void advance(GameTestHelper helper, MinecraftServer server, TestState state) {
        if (state.finished || server.getTickCount() <= state.startServerTick) return;
        try {
            switch (state.stage) {
                case 0 -> {
                    if (!state.future.isDone()) return;
                    completedChunk(helper, state);
                    // Stay inside the requested chunk; no synchronous neighboring chunk access.
                    helper.assertTrue(state.level.setBlock(state.marker, Blocks.GOLD_BLOCK.defaultBlockState(), 2),
                            "Could not place persistence marker in the generated chunk");
                    releaseTicket(state);
                    state.stage = 1;
                }
                case 1 -> {
                    if (state.level.getChunkSource().chunkMap.hasWork()) return;
                    ServerLevel previous = state.level;
                    helper.assertTrue(command(server, "wa unload " + state.name) == 1, "Drained world failed to unload");
                    helper.assertTrue(server.getLevel(WorldManager.dimensionKey(state.name)) == null,
                            "Unloaded dimension remains in the server map");
                    helper.assertTrue(state.unloads == 1, "Unload must emit exactly one native unload event");
                    verifyManifest(helper, server, state.name, false);
                    helper.assertTrue(command(server, "wa load " + state.name) == 1, "Saved world failed to reload");
                    state.level = server.getLevel(WorldManager.dimensionKey(state.name));
                    helper.assertTrue(state.level != null && state.level != previous,
                            "Reload did not construct a fresh ServerLevel");
                    helper.assertTrue(state.loads == 2, "Reload did not emit its native load event");
                    helper.assertTrue(state.generatorJson.equals(encode(server, state.level.getChunkSource().getGenerator())),
                            "Reload changed the persisted generator configuration");
                    helper.assertTrue(state.level.getChunkSource().getLoadedChunksCount() == 0,
                            "Reload synchronously warmed up spawn chunks");
                    verifyManifest(helper, server, state.name, true);
                    requestChunk(state);
                    state.stage = 2;
                }
                case 2 -> {
                    if (!state.future.isDone()) return;
                    ChunkAccess chunk = completedChunk(helper, state);
                    helper.assertTrue(chunk.getBlockState(state.marker).is(Blocks.GOLD_BLOCK),
                            "Native region persistence lost the marker block across unload/reload");
                    releaseTicket(state);
                    state.stage = 3;
                }
                case 3 -> {
                    if (state.level.getChunkSource().chunkMap.hasWork()) return;
                    helper.assertTrue(command(server, "wa unload " + state.name) == 1, "Final cleanup unload failed");
                    helper.assertTrue(state.unloads == 2, "Final unload event count is incorrect");
                    verifyManifest(helper, server, state.name, false);
                    state.finished = true;
                    ACTIVE.remove(WorldManager.dimensionKey(state.name).location());
                    helper.succeed();
                }
                default -> helper.fail("Unexpected lifecycle test stage " + state.stage);
            }
        } catch (Exception failure) {
            releaseTicket(state);
            state.finished = true;
            helper.fail("Lifecycle stage " + state.stage + " failed: " + failure);
        }
    }

    private static void requestChunk(TestState state) {
        state.level.getChunkSource().addRegionTicket(TICKET, state.pos, 0, state.pos, false);
        state.ticketHeld = true;
        state.future = state.level.getChunkSource().getChunkFutureMainThread(
                state.pos.x, state.pos.z, ChunkStatus.FULL, true);
    }

    private static ChunkAccess completedChunk(GameTestHelper helper, TestState state) {
        helper.assertFalse(state.future.isCompletedExceptionally(), "Native chunk generation future failed");
        // getNow is nonblocking; callers check isDone before reaching this method.
        ChunkResult<ChunkAccess> result = state.future.getNow(null);
        helper.assertTrue(result != null && result.isSuccess(), "Native generation returned an unloaded/error result");
        ChunkAccess chunk = result.orElse(null);
        helper.assertTrue(chunk != null && chunk.getPos().equals(state.pos), "Generation returned the wrong chunk");
        return chunk;
    }

    private static void releaseTicket(TestState state) {
        if (state.ticketHeld && state.level != null) {
            state.level.getChunkSource().removeRegionTicket(TICKET, state.pos, 0, state.pos, false);
            state.ticketHeld = false;
        }
    }

    /** Release retained work even when a test assertion or timeout interrupts normal cleanup. */
    @AfterBatch(batch = BATCH)
    public static void releaseTicketsAfterBatch(ServerLevel level) {
        for (TestState state : ACTIVE.values()) releaseTicket(state);
        ACTIVE.clear();
    }

    @SubscribeEvent
    public static void loaded(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            TestState state = ACTIVE.get(level.dimension().location());
            if (state != null) state.loads++;
        }
    }

    @SubscribeEvent
    public static void unloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            TestState state = ACTIVE.get(level.dimension().location());
            if (state != null) state.unloads++;
        }
    }

    private static int command(MinecraftServer server, String command) throws Exception {
        return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack().withSuppressedOutput());
    }

    private static JsonElement encode(MinecraftServer server, ChunkGenerator generator) {
        return ChunkGenerator.CODEC.encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), generator)
                .getOrThrow();
    }

    private static void verifyManifest(GameTestHelper helper, MinecraftServer server, String name, boolean loadOnStart) throws Exception {
        JsonObject manifest = JsonParser.parseString(Files.readString(
                server.getWorldPath(LevelResource.ROOT).resolve("worldadmin/worlds.json"))).getAsJsonObject();
        for (JsonElement value : manifest.getAsJsonArray("worlds")) {
            JsonObject entry = value.getAsJsonObject();
            if (name.equals(entry.get("name").getAsString())) {
                helper.assertTrue(entry.get("loadOnStart").getAsBoolean() == loadOnStart,
                        "Manifest loadOnStart does not reflect the lifecycle change");
                helper.assertTrue(entry.get("template").getAsString().equals("minecraft:overworld"),
                        "Manifest lost the template identity");
                helper.assertTrue(entry.getAsJsonObject("stem").has("generator"), "Manifest lost the generator codec");
                return;
            }
        }
        helper.fail("Managed world definition is absent from the manifest");
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag result = new ListTag();
        result.add(IntTag.valueOf(x));
        result.add(IntTag.valueOf(y));
        result.add(IntTag.valueOf(z));
        return result;
    }

    private static final class TestState {
        private final String name;
        private final ChunkPos pos = new ChunkPos(7, 9);
        private final BlockPos marker = new BlockPos(pos.getMinBlockX() + 8, 96, pos.getMinBlockZ() + 8);
        private ServerLevel level;
        private CompletableFuture<ChunkResult<ChunkAccess>> future;
        private JsonElement generatorJson;
        private int stage;
        private int startServerTick;
        private int loads;
        private int unloads;
        private boolean ticketHeld;
        private boolean finished;

        private TestState(String name) { this.name = name; }
    }
}
