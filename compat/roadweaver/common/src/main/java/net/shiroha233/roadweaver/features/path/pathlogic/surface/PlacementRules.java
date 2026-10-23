package net.shiroha233.roadweaver.features.path.pathlogic.surface;

import net.minecraft.world.level.block.Block;
import net.shiroha233.roadweaver.features.path.decoration.compat.RoadFeatureCompat;

/**
 * 方块放置规则
 */
public final class PlacementRules {
    private PlacementRules() {}

    /**
     * 放置检查
     */
    public static boolean placeAllowedCheck(Block block) {
        return !RoadFeatureCompat.dontPlaceHere(block);
    }
}
