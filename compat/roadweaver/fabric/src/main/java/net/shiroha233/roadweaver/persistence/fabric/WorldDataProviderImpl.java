package net.shiroha233.roadweaver.persistence.fabric;

import net.shiroha233.roadweaver.persistence.WorldDataProvider;

/**
 * Architectury @ExpectPlatform 实现类（Fabric）
 */
public final class WorldDataProviderImpl {
    private static final WorldDataProvider INSTANCE = new FabricWorldDataProvider();

    public static WorldDataProvider getInstance() {
        return INSTANCE;
    }
}
