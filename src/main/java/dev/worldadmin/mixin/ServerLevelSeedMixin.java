package dev.worldadmin.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.worldadmin.world.SeededLevelData;
import java.util.OptionalLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelSeedMixin {
    @Inject(method="getSeed",at=@At("HEAD"),cancellable=true,require=1)
    private void worldadmin$seedDuringConstruction(CallbackInfoReturnable<Long> result) {
        if (((ServerLevel)(Object)this).getLevelData() instanceof SeededLevelData data) {
            result.setReturnValue(data.seed());
        }
    }

    // StructureCheck/RandomSequences and StructureManager each read WorldOptions in the constructor.
    @ModifyExpressionValue(method="<init>",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/storage/WorldData;worldGenOptions()Lnet/minecraft/world/level/levelgen/WorldOptions;"),
            require=2,expect=2)
    private WorldOptions worldadmin$seedConstructorOptions(WorldOptions original) {
        return ((ServerLevel)(Object)this).getLevelData() instanceof SeededLevelData data
                ? original.withSeed(OptionalLong.of(data.seed())) : original;
    }
}
