package dev.worldadmin.world;

import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;

/** The seed is available to Level and ChunkMap before ServerLevel construction finishes. */
public final class SeededLevelData extends DerivedLevelData {
    private final long seed;

    public SeededLevelData(WorldData worldData, ServerLevelData sharedData, long seed) {
        super(worldData,sharedData);
        this.seed=seed;
    }

    public long seed() { return seed; }
}
