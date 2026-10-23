/* 文件职责：结构状态内存权威副本、单写者延迟落盘与扫描租约管理。 */
package net.shiroha233.roadweaver.persistence.files;

// Memory-state operations adapted from upstream RoadWeaver a6560085c8c70d92da8758d22838c4a39c39acb2.
// This companion adds identity ownership, asynchronous preload and a bounded single-writer lifecycle.
import net.shiroha233.roadweaver.runtime.LevelScopedState;
import net.shiroha233.roadweaver.runtime.LevelWriteFence;
import net.shiroha233.roadweaver.runtime.ThreadPoolManager;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.planning.path.PlannedPathKey;
import net.shiroha233.roadweaver.core.model.StructureLocationData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 结构状态存储：内存为权威副本，写入仅标脏，由单写者线程周期 checkpoint。
 * 主线程读写均为内存操作，磁盘 IO 不再出现在任何热路径上。
 */
public final class StructureFileStorage {
    private StructureFileStorage() {}

    public static final int SOURCE_PREDICTED = 0;
    public static final int SOURCE_MANUAL = 1;
    public static final int SCAN_TILE_SIZE_CHUNKS = 128;

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CATEGORY = "structures";
    private static final String STATE_FILE = "state.json";
    private static final long FLUSH_INTERVAL_MS = 1000;

    private static final LevelScopedState<Entry> ENTRIES = new LevelScopedState<>();
    private static final ArrayBlockingQueue<Entry> LOAD_QUEUE = new ArrayBlockingQueue<>(64);
    // Failed shutdown checkpoints retain detached data only; never a ServerLevel reference.
    private static final Set<Entry> RETIRED = ConcurrentHashMap.newKeySet();
    private static volatile boolean stopping;

    static final class StateData {
        StructureLocationData structureLocations = new StructureLocationData(new ArrayList<>(), new ArrayList<>());
        List<StructureConnection> connections = new ArrayList<>();
        Set<Long> plannedTileKeys = new HashSet<>();
        Map<Long, Long> plannedTileCenters = new HashMap<>();
        Map<Long, Integer> structureSources = new HashMap<>();
        Map<String, String> meta = new HashMap<>();
        Map<Long, Long> scanTiles = new HashMap<>();
    }

    static final class Entry {
        final Path path;
        final Path legacyPath;
        final Object lock = new Object();
        final CompletableFuture<Void> loading = new CompletableFuture<>();
        volatile StateData data;
        Map<PlannedPathKey, Integer> connectionIndices = new HashMap<>();
        long version;
        long flushed;
        long dirtyAt;
        volatile boolean closed;
        boolean forceFlush;
        CompletableFuture<Void> checkpoint;
        long checkpointVersion;
        StructureSnapshot snapshot;
        long snapshotVersion = -1;

        Entry(Path path, Path legacyPath) {
            this.path = path.toAbsolutePath().normalize();
            this.legacyPath = legacyPath;
        }
    }

    private static final ScheduledExecutorService FLUSHER = initFlusher();

