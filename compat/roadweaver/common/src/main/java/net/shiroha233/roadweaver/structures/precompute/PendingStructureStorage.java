package net.shiroha233.roadweaver.structures.precompute;

import net.shiroha233.roadweaver.runtime.LevelScopedState;
import net.shiroha233.roadweaver.api.RoadWeaverWorlds;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预计算结构存储服务
 */
public final class PendingStructureStorage {
    private PendingStructureStorage() {}
    
    
    private static final LevelScopedState<State> STATES = new LevelScopedState<>();

    private static final class State {
        final ConcurrentHashMap<Long, List<PendingRoadsideStructure>> pending = new ConcurrentHashMap<>();
        final Set<Long> injected = createLimitedSet();
    }

    private static State state(ServerLevel level) {
        return STATES.get(level, State::new);
    }

    private static final int MAX_INJECTED = 4096;
    
    private static Set<Long> createLimitedSet() {
        return java.util.Collections.newSetFromMap(
            java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<Long, Boolean>(256, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                        return size() > MAX_INJECTED;
                    }
                }
            )
        );
    }
    
    public static void addPendingStructure(ServerLevel level, 
                                           ResourceLocation structureId,
                                           BlockPos anchor, 
                                           Rotation rotation,
                                           int sizeX, int sizeY, int sizeZ) {
        if (!isSupportedWorld(level) || structureId == null || anchor == null || rotation == null) return;
        PendingRoadsideStructure pending = new PendingRoadsideStructure(
            structureId, anchor, rotation, sizeX, sizeY, sizeZ
        );
        
        long chunkKey = pending.chunkKey();
        state(level).pending.computeIfAbsent(chunkKey, k -> Collections.synchronizedList(new ArrayList<>()))
               .add(pending);
    }
    
    public static List<PendingRoadsideStructure> getPendingStructures(ServerLevel level, ChunkPos chunkPos) {
        if (!isSupportedWorld(level) || chunkPos == null) return Collections.emptyList();
        long chunkKey = ((long) chunkPos.x << 32) | (chunkPos.z & 0xFFFFFFFFL);
        
        if (state(level).injected.contains(chunkKey)) {
            return Collections.emptyList();
        }
        
        List<PendingRoadsideStructure> structures = state(level).pending.get(chunkKey);
        return structures != null ? new ArrayList<>(structures) : Collections.emptyList();
    }
    
    public static void markAsInjected(ServerLevel level, ChunkPos chunkPos) {
        if (!isSupportedWorld(level) || chunkPos == null) return;
        long chunkKey = ((long) chunkPos.x << 32) | (chunkPos.z & 0xFFFFFFFFL);
        
        state(level).injected.add(chunkKey);
        state(level).pending.remove(chunkKey);
    }
    
    public static boolean hasPendingStructures(ServerLevel level, ChunkPos chunkPos) {
        if (!isSupportedWorld(level) || chunkPos == null) return false;
        long chunkKey = ((long) chunkPos.x << 32) | (chunkPos.z & 0xFFFFFFFFL);
        
        if (state(level).injected.contains(chunkKey)) {
            return false;
        }
        
        List<PendingRoadsideStructure> structures = state(level).pending.get(chunkKey);
        return structures != null && !structures.isEmpty();
    }
    
    public static void clearAll() {
        STATES.clear();
    }

    public static void clearLevel(ServerLevel level) {
        STATES.remove(level);
    }
    
    public static int getPendingCount(ServerLevel level) {
        if (!isSupportedWorld(level)) return 0;
        return state(level).pending.values().stream().mapToInt(List::size).sum();
    }

    private static boolean isSupportedWorld(ServerLevel level) {
        return level != null && RoadWeaverWorlds.supports(level);
    }
}
