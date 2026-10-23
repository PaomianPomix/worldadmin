package net.shiroha233.roadweaver.persistence.neoforge;

import net.shiroha233.roadweaver.persistence.WorldDataProvider;

/**
 * Architectury @ExpectPlatform implementation for NeoForge.
 */
public final class WorldDataProviderImpl {
    private static final WorldDataProvider INSTANCE = new NeoForgeWorldDataProvider();

    public static WorldDataProvider getInstance() {
        return INSTANCE;
    }
}
