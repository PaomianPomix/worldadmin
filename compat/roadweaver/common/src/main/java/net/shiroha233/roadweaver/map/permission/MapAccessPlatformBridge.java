package net.shiroha233.roadweaver.map.permission;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class MapAccessPlatformBridge {
    private MapAccessPlatformBridge() {
    }

    @ExpectPlatform
    public static MapAccessPolicy getPolicy(MinecraftServer server) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void setPolicy(MinecraftServer server, MapAccessPolicy policy) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void syncPlayer(ServerPlayer player) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void syncAll(MinecraftServer server) {
        throw new AssertionError();
    }
}