    private static ScheduledExecutorService initFlusher() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "roadweaver-structure-state-io");
            thread.setDaemon(true);
            return thread;
        });
        // Exactly one repeating task; requests are coalesced per entry, not queued per mutation.
        executor.scheduleWithFixedDelay(() -> {
            try { serviceDiskWork(); }
            catch (Throwable failure) { LOGGER.warn("Structure state disk worker failed", failure); }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return executor;
    }

    public record StructureSnapshot(StructureLocationData locations, Map<Long, Integer> sources) {
        public StructureSnapshot {
            locations = locations == null
                    ? new StructureLocationData(new ArrayList<>(), new ArrayList<>())
                    : new StructureLocationData(locations.structureLocations(), locations.structureInfos());
            sources = sources == null ? Map.of() : Map.copyOf(sources);
        }

        public int sourceAt(BlockPos pos) {
            return pos == null ? -1 : sources.getOrDefault(posKey(pos), SOURCE_PREDICTED);
        }

        @Override
        public StructureLocationData locations() {
            return new StructureLocationData(locations.structureLocations(), locations.structureInfos());
        }
    }

    // ==================== 读取 API（内存操作） ====================

    public static StructureLocationData getStructureLocations(ServerLevel level) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StructureLocationData data = loaded(entry).structureLocations;
            return new StructureLocationData(data.structureLocations(), data.structureInfos());
        }
    }

    public static List<StructureConnection> getStructureConnections(ServerLevel level) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            return new ArrayList<>(loaded(entry).connections);
        }
    }

    /** Constant-time connection lookup; the index is prepared during load or batch replacement. */
    public static StructureConnection getConnection(ServerLevel level, PlannedPathKey key) {
        if (level == null || key == null) return null;
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData data = loaded(entry);
            Integer index = entry.connectionIndices.get(key);
            return index == null ? null : data.connections.get(index);
        }
    }

    /** Change one connection in place without copying or scanning every road in the world. */
    public static boolean transitionConnection(ServerLevel level, PlannedPathKey key, ConnectionStatus status) {
        if (level == null || key == null || status == null) return false;
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData data = loaded(entry);
            Integer index = entry.connectionIndices.get(key);
            if (index == null) return false;
            StructureConnection previous = data.connections.get(index);
            if (previous.status() != status) {
                data.connections.set(index, new StructureConnection(previous.from(), previous.to(), status));
                markDirty(entry);
            }
            return true;
        }
    }

    public static Set<Long> getPlannedTileKeys(ServerLevel level) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            return new HashSet<>(loaded(entry).plannedTileKeys);
        }
    }

    /** 单键成员查询：供「每玩家每秒一次」的热路径避免深拷贝整个键集合。 */
    public static boolean isPlannedTile(ServerLevel level, long tileKey) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            return loaded(entry).plannedTileKeys.contains(tileKey);
        }
    }

    /** 原子登记，避免「拷贝整个键集合 -> 加一条 -> 整体写回」。 */
    public static void markPlannedTile(ServerLevel level, long tileKey, int centerChunkX, int centerChunkZ) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData state = loaded(entry);
            if (state.plannedTileKeys.add(tileKey)) {
                markDirty(entry);
            }
            long center = (((long) centerChunkX) << 32) ^ (centerChunkZ & 0xffffffffL);
            if (state.plannedTileCenters.get(tileKey) == null) {
                state.plannedTileCenters.put(tileKey, center);
                markDirty(entry);
            }
        }
    }

    /** 就地裁剪，不做全量拷贝。 */
    public static void prunePlannedTilesIfTooLarge(ServerLevel level, int maxKeys) {
        if (maxKeys <= 0) return;
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData state = loaded(entry);

            int excess = state.plannedTileKeys.size() - maxKeys;
            if (excess > 0) {
                Iterator<Long> it = state.plannedTileKeys.iterator();
                while (excess > 0 && it.hasNext()) {
                    Long removed = it.next();
                    it.remove();
                    state.plannedTileCenters.remove(removed);
                    excess--;
                }
                markDirty(entry);
            }

            excess = state.plannedTileCenters.size() - maxKeys;
            if (excess > 0) {
                Iterator<Long> it = state.plannedTileCenters.keySet().iterator();
                while (excess > 0 && it.hasNext()) {
                    it.next();
                    it.remove();
                    excess--;
                }
                markDirty(entry);
            }
        }
    }

    public static Map<Long, Long> getPlannedTileCenters(ServerLevel level) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            return new HashMap<>(loaded(entry).plannedTileCenters);
        }
    }

    public static StructureSnapshot getStructureSnapshot(ServerLevel level) {
        if (level == null) return new StructureSnapshot(null, Map.of());
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData state = loaded(entry);
            if (entry.snapshot == null || entry.snapshotVersion != entry.version) {
                entry.snapshot = new StructureSnapshot(state.structureLocations, state.structureSources);
                entry.snapshotVersion = entry.version;
            }
            return entry.snapshot;
        }
    }

    public static int sourceAt(ServerLevel level, BlockPos pos) {
        return getStructureSnapshot(level).sourceAt(pos);
    }

    public static List<StructureInfo> queryRect(ServerLevel level, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int... sources) {
        if (level == null) return List.of();
        Set<Integer> filter = normalizeSources(sources);
        List<StructureInfo> out = new ArrayList<>();
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData state = loaded(entry);
            for (StructureInfo info : state.structureLocations.structureInfos()) {
                if (info == null || info.pos() == null) continue;
                int x = info.pos().getX();
                int z = info.pos().getZ();
                if (x < minBlockX || x > maxBlockX || z < minBlockZ || z > maxBlockZ) continue;
                int source = state.structureSources.getOrDefault(posKey(info.pos()), SOURCE_PREDICTED);
                if (filter.contains(source)) out.add(info);
            }
        }
        return out;
    }

    public static boolean hasAnyStructure(ServerLevel level) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            return !loaded(entry).structureLocations.structureInfos().isEmpty();
        }
    }

    public static boolean hasAnyManualStructure(ServerLevel level) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData state = loaded(entry);
            for (StructureInfo info : state.structureLocations.structureInfos()) {
                if (info != null && info.pos() != null && state.structureSources.getOrDefault(posKey(info.pos()), SOURCE_PREDICTED) == SOURCE_MANUAL) {
                    return true;
                }
            }
            for (BlockPos pos : state.structureLocations.structureLocations()) {
                if (pos != null && state.structureSources.getOrDefault(posKey(pos), SOURCE_PREDICTED) == SOURCE_MANUAL) {
                    return true;
                }
            }
            return false;
        }
    }

    public static Set<Long> manualStructureKeys(ServerLevel level) {
        Entry entry = entry(level);
        HashSet<Long> out = new HashSet<>();
        synchronized (entry.lock) {
            StateData state = loaded(entry);
            for (StructureInfo info : state.structureLocations.structureInfos()) {
                if (info != null && info.pos() != null && state.structureSources.getOrDefault(posKey(info.pos()), SOURCE_PREDICTED) == SOURCE_MANUAL) {
                    out.add(posKey(info.pos()));
                }
            }
            for (BlockPos pos : state.structureLocations.structureLocations()) {
                if (pos != null && state.structureSources.getOrDefault(posKey(pos), SOURCE_PREDICTED) == SOURCE_MANUAL) {
                    out.add(posKey(pos));
                }
            }
        }
        return out;
    }

    // ==================== 写入 API（改内存 + 标脏） ====================

    public static void setStructureLocations(ServerLevel level, StructureLocationData data) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData state = loaded(entry);
            Map<Long, Integer> previousSources = new HashMap<>(state.structureSources);
            state.structureLocations = data != null ? normalizeStructureLocations(data) : new StructureLocationData(new ArrayList<>(), new ArrayList<>());
            state.structureSources = rebuildSources(state.structureLocations, previousSources, SOURCE_MANUAL);
            markDirty(entry);
        }
    }

    public static void setStructureConnections(ServerLevel level, List<StructureConnection> connections) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData state = loaded(entry);
            state.connections = connections != null ? new ArrayList<>(connections) : new ArrayList<>();
            rebuildConnectionIndex(entry, state);
            markDirty(entry);
        }
    }

    public static void mergeStructureConnections(ServerLevel level, List<StructureConnection> connections) {
        if (level == null || connections == null || connections.isEmpty()) return;
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData state = loaded(entry);
            mergeConnections(state.connections, connections);
            rebuildConnectionIndex(entry, state);
            markDirty(entry);
        }
    }

    public static void setPlannedTileKeys(ServerLevel level, Set<Long> keys) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            loaded(entry).plannedTileKeys = keys != null ? new HashSet<>(keys) : new HashSet<>();
            markDirty(entry);
        }
    }

    public static void setPlannedTileCenters(ServerLevel level, Map<Long, Long> centers) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            loaded(entry).plannedTileCenters = centers != null ? new HashMap<>(centers) : new HashMap<>();
            markDirty(entry);
        }
    }

    public static void addStructures(ServerLevel level, List<StructureInfo> infos, int source) {
        if (level == null || infos == null || infos.isEmpty()) return;
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData state = loaded(entry);
            putStructures(state, infos, source);
            markDirty(entry);
        }
    }

    public static void ensurePolicy(ServerLevel level, String policyHash) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData state = loaded(entry);
            String hash = policyHash == null ? "" : policyHash;
            String current = state.meta.getOrDefault("policy_hash", "");
            if (!current.equals(hash)) {
                keepOnlyManualStructures(state);
                state.scanTiles.clear();
                state.meta.put("policy_hash", hash);
                markDirty(entry);
            }
        }
    }

    public static boolean claimScanTile(ServerLevel level, int tileX, int tileZ) {
        return claimScanKey(level, chunkKey(tileX, tileZ));
    }

    public static void markScanTileDone(ServerLevel level, int tileX, int tileZ) {
        markScanKeyDone(level, chunkKey(tileX, tileZ));
    }

    public static void releaseScanTile(ServerLevel level, int tileX, int tileZ) {
        releaseScanKey(level, chunkKey(tileX, tileZ));
    }

    public static boolean claimScanWindow(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        return claimScanKey(level, scanWindowKey(minChunkX, minChunkZ, maxChunkX, maxChunkZ));
    }

    public static void markScanWindowDone(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        markScanKeyDone(level, scanWindowKey(minChunkX, minChunkZ, maxChunkX, maxChunkZ));
    }

    public static void releaseScanWindow(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        releaseScanKey(level, scanWindowKey(minChunkX, minChunkZ, maxChunkX, maxChunkZ));
    }

    // ==================== Asynchronous lifecycle ====================

    public static void onServerStarted() { stopping = false; }

    public static CompletableFuture<Void> ensureLoadedAsync(ServerLevel level) {
        if (level == null) return CompletableFuture.failedFuture(new IllegalArgumentException("level"));
        try { return entryForLoad(level).loading.copy(); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }

    public static boolean isReady(ServerLevel level) {
        Entry entry = ENTRIES.peek(level);
        return entry != null && entry.data != null && !entry.closed && !stopping
                && !ThreadPoolManager.isStopping() && ThreadPoolManager.isCurrentWorkerSession();
    }

    public static boolean hasPendingWork(ServerLevel level) {
        return hasPendingEntry(level);
    }

    static boolean hasPendingEntry(Object level) {
        Entry entry = ENTRIES.peek(level);
        if (entry == null) return false;
        synchronized (entry.lock) {
            return !entry.loading.isDone() || entry.version != entry.flushed;
        }
    }

    public static CompletableFuture<Void> flushAsync(ServerLevel level) {
        Entry entry = ENTRIES.peek(level);
        return entry == null ? CompletableFuture.completedFuture(null) : requestFlush(entry).copy();
    }

    /** Close is nonblocking: the caller must first observe a drained persistence lease. */
    public static void close(ServerLevel level) {
        closeEntry(level);
    }

    static void closeEntry(Object level) {
        Entry entry = ENTRIES.peek(level);
        if (entry == null) return;
        synchronized (entry.lock) {
            if (!entry.loading.isDone() || entry.version != entry.flushed) {
                throw new IllegalStateException("Structure state checkpoint must finish before unloading");
            }
            entry.closed = true;
        }
        if (level instanceof ServerLevel serverLevel) LevelWriteFence.close(serverLevel);
        ENTRIES.remove(level);
    }

    public static CompletableFuture<Void> closeAsync(ServerLevel level) {
        return flushAsync(level).thenRun(() -> close(level));
    }

    /** Only server shutdown waits for I/O; ordinary reads, ticks and dimension unload never do. */
    public static void shutdown() {
        stopping = true;
        for (Object key : ENTRIES.keysSnapshot()) {
            if (key instanceof ServerLevel level) LevelWriteFence.close(level);
        }
        List<Entry> entries = ENTRIES.valuesSnapshot();
        for (Entry entry : entries) {
            synchronized (entry.lock) { entry.closed = true; }
        }
        CompletableFuture<?>[] checkpoints = entries.stream().map(StructureFileStorage::requestFlush)
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(checkpoints).get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            LOGGER.error("Structure checkpoints did not finish at shutdown; retaining detached dirty data for retry", failure);
            for (Entry entry : entries) {
                synchronized (entry.lock) {
                    if (entry.version != entry.flushed) RETIRED.add(entry);
                }
            }
        }
        ENTRIES.clear();
    }

    static CompletableFuture<Void> requestFlush(Entry entry) {
        if (!entry.loading.isDone()) return entry.loading.thenCompose(ignored -> requestFlush(entry));
        if (entry.loading.isCompletedExceptionally()) return entry.loading;
        synchronized (entry.lock) {
            if (entry.version == entry.flushed) return CompletableFuture.completedFuture(null);
            entry.forceFlush = true;
            entry.checkpointVersion = entry.version;
            if (entry.checkpoint == null || entry.checkpoint.isDone()) entry.checkpoint = new CompletableFuture<>();
            return entry.checkpoint;
        }
    }

    private static void serviceDiskWork() {
        for (int i = 0; i < 4; i++) {
            Entry entry = LOAD_QUEUE.poll();
            if (entry == null) break;
            try {
                if (Files.notExists(entry.path) && Files.exists(entry.legacyPath)) {
                    throw new IOException("Legacy RoadWeaver SavedData exists without authoritative JSON: "
                            + entry.legacyPath + ". Explicit offline migration is required; the legacy file is unchanged.");
                }
                StateData data = readFileState(entry.path);
                StateData normalized = normalize(data == null ? new StateData() : data);
                Map<PlannedPathKey, Integer> connectionIndices = buildConnectionIndex(normalized);
                synchronized (entry.lock) {
                    entry.connectionIndices = connectionIndices;
                    entry.data = normalized;
                }
                entry.loading.complete(null);
            } catch (Throwable failure) {
                LOGGER.error("Cannot load structure state {}", entry.path, failure);
                entry.loading.completeExceptionally(failure);
            }
        }
        for (Entry entry : ENTRIES.valuesSnapshot()) flushEntry(entry);
        for (Entry entry : RETIRED) {
            flushEntry(entry);
            synchronized (entry.lock) {
                if (entry.version == entry.flushed) RETIRED.remove(entry);
            }
        }
    }

    private record Snapshot(StateData data, long version) {}

    private static void flushEntry(Entry entry) {
        Snapshot snapshot;
        synchronized (entry.lock) {
            if (entry.data == null || entry.version == entry.flushed) return;
            if (!entry.forceFlush && System.currentTimeMillis() - entry.dirtyAt < FLUSH_INTERVAL_MS) return;
            snapshot = new Snapshot(shallowCopy(entry.data), entry.version);
            entry.forceFlush = false;
        }
        CompletableFuture<Void> checkpoint = null;
        try {
            // Serialization and atomic file replacement occur only on this one disk worker.
            FileStorageIO.writeStringAtomic(entry.path, GSON.toJson(snapshot.data()));
            synchronized (entry.lock) {
                entry.flushed = snapshot.version();
                if (entry.checkpoint != null && entry.flushed >= entry.checkpointVersion) {
                    checkpoint = entry.checkpoint;
                    entry.checkpoint = null;
                }
            }
            if (checkpoint != null) checkpoint.complete(null);
        } catch (Exception failure) {
            synchronized (entry.lock) {
                entry.dirtyAt = System.currentTimeMillis();
                checkpoint = entry.checkpoint;
                entry.checkpoint = null;
            }
            LOGGER.warn("Structure checkpoint failed; dirty state retained: {}", entry.path, failure);
            if (checkpoint != null) checkpoint.completeExceptionally(failure);
        }
    }

    private static StateData shallowCopy(StateData data) {
        StateData copy = new StateData();
        StructureLocationData locations = data.structureLocations;
        copy.structureLocations = new StructureLocationData(
                new ArrayList<>(locations.structureLocations()),
                new ArrayList<>(locations.structureInfos()));
        copy.connections = new ArrayList<>(data.connections);
        copy.plannedTileKeys = new HashSet<>(data.plannedTileKeys);
        copy.plannedTileCenters = new HashMap<>(data.plannedTileCenters);
        copy.structureSources = new HashMap<>(data.structureSources);
        copy.meta = new HashMap<>(data.meta);
        copy.scanTiles = new HashMap<>(data.scanTiles);
        return copy;
    }

    static void markDirty(Entry entry) {
        if (entry.version == entry.flushed) entry.dirtyAt = System.currentTimeMillis();
        entry.version++;
        entry.snapshot = null;
    }

    private static Entry entryForLoad(ServerLevel level) {
        LevelWriteFence.requireActive(level);
        return openEntry(level, FileStoragePathResolver.categoryRoot(level, CATEGORY).resolve(STATE_FILE),
                FileStoragePathResolver.legacyStructureState(level));
    }

    static Entry openEntry(Object level, Path path, Path legacyPath) {
        LevelWriteFence.requireCurrentSession();
        if (stopping) throw new CancellationException("Structure state storage is stopping");
        return ENTRIES.get(level, () -> {
            Entry created = new Entry(path, legacyPath);
            for (Entry retired : RETIRED) {
                if (retired.path.equals(created.path)) {
                    throw new IllegalStateException("A previous session still has an unflushed structure checkpoint: " + created.path);
                }
            }
            if (!LOAD_QUEUE.offer(created)) throw new RejectedExecutionException("Structure preload queue is full");
            return created;
        });
    }

    private static Entry entry(ServerLevel level) {
        Entry entry = entryForLoad(level);
        if (entry.data == null) {
            if (level.getServer().isSameThread()) {
                throw new IllegalStateException("Structure state is not ready; compose ensureLoadedAsync before main-thread access");
            }
            // Only background callers may wait, before acquiring the state lock.
            entry.loading.join();
        }
        return entry;
    }

    private static StateData loaded(Entry entry) {
        LevelWriteFence.requireCurrentSession();
        if (entry.closed || entry.data == null) throw new IllegalStateException("Structure state is unavailable");
        return entry.data;
    }

    private static void rebuildConnectionIndex(Entry entry, StateData data) {
        entry.connectionIndices = buildConnectionIndex(data);
    }

    private static Map<PlannedPathKey, Integer> buildConnectionIndex(StateData data) {
        Map<PlannedPathKey, Integer> indices = new HashMap<>();
        for (int i = 0; i < data.connections.size(); i++) {
            StructureConnection connection = data.connections.get(i);
            if (validConnection(connection)) indices.putIfAbsent(PlannedPathKey.of(connection), i);
        }
        return indices;
    }

    private static StateData readFileState(Path path) throws IOException {
        if (Files.notExists(path)) return null;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StateData data = GSON.fromJson(reader, StateData.class);
            if (data == null) throw new IOException("Empty structure state file: " + path);
            return data;
        } catch (com.google.gson.JsonParseException invalid) {
            throw new IOException("Invalid structure state file: " + path, invalid);
        }
    }

    // ==================== 私有辅助 ====================

    private static boolean claimScanKey(ServerLevel level, long key) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            StateData state = loaded(entry);
            long now = System.currentTimeMillis() / 1000L;
            Long scannedAt = state.scanTiles.get(key);
            if (scannedAt != null && scannedAt > 0) return false;
            if (scannedAt != null) {
                long start = -scannedAt;
                if (now - start < 10 * 60) return false;
            }
            state.scanTiles.put(key, -now);
            markDirty(entry);
            return true;
        }
    }

    private static void markScanKeyDone(ServerLevel level, long key) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            loaded(entry).scanTiles.put(key, System.currentTimeMillis() / 1000L);
            markDirty(entry);
        }
    }

    private static void releaseScanKey(ServerLevel level, long key) {
        Entry entry = entry(level);
        synchronized (entry.lock) {
            loaded(entry).scanTiles.remove(key);
            markDirty(entry);
        }
    }

    private static boolean samePos(BlockPos pos, int x, int z) {
        return pos != null && pos.getX() == x && pos.getZ() == z;
    }

    private static long posKey(BlockPos pos) {
        return (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xffffffffL);
    }

    private static int normalizeSource(int source) {
        return source == SOURCE_MANUAL ? SOURCE_MANUAL : SOURCE_PREDICTED;
    }

    private static long scanWindowKey(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        long hash = 0xcbf29ce484222325L;
        hash = mixScanHash(hash, minChunkX);
        hash = mixScanHash(hash, minChunkZ);
        hash = mixScanHash(hash, maxChunkX);
        hash = mixScanHash(hash, maxChunkZ);
        return hash;
    }

    private static long mixScanHash(long hash, int value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    private static Set<Integer> normalizeSources(int[] sources) {
        if (sources == null || sources.length == 0) {
            return Set.of(SOURCE_PREDICTED, SOURCE_MANUAL);
        }
        Set<Integer> out = new HashSet<>();
        for (int source : sources) out.add(normalizeSource(source));
        return out;
    }

    private static long chunkKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static StateData normalize(StateData state) {
        if (state.structureLocations == null) {
            state.structureLocations = new StructureLocationData(new ArrayList<>(), new ArrayList<>());
        } else {
            state.structureLocations = normalizeStructureLocations(state.structureLocations);
        }
        if (state.connections == null) state.connections = new ArrayList<>();
        if (state.plannedTileKeys == null) state.plannedTileKeys = new HashSet<>();
        if (state.plannedTileCenters == null) state.plannedTileCenters = new HashMap<>();
        if (state.structureSources == null) state.structureSources = new HashMap<>();
        if (state.meta == null) state.meta = new HashMap<>();
        if (state.scanTiles == null) state.scanTiles = new HashMap<>();
        if (state.structureSources.isEmpty()) {
            state.structureSources = rebuildSources(state.structureLocations, Map.of(), SOURCE_PREDICTED);
        }
        return state;
    }

    private static StructureLocationData normalizeStructureLocations(StructureLocationData data) {
        StructureLocationData normalized = new StructureLocationData(data.structureLocations(), data.structureInfos());
        // 先建索引再单遍补齐，替代 O(位置数 × info 数) 的双重扫描
        HashSet<Long> knownKeys = new HashSet<>(Math.max(16, normalized.structureInfos().size() * 2));
        for (StructureInfo info : normalized.structureInfos()) {
            if (info != null && info.pos() != null) {
                knownKeys.add(posKey(info.pos()));
            }
        }
        for (BlockPos pos : normalized.structureLocations()) {
            if (pos == null) continue;
            if (knownKeys.add(posKey(pos))) {
                normalized.structureInfos().add(new StructureInfo(pos, "unknown"));
            }
        }
        return normalized;
    }

    private static Map<Long, Integer> rebuildSources(StructureLocationData data, Map<Long, Integer> previous, int defaultSource) {
        HashMap<Long, Integer> out = new HashMap<>();
        if (data != null) {
            for (StructureInfo info : data.structureInfos()) {
                if (info == null || info.pos() == null) continue;
                long key = posKey(info.pos());
                out.put(key, previous.getOrDefault(key, normalizeSource(defaultSource)));
            }
            for (BlockPos pos : data.structureLocations()) {
                if (pos == null) continue;
                long key = posKey(pos);
                out.putIfAbsent(key, previous.getOrDefault(key, normalizeSource(defaultSource)));
            }
        }
        return out;
    }

    /**
     * 批量写入结构，复杂度 O(已有结构数 + 本批数量)。
     * 逐条调用 {@link #putStructure} 会对两个列表各做一次全量 removeIf
     * 线性扫描，批量写入时退化为 O(已有总数 × 本批数量)。
     * 此处先按 posKey 定位本批槽位、在内存中复现单条写入的决策后统一落盘，
     * 语义与逐条调用一致（含同一位置重复写入时后写覆盖并排到队尾）。
     */
    private static void putStructures(StateData state, List<StructureInfo> infos, int defaultSource) {
        StructureLocationData locations = state.structureLocations;
        List<StructureInfo> existingInfos = locations.structureInfos();
        List<BlockPos> existingPositions = locations.structureLocations();

        HashMap<Long, Slot> slots = new HashMap<>(Math.max(16, infos.size() * 2));
        // 保持逐条调用时的追加顺序：同一 key 重复写入需移动到队尾
        LinkedHashSet<Long> appendOrder = new LinkedHashSet<>();

        for (StructureInfo incoming : infos) {
            if (incoming == null || incoming.pos() == null) continue;
            long key = posKey(incoming.pos());
            Slot slot = slots.get(key);
            if (slot == null) {
                slot = new Slot(findStructureInfo(existingInfos, incoming.pos().getX(), incoming.pos().getZ()),
                        state.structureSources.get(key));
                slots.put(key, slot);
            }

            int source = defaultSource;
            boolean incomingKnown = StructureInfo.isKnownId(incoming.structureId());
            boolean existingKnown = slot.info != null && StructureInfo.isKnownId(slot.info.structureId());

            if (source == SOURCE_PREDICTED && slot.source != null && slot.source == SOURCE_MANUAL) {
                if (!incomingKnown || existingKnown) continue;
                source = SOURCE_MANUAL;
            } else if (!incomingKnown && existingKnown) {
                if (source == SOURCE_MANUAL) {
                    slot.source = SOURCE_MANUAL;
                    state.structureSources.put(key, SOURCE_MANUAL);
                }
                continue;
            }

            slot.info = incoming;
            slot.source = normalizeSource(source);
            // remove + add 即为「移动到队尾」，与逐条调用时的语义一致
            appendOrder.remove(key);
            appendOrder.add(key);
        }

        if (appendOrder.isEmpty()) return;

        for (long key : appendOrder) {
            final long posKey = key;
            existingInfos.removeIf(info -> info != null && info.pos() != null && posKey(info.pos()) == posKey);
            existingPositions.removeIf(pos -> pos != null && posKey(pos) == posKey);
            state.structureSources.put(key, slots.get(key).source);
        }
        for (long key : appendOrder) {
            StructureInfo info = slots.get(key).info;
            existingInfos.add(info);
            existingPositions.add(info.pos());
        }
    }

    /** 批量写入过程中单个位置的临时状态。 */
    private static final class Slot {
        StructureInfo info;
        Integer source;

        Slot(StructureInfo info, Integer source) {
            this.info = info;
            this.source = source;
        }
    }

    private static void putStructure(StateData state, StructureInfo info, int source) {
        int x = info.pos().getX();
        int z = info.pos().getZ();
        long key = posKey(info.pos());
        Integer existingSource = state.structureSources.get(key);
        StructureInfo existingInfo = findStructureInfo(state.structureLocations.structureInfos(), x, z);
        boolean incomingKnown = StructureInfo.isKnownId(info.structureId());
        boolean existingKnown = existingInfo != null && StructureInfo.isKnownId(existingInfo.structureId());
        if (source == SOURCE_PREDICTED && existingSource != null && existingSource == SOURCE_MANUAL) {
            if (!incomingKnown || existingKnown) return;
            source = SOURCE_MANUAL;
        } else if (!incomingKnown && existingKnown) {
            if (source == SOURCE_MANUAL) state.structureSources.put(key, SOURCE_MANUAL);
            return;
        }
        state.structureLocations.structureInfos().removeIf(existing -> existing != null && samePos(existing.pos(), x, z));
        state.structureLocations.structureLocations().removeIf(pos -> samePos(pos, x, z));
        state.structureLocations.structureInfos().add(info);
        state.structureLocations.structureLocations().add(info.pos());
        state.structureSources.put(key, normalizeSource(source));
    }

    private static StructureInfo findStructureInfo(List<StructureInfo> infos, int x, int z) {
        for (StructureInfo info : infos) {
            if (info != null && samePos(info.pos(), x, z)) return info;
        }
        return null;
    }

    private static void keepOnlyManualStructures(StateData state) {
        ArrayList<StructureInfo> manualInfos = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        for (StructureInfo info : state.structureLocations.structureInfos()) {
            if (info == null || info.pos() == null) continue;
            long key = posKey(info.pos());
            if (state.structureSources.getOrDefault(key, SOURCE_PREDICTED) == SOURCE_MANUAL && seen.add(key)) {
                manualInfos.add(info);
            }
        }
        for (BlockPos pos : state.structureLocations.structureLocations()) {
            if (pos == null) continue;
            long key = posKey(pos);
            if (state.structureSources.getOrDefault(key, SOURCE_PREDICTED) == SOURCE_MANUAL && seen.add(key)) {
                manualInfos.add(new StructureInfo(pos, "unknown"));
            }
        }

        state.structureLocations = new StructureLocationData(new ArrayList<>(), new ArrayList<>());
        state.structureSources.clear();
        for (StructureInfo info : manualInfos) {
            putStructure(state, info, SOURCE_MANUAL);
        }
    }

    private static void mergeConnections(List<StructureConnection> target, List<StructureConnection> incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        LinkedHashMap<PlannedPathKey, StructureConnection> merged = new LinkedHashMap<>();
        for (StructureConnection connection : target) {
            if (validConnection(connection)) merged.put(PlannedPathKey.of(connection), connection);
        }
        for (StructureConnection connection : incoming) {
            if (!validConnection(connection)) continue;
            PlannedPathKey key = PlannedPathKey.of(connection);
            StructureConnection previous = merged.get(key);
            if (previous == null || statusPriority(connection.status()) >= statusPriority(previous.status())) {
                merged.put(key, connection);
            }
        }
        target.clear();
        target.addAll(merged.values());
    }

    private static boolean validConnection(StructureConnection connection) {
        return connection != null && connection.from() != null && connection.to() != null;
    }

    private static int statusPriority(ConnectionStatus status) {
        if (status == null) return 0;
        return switch (status) {
            case COMPLETED -> 4;
            case GENERATING -> 3;
            case PLANNED -> 2;
            case FAILED -> 1;
        };
    }
}
