package dev.worldadmin.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.worldadmin.world.SeededLevelData;
import java.util.OptionalLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Keep DH's structure seed consistent with the managed level's noise and biome state. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.worldGeneration.params.GlobalWorldGenParams_neoforge", remap = false)
public abstract class DistantHorizonsSeedMixin {
    @Shadow @Final public ServerLevel mcServerLevel;

    // DH 3.3.3 assigns mcServerLevel before reading the shared server WorldOptions.
    // Replacing this expression also fixes the subsequent worldSeed assignment, while
    // preserving structure/bonus-chest flags and legacy world-generation options.
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/WorldData;worldGenOptions()Lnet/minecraft/world/level/levelgen/WorldOptions;"),
            remap = false, require = 1)
    private WorldOptions worldadmin$useManagedWorldSeed(WorldOptions original) {
        return mcServerLevel.getLevelData() instanceof SeededLevelData
                ? original.withSeed(OptionalLong.of(mcServerLevel.getSeed()))
                : original;
    }
}
