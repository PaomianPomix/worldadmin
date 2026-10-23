package net.shiroha233.roadweaver.api;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

/** Eligibility is explicit; no dimension identity is ever impersonated. */
public final class RoadWeaverWorlds {
    private RoadWeaverWorlds() {}

    public static int apiVersion() { return 1; }

    public static boolean supports(Level level) {
        if (level == null) return false;
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel
                && serverLevel.getServer().getLevel(serverLevel.dimension()) != serverLevel) return false;
        if (Level.OVERWORLD.equals(level.dimension())) return true;
        var id = level.dimension().location();
        return isManagedName(id.getNamespace(), id.getPath())
                && level.dimensionTypeRegistration().is(BuiltinDimensionTypes.OVERWORLD);
    }

    static boolean isManagedName(String namespace, String path) {
        return "worldadmin".equals(namespace) && path != null
                && path.matches("[a-z0-9][a-z0-9_-]{0,63}");
    }
}
