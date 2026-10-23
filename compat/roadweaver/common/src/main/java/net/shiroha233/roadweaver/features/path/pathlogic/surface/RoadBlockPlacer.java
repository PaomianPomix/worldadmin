package net.shiroha233.roadweaver.features.path.pathlogic.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;

import java.util.List;

/**
 * 道路方块放置器
 */
public final class RoadBlockPlacer {
    private RoadBlockPlacer() {
    }

    public static void placeRoadBlock(WorldGenLevel world,
            BlockState blockBelow,
            BlockPos surfacePos,
            List<BlockState> materials,
            RandomSource random,
            ModConfig cfg) {
        if (!PlacementRules.placeAllowedCheck(blockBelow.getBlock()))
            return;
        BlockState chosen = materials.get(random.nextInt(materials.size()));

        BlockPos below1 = surfacePos.below();
        world.setBlock(below1, chosen, RoadConstants.BLOCK_UPDATE_FLAG);

        AboveColumnClearer.clearAboveColumn(world, surfacePos, cfg);

        BlockPos belowPos1 = surfacePos.below(RoadConstants.BELOW_DEPTH_2);
        BlockState belowState1 = world.getBlockState(belowPos1);
        if (belowState1.is(Blocks.GRASS_BLOCK)) {
            world.setBlock(belowPos1, Blocks.DIRT.defaultBlockState(), RoadConstants.BLOCK_UPDATE_FLAG);
        }
    }
}
