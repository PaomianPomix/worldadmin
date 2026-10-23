package net.shiroha233.roadweaver.persistence.files;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/**
 * 文件型持久化的统一路径解析。
 */
public final class FileStoragePathResolver {
    private FileStoragePathResolver() {}

    private static final String ROOT_DIR = "data/roadweaver/persistence";

    public static Path root(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve(ROOT_DIR);
    }

    public static Path categoryRoot(ServerLevel level, String category) {
        return root(level).resolve(category).resolve(dimensionKey(level.dimension().location()));
    }

    /** Resolve the legacy SavedData location without touching Minecraft's synchronous data-storage cache. */
    public static Path legacyStructureState(ServerLevel level) {
        Path saveRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        ResourceLocation id = level.dimension().location();
        Path dimensionRoot = switch (id.toString()) {
            case "minecraft:overworld" -> saveRoot;
            case "minecraft:the_nether" -> saveRoot.resolve("DIM-1");
            case "minecraft:the_end" -> saveRoot.resolve("DIM1");
            default -> saveRoot.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
        };
        return dimensionRoot.resolve("data").resolve("roadweaver_world_data.dat");
    }

    public static String dimensionKey(ResourceLocation location) {
        if (location == null) return "unknown";
        // Keep the existing overworld directory and its on-disk migration markers.
        if (location.toString().equals("minecraft:overworld")) return "minecraft_overworld";
        // Hex is injective for resource ids and safe on Windows (including reserved names).
        return "dim_" + java.util.HexFormat.of().formatHex(
                location.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
