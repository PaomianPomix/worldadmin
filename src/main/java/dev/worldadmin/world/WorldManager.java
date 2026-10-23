package dev.worldadmin.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import dev.worldadmin.compat.RoadWeaverBridge;
import dev.worldadmin.compat.DistantHorizonsRoadGate;
import dev.worldadmin.world.seed.SeedValue;
import dev.worldadmin.world.seed.WorldSeedLock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;

/**
 * Owns dynamic ServerLevels, while Minecraft retains ownership of the save lock and shutdown.
 * Each world pins an immutable seed. Time, weather, rules and spawn data follow the overworld.
 * Every public operation must execute on the server thread.
 */
public final class WorldManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,48}");
    private static final ChunkProgressListener NO_SPAWN_PROGRESS = new ChunkProgressListener() {
        @Override public void updateSpawnPos(ChunkPos pos) {}
        @Override public void onStatusChange(ChunkPos pos, @Nullable ChunkStatus status) {}
        @Override public void start() {}
        @Override public void stop() {}
    };

    private final MinecraftServer server;
    private final Path definitionsFile;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, BorderChangeListener> borderListeners = new HashMap<>();

    public WorldManager(MinecraftServer server) throws IOException {
        this.server = server;
        this.definitionsFile = server.getWorldPath(LevelResource.ROOT).resolve("worldadmin/worlds.json");
        requireServerThread();
        readDefinitions();
    }

    /** Restore before player admission. Failures retain their definitions and are reported individually. */
    public List<String> restoreLoadedWorlds() {
        requireServerThread();
        List<String> failures = new ArrayList<>();
        for (Definition definition : definitions.values()) {
            if (!definition.loadOnStart) continue;
            try {
                load(definition.name);
            } catch (Exception failure) {
                LOGGER.error("Cannot restore managed world {}", definition.name, failure);
                failures.add(definition.name + ": " + failure.getMessage());
            }
        }
        return List.copyOf(failures);
    }

    /** Clone the actual registered template through its codec; never substitute vanilla generation. */
    public WorldInfo create(String name, ResourceLocation template) throws IOException {
        return create(name,template,server.getWorldData().worldGenOptions().seed());
    }

    public WorldInfo create(String name, ResourceLocation template, long seed) throws IOException {
        requireServerThread();
        validateName(name);
        if (definitions.containsKey(name)) throw new IllegalArgumentException("世界定义已存在: " + name);
        ResourceKey<Level> key = dimensionKey(name);
        if (server.getLevel(key) != null
                || server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).containsKey(key.location())) {
            throw new IllegalArgumentException("维度标识已被其他世界占用: " + key.location());
        }
        Path dimensionPath = server.storageSource.getDimensionPath(key);
        if (Files.exists(dimensionPath)) {
            throw new IllegalArgumentException("维度目录已存在，拒绝覆盖: " + dimensionPath);
        }
        LevelStem source = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(template);
        if (source == null) throw new IllegalArgumentException("没有已注册的世界模板: " + template);
        JsonElement encoded = LevelStem.CODEC.encodeStart(
                server.registryAccess().createSerializationContext(JsonOps.INSTANCE), source).getOrThrow();
        // Decode immediately so missing/unsupported codecs fail before a definition is committed.
        decodeStem(encoded);
        Definition definition = new Definition(name, template, encoded.deepCopy(), false, seed, false);
        definitions.put(name, definition);
        try {
            writeDefinitions();
        } catch (IOException failure) {
            definitions.remove(name);
            throw failure;
        }
        load(name);
        return info(definition);
    }

    @SuppressWarnings("deprecation")
    public ServerLevel load(String name) throws IOException {
        requireServerThread();
        Definition definition = definition(name);
        ServerLevel existing = getLevel(name);
        if (existing != null) {
            if (!borderListeners.containsKey(name)) {
                throw new IllegalStateException("维度已由其他组件加载，拒绝接管: " + dimensionKey(name).location());
            }
            return existing;
        }
        ResourceKey<Level> key = dimensionKey(name);
        if (server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).containsKey(key.location())) {
            throw new IllegalStateException("保存的维度标识与数据包维度冲突: " + key.location());
        }
        LevelStem stem = decodeStem(definition.stem);
        Path dimensionPath=server.storageSource.getDimensionPath(key);
        WorldSeedLock.verifyOrCreate(dimensionPath,definition.seed,!Files.exists(dimensionPath));
        ServerLevel overworld = server.overworld();
        // This constructor initializes structure state and mod hooks on the server thread.
        // It deliberately does not run setInitialSpawn, prepareLevels, or getChunk.
        ServerLevel level = new ServerLevel(server, Util.backgroundExecutor(), server.storageSource,
                new SeededLevelData(server.getWorldData(), server.getWorldData().overworldData(),definition.seed),
                key, stem, NO_SPAWN_PROGRESS, server.getWorldData().isDebugWorld(),
                BiomeManager.obfuscateSeed(definition.seed),
                List.of(), false, definition.sharedRandomSequences ? overworld.getRandomSequences() : null);
        boolean previousLoadOnStart = definition.loadOnStart;
        definition.loadOnStart = true;
        try {
            writeDefinitions();
        } catch (IOException failure) {
            definition.loadOnStart = previousLoadOnStart;
            try { level.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
        level.getWorldBorder().applySettings(overworld.getWorldBorder().createSettings());
        BorderChangeListener listener = new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder());
        overworld.getWorldBorder().addListener(listener);
        borderListeners.put(name, listener);
        server.forgeGetWorldMap().put(key, level);
        server.markWorldsDirty();
        // Publish before the event so integrations can resolve the level through MinecraftServer.
        // If a third-party listener throws, retain the valid published level rather than closing
        // a level that other listeners may already have retained.
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        return level;
    }

    /**
     * Caller must first drain its own jobs/teleports. Reject remaining native work/forced tickets;
     * never synchronously wait for generation to finish while processing an admin command.
     */
    @SuppressWarnings("deprecation")
    public void unload(String name) throws IOException {
        requireServerThread();
        Definition definition = definition(name);
        ServerLevel level = getLevel(name);
        if (level == null) {
            setLoadOnStart(definition, false);
            return;
        }
        if (!borderListeners.containsKey(name)) throw new IllegalStateException("拒绝卸载其他组件管理的维度");
        if (!level.players().isEmpty()) throw new IllegalStateException("世界中仍有玩家，请先传送离开");
        if (DistantHorizonsRoadGate.hasPendingWork(level)) throw new IllegalStateException("DH 道路生成任务尚未排空，请稍后重试");
        if (RoadWeaverBridge.busy(level)) throw new IllegalStateException("RoadWeaver 规划或道路任务尚未排空，请稍后重试");
        if (level.getChunkSource().chunkMap.hasWork()) {
            throw new IllegalStateException("世界仍有区块票据或生成/保存工作，请取消任务、移除强加载并等待后重试");
        }
        if (!DistantHorizonsRoadGate.beginUnload(level)) {
            throw new IllegalStateException("DH 在卸载准备期间收到新任务，请等待排空后重试");
        }
        try {
        level.save(null, true, false);
        setLoadOnStart(definition, false);
        try {
            NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
            RoadWeaverBridge.releaseWorld(level);
        } catch (RuntimeException failure) {
            // Closing after a failed unload event could strand an integration with a stale level.
            try { setLoadOnStart(definition, true); } catch (IOException persistFailure) { failure.addSuppressed(persistFailure); }
            throw failure;
        }
        // Remove even if close fails: a partly closed ServerLevel must never keep ticking.
        try {
            level.close();
        } finally {
            server.forgeGetWorldMap().remove(level.dimension(), level);
            server.markWorldsDirty();
            BorderChangeListener listener = borderListeners.remove(name);
            if (listener != null) server.overworld().getWorldBorder().removeListener(listener);
        }
        } finally {
            if (server.getLevel(level.dimension()) == level) DistantHorizonsRoadGate.cancelUnload(level);
        }
    }

    public List<WorldInfo> list() {
        requireServerThread();
        return definitions.values().stream().map(this::info).toList();
    }

    @Nullable
    public ServerLevel getLevel(String name) {
        requireServerThread();
        return definitions.containsKey(name) ? server.getLevel(dimensionKey(name)) : null;
    }

    public boolean contains(String name) {
        requireServerThread();
        return definitions.containsKey(name);
    }

    /** Available while unloaded; seed changes require creating a different world. */
    public long seed(String name) {
        requireServerThread();
        return definition(name).seed;
    }

    public boolean isManaged(ResourceKey<Level> key) {
        requireServerThread();
        return "worldadmin".equals(key.location().getNamespace()) && definitions.containsKey(key.location().getPath());
    }

    public static ResourceKey<Level> dimensionKey(String name) {
        validateName(name);
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("worldadmin", name));
    }

    public record WorldInfo(String name, ResourceLocation dimension, ResourceLocation template,
                            boolean loaded, boolean loadOnStart, long seed) {}

    private WorldInfo info(Definition definition) {
        ResourceKey<Level> key = dimensionKey(definition.name);
        return new WorldInfo(definition.name, key.location(), definition.template,
                server.getLevel(key) != null, definition.loadOnStart,definition.seed);
    }

    private Definition definition(String name) {
        validateName(name);
        Definition definition = definitions.get(name);
        if (definition == null) throw new IllegalArgumentException("世界定义不存在: " + name);
        return definition;
    }

    private LevelStem decodeStem(JsonElement encoded) {
        LevelStem stem = LevelStem.CODEC.parse(
                server.registryAccess().createSerializationContext(JsonOps.INSTANCE), encoded.deepCopy()).getOrThrow();
        if (stem.type().unwrapKey().isEmpty()) {
            throw new IllegalArgumentException("维度类型必须引用服务器启动时已注册的类型，不能使用运行时内联类型");
        }
        return stem;
    }

    private void setLoadOnStart(Definition definition, boolean value) throws IOException {
        boolean previous = definition.loadOnStart;
        definition.loadOnStart = value;
        try { writeDefinitions(); }
        catch (IOException failure) { definition.loadOnStart = previous; throw failure; }
    }

    private void readDefinitions() throws IOException {
        if (!Files.exists(definitionsFile)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(definitionsFile, StandardCharsets.UTF_8)).getAsJsonObject();
            int version=root.has("version") ? Math.toIntExact(SeedValue.read(root.get("version"))) : -1;
            if (version != 1 && version != 2) {
                throw new IllegalArgumentException("不支持的世界定义版本");
            }
            for (JsonElement element : root.getAsJsonArray("worlds")) {
                JsonObject entry = element.getAsJsonObject();
                String name = entry.get("name").getAsString();
                validateName(name);
                Definition definition = new Definition(name,
                        ResourceLocation.parse(entry.get("template").getAsString()),
                        entry.getAsJsonObject("stem").deepCopy(), entry.get("loadOnStart").getAsBoolean(),
                        version==1 ? server.getWorldData().worldGenOptions().seed() : SeedValue.read(entry.get("seed")),
                        version==1 || readSharedRandomSequences(entry));
                if (definitions.putIfAbsent(name, definition) != null) {
                    throw new IllegalArgumentException("重复的世界名称: " + name);
                }
            }
            if (version==1) {
                // Bind every legacy world before upgrading the manifest; an interrupted
                // migration can safely repeat against the original v1 manifest.
                for(Definition definition:definitions.values()) {
                    WorldSeedLock.verifyOrCreate(server.storageSource.getDimensionPath(dimensionKey(definition.name)),
                            definition.seed,true);
                }
                writeDefinitions();
                LOGGER.info("Pinned {} legacy worlds to their existing server seed",definitions.size());
            }
        } catch (RuntimeException failure) {
            throw new IOException("读取世界定义失败，原文件保留: " + definitionsFile, failure);
        }
    }

    private static boolean readSharedRandomSequences(JsonObject entry) {
        JsonElement value=entry.get("sharedRandomSequences");
        if(value==null) return false;
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("sharedRandomSequences 必须是布尔值");
        }
        return value.getAsBoolean();
    }

    private void writeDefinitions() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        JsonArray worlds = new JsonArray();
        for (Definition definition : definitions.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", definition.name);
            entry.addProperty("template", definition.template.toString());
            entry.addProperty("loadOnStart", definition.loadOnStart);
            // A string avoids losing 64-bit precision in JSON editors using doubles.
            entry.addProperty("seed", Long.toString(definition.seed));
            entry.addProperty("sharedRandomSequences", definition.sharedRandomSequences);
            entry.add("stem", definition.stem.deepCopy());
            worlds.add(entry);
        }
        root.add("worlds", worlds);
        Files.createDirectories(definitionsFile.getParent());
        Path temporary = Files.createTempFile(definitionsFile.getParent(), "worlds-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, definitionsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, definitionsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateName(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("世界名称只能包含 1–48 位小写英文字母、数字或下划线");
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("WorldManager 必须在服务器主线程调用");
    }

    private static final class Definition {
        private final String name;
        private final ResourceLocation template;
        private final JsonElement stem;
        private boolean loadOnStart;
        private final long seed;
        private final boolean sharedRandomSequences;

        private Definition(String name, ResourceLocation template, JsonElement stem, boolean loadOnStart,
                           long seed, boolean sharedRandomSequences) {
            this.name = name;
            this.template = template;
            this.stem = stem;
            this.loadOnStart = loadOnStart;
            this.seed=seed;
            this.sharedRandomSequences=sharedRandomSequences;
        }
    }
}
