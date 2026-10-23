/* 文件职责：在 NeoForge 服务端向当前维度客户端同步自动规划采样范围。 */
package net.shiroha233.roadweaver.network.neoforge;

import net.minecraft.server.MinecraftServer;
import net.shiroha233.roadweaver.runtime.LevelTaskTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingActivities;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingBounds;

import java.util.List;

/**
 * NeoForge 自动规划采样范围同步实现。
 */
public final class ServerMapPlanningActivityBridgeImpl {
    private ServerMapPlanningActivityBridgeImpl() {}

    public static void broadcast(ServerLevel level) {
        if (level == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        LevelTaskTracker.runOnServer(level, () -> {
            List<AutomaticPlanningSamplingBounds> snapshot = AutomaticPlanningSamplingActivities.snapshot(level);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player != null && player.serverLevel() == level) {
                    MapNetworkNeoForge.broadcastAutomaticPlanningSampling(
                            player,
                            level.dimension().location(),
                            snapshot);
                }
            }
        });
    }
}
