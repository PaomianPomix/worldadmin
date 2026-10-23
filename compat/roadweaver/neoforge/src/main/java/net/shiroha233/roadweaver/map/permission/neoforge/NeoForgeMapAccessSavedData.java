package net.shiroha233.roadweaver.map.permission.neoforge;

import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.shiroha233.roadweaver.map.permission.MapAccessPolicy;

import java.util.Objects;

public final class NeoForgeMapAccessSavedData extends SavedData {
    private static final String DATA_NAME = "roadweaver_map_access";
    private static final String KEY_POLICY = "policy";

    private MapAccessPolicy policy = MapAccessPolicy.DEFAULT;

    public static NeoForgeMapAccessSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        NeoForgeMapAccessSavedData data = new NeoForgeMapAccessSavedData();
        if (tag.contains(KEY_POLICY)) {
            DynamicOps<Tag> ops = NbtOps.INSTANCE;
            MapAccessPolicy.CODEC.parse(new Dynamic<>(ops, tag.get(KEY_POLICY)))
                    .result()
                    .ifPresent(value -> data.policy = value);
        }
        return data;
    }

    public static NeoForgeMapAccessSavedData get(MinecraftServer server) {
        ServerLevel level = server != null ? server.getLevel(Level.OVERWORLD) : null;
        if (level == null) {
            return new NeoForgeMapAccessSavedData();
        }
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(NeoForgeMapAccessSavedData::new, NeoForgeMapAccessSavedData::load, DataFixTypes.LEVEL),
                DATA_NAME
        );
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        Objects.requireNonNull(tag);
        MapAccessPolicy.CODEC.encodeStart(NbtOps.INSTANCE, policy)
                .result()
                .ifPresent(encoded -> tag.put(KEY_POLICY, encoded));
        return tag;
    }

    public MapAccessPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(MapAccessPolicy policy) {
        this.policy = policy != null ? policy : MapAccessPolicy.DEFAULT;
        setDirty();
    }
}
