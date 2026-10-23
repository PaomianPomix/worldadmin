/* 文件职责：缓存已加载区块的结构边界，让路面铺设 worker 线程无需同步触发主线程 chunk 加载。 */
package net.shiroha233.roadweaver.features.path.pathlogic.core;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import net.shiroha233.roadweaver.runtime.LevelScopedState;
import net.shiroha233.roadweaver.api.RoadWeaverWorlds;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class StructureAvoidanceService {
    private StructureAvoidanceService() {}

    private static final int SEA_LEVEL = 63;
    private static final int CACHE_CAPACITY = 256;
    private static final LevelScopedState<State> STATES = new LevelScopedState<>();

    private static final class State {
        final AtomicLong pendingRequests = new AtomicLong();
        final Map<Long, ChunkEntry> boxes = Collections.synchronizedMap(
                new LinkedHashMap<Long, ChunkEntry>(CACHE_CAPACITY, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, ChunkEntry> eldest) {
                        return size() > CACHE_CAPACITY;
                    }
                });
    }

    private static final class ChunkEntry {
        final Set<BoundingBox> boxes;
        final boolean ready;
        ChunkEntry(Set<BoundingBox> boxes, boolean ready) {
            this.boxes = boxes;
            this.ready = ready;
        }
        static final ChunkEntry UNREADY = new ChunkEntry(Collections.emptySet(), false);
    }

    public static boolean shouldAvoid(WorldGenLevel world, BlockPos pos) {
        if (!ConfigService.get().structureAvoidanceEnabled()) {
            return false;
        }
        if (world == null || pos == null) return false;

        ServerLevel level = world.getLevel();
        if (!RoadWeaverWorlds.supports(level)) return false;
        State state = STATES.get(level, State::new);

        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        long key = ChunkPos.asLong(cx, cz);
        ChunkEntry entry = state.boxes.get(key);

        if (entry == null) {
            requestMainThreadPreload(level, state, cx, cz, key);
            return false;
        }
        if (!entry.ready) {
            return false;
        }
        if (entry.boxes.isEmpty()) return false;

        for (BoundingBox bb : entry.boxes) {
            if (bb.minY() < SEA_LEVEL) continue;
            if (pos.getX() >= bb.minX() && pos.getX() <= bb.maxX()
                    && pos.getZ() >= bb.minZ() && pos.getZ() <= bb.maxZ()) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldAvoidAny(WorldGenLevel world, Iterable<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (shouldAvoid(world, pos)) {
                return true;
            }
        }
        return false;
    }

    public static void invalidate(ServerLevel level, int cx, int cz) {
        State state = STATES.peek(level);
        if (state != null) state.boxes.remove(ChunkPos.asLong(cx, cz));
    }

    public static void clearAll() {
        STATES.clear();
    }

    public static void clear(ServerLevel level) {
        STATES.remove(level);
    }

    public static boolean hasPendingWork(ServerLevel level) {
        State state = STATES.peek(level);
        return state != null && state.pendingRequests.get() > 0;
    }

    private static void requestMainThreadPreload(ServerLevel level, State state, int cx, int cz, long key) {
        if (state.boxes.putIfAbsent(key, ChunkEntry.UNREADY) != null) return;
        if (state.pendingRequests.incrementAndGet() > 32) {
            state.pendingRequests.decrementAndGet();
            state.boxes.remove(key, ChunkEntry.UNREADY);
            return;
        }
        var server = level.getServer();
        if (server == null) {
            state.pendingRequests.decrementAndGet();
            state.boxes.remove(key, ChunkEntry.UNREADY);
            return;
        }
        long epoch = ThreadPoolManager.currentEpoch();
        try {
            server.execute(() -> {
                try {
                    if (!ThreadPoolManager.isEpoch(epoch) || STATES.peek(level) != state
                            || server.getLevel(level.dimension()) != level) return;
                    // Inspect only chunks already resident in memory. StructureManager queries
                    // and ServerLevel.getChunk may synchronously load/generate chunks here.
                    var chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) {
                        state.boxes.remove(key, ChunkEntry.UNREADY);
                        return;
                    }
                    Map<Structure, LongSet> allStructures = chunk.getAllReferences();
                    Set<BoundingBox> boxes = new HashSet<>();
                    for (Map.Entry<Structure, LongSet> reference : allStructures.entrySet()) {
                        for (long chunkLong : reference.getValue()) {
                            ChunkPos referencedPos = new ChunkPos(chunkLong);
                            var referencedChunk = level.getChunkSource().getChunkNow(referencedPos.x, referencedPos.z);
                            if (referencedChunk == null) {
                                state.boxes.remove(key, ChunkEntry.UNREADY);
                                return;
                            }
                            StructureStart start = referencedChunk.getStartForStructure(reference.getKey());
                            if (start == null || !start.isValid()) continue;
                            for (StructurePiece piece : start.getPieces()) {
                                BoundingBox bounds = piece.getBoundingBox();
                                if (bounds.minY() >= SEA_LEVEL) boxes.add(bounds);
                            }
                        }
                    }
                    state.boxes.put(key, new ChunkEntry(boxes, true));
                } catch (Throwable ignored) {
                    state.boxes.remove(key);
                } finally {
                    state.pendingRequests.decrementAndGet();
                }
            });
        } catch (RuntimeException submissionFailure) {
            state.boxes.remove(key, ChunkEntry.UNREADY);
            state.pendingRequests.decrementAndGet();
        }
    }
}
