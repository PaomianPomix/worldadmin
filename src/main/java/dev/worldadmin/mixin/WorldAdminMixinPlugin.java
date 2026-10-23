package dev.worldadmin.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Gate by loader metadata/resources only; never initialize an optional mod while mixins load. */
public final class WorldAdminMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (FMLLoader.getDist() != Dist.DEDICATED_SERVER) return false;
        if (mixinClassName.endsWith(".ServerLevelSeedMixin")) return true;
        var mods = FMLLoader.getLoadingModList();
        if (mods == null) return false;
        boolean supportedDh = mods.getMods().stream().anyMatch(mod -> mod.getModId().equals("distanthorizons")
                && mod.getVersion().toString().equals("3.3.3"));
        if (!supportedDh) return false;
        // Independent DH world seeds also apply without the optional RoadWeaver companion.
        if (mixinClassName.endsWith(".DistantHorizonsSeedMixin")) return true;
        if (mods.getModFileById("roadweaver") == null) return false;
        Path api = mods.findResource("net/shiroha233/roadweaver/api/RoadWeaverWorlds.class");
        return api != null && Files.exists(api);
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
