package net.shiroha233.roadweaver.client.neoforge;

import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.shiroha233.roadweaver.client.map.data.MapSnapshotCache;

public final class ClientEvents {
    private ClientEvents() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ClientEvents::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onLoggingOut);
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn e) {
        MapSnapshotCache.clearNow();
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        MapSnapshotCache.clearNow();
    }
}
