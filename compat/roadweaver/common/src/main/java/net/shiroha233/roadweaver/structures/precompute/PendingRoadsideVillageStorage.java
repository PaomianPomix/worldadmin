package net.shiroha233.roadweaver.structures.precompute;

import net.shiroha233.roadweaver.runtime.LevelScopedState;
import net.shiroha233.roadweaver.api.RoadWeaverWorlds;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路边村庄待注入存储
 */
public final class PendingRoadsideVillageStorage {
    private PendingRoadsideVillageStorage() {}

    private static final LevelScopedState<State> STATES = new LevelScopedState<>();

    private static final class State {
        final Map<Long, List<PendingRoadsideVillage>> pending = new ConcurrentHashMap<>();
        final Set<ResourceLocation> placed = createPlacementSet();
        final Set<Long> injectedChunks = createLimitedChunkSet();
    }

    private static State state(ServerLevel level) {
        return STATES.get(level, State::new);
    }

    private static final int MAX_INJECTED = 2048;

    private static Set<ResourceLocation> createPlacementSet() {
        return Collections.newSetFromMap(new ConcurrentHashMap<>());
    }

    private static Set<Long> createLimitedChunkSet() {
        return Collections.newSetFromMap(
            Collections.synchronizedMap(
                new java.util.LinkedHashMap<Long, Boolean>(256, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                        return size() > MAX_INJECTED;
                    }
                }
            )
        );
    }

    public static boolean addPendingVillage(ServerLevel level, PendingRoadsideVillage village) {
        if (!isSupportedWorld(level) || village == null || village.placementId() == null) return false;
        if (!state(level).placed.add(village.placementId())) {
            return false;
        }

        long chunkKey = village.chunkKey();
        state(level).pending.computeIfAbsent(chunkKey, k -> Collections.synchronizedList(new ArrayList<>()))
            .add(village);
        return true;
    }

    public static List<PendingRoadsideVillage> getPendingVillages(ServerLevel level, ChunkPos chunkPos) {
        if (!isSupportedWorld(level) || chunkPos == null) return Collections.emptyList();
        long chunkKey = chunkPos.toLong();

        if (state(level).injectedChunks.contains(chunkKey)) {
            return Collections.emptyList();
        }

        List<PendingRoadsideVillage> villages = state(level).pending.get(chunkKey);
        return villages == null ? Collections.emptyList() : new ArrayList<>(villages);
    }

    public static void markAsInjected(ServerLevel level, ChunkPos chunkPos) {
        if (!isSupportedWorld(level) || chunkPos == null) return;
        long chunkKey = chunkPos.toLong();
        state(level).injectedChunks.add(chunkKey);
        state(level).pending.remove(chunkKey);
    }

    public static void clearAll() {
        STATES.clear();
    }

    public static void clearLevel(ServerLevel level) {
        STATES.remove(level);
    }

    private static boolean isSupportedWorld(ServerLevel level) {
        return level != null && RoadWeaverWorlds.supports(level);
    }
}
