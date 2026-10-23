package dev.worldadmin.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.worldadmin.compat.DistantHorizonsRoadGate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/** No DH type appears in a Java signature, so the base mod has no hard DH dependency. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.generation.DhWorldGenerator", remap = false)
public abstract class DistantHorizonsRoadMixin {
    @WrapMethod(method = "generateLod(IIIIBLcom/seibel/distanthorizons/api/objects/data/IDhApiFullDataSource;Lcom/seibel/distanthorizons/api/enums/worldGeneration/EDhApiDistantGeneratorMode;Ljava/util/concurrent/ExecutorService;Ljava/util/function/Consumer;)Ljava/util/concurrent/CompletableFuture;", remap = false, require = 1)
    private CompletableFuture<Void> worldadmin$roadsBeforeDhFeatures(int chunkX, int chunkZ, int lodX, int lodZ,
            byte detail, @Coerce Object source, @Coerce Object mode, ExecutorService executor, Consumer<?> consumer,
            Operation<CompletableFuture<Void>> original) {
        return DistantHorizonsRoadGate.wrap(this, chunkX, chunkZ, detail, mode, executor,
                () -> original.call(chunkX, chunkZ, lodX, lodZ, detail, source, mode, executor, consumer));
    }
}
