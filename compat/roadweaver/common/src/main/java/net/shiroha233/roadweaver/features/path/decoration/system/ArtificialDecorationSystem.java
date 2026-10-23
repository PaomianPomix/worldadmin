package net.shiroha233.roadweaver.features.path.decoration.system;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.features.path.decoration.base.Decoration;

import java.util.List;
import java.util.Set;

/**
 * 人工装饰系统
 */
public final class ArtificialDecorationSystem {
    private ArtificialDecorationSystem() {}

    public static void placeOnSurface(WorldGenLevel world, BlockPos placePos, List<BlockState> material, RandomSource random, ModConfig cfg) {
        DecorationPlanner.placeOnSurface(world, placePos, material, random, cfg, DecorationPlanner.Mode.ARTIFICIAL);
    }

    public static void addDecoration(WorldGenLevel world,
                                     Set<Decoration> out,
                                     BlockPos placePos,
                                     int segmentIndex,
                                     BlockPos nextPos,
                                     BlockPos prevPos,
                                     List<BlockPos> middlePositions,
                                     int roadWidth,
                                     RandomSource random,
                                     ModConfig cfg) {
        DecorationPlanner.addDecoration(world, out, placePos, segmentIndex, nextPos, prevPos, middlePositions, roadWidth, random, cfg, DecorationPlanner.Mode.ARTIFICIAL);
    }
}
