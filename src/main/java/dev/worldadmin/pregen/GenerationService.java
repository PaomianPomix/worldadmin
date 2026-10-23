package dev.worldadmin.pregen;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import dev.worldadmin.WorldAdminConfig;
import dev.worldadmin.compat.DistantHorizonsBridge;
import dev.worldadmin.compat.RoadWeaverBridge;
import dev.worldadmin.io.AsyncJsonStore;
import dev.worldadmin.pregen.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/** All mutable state belongs to the server thread. Futures are polled, never waited on. */
public final class GenerationService implements AutoCloseable {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("worldadmin", Comparator.comparingLong(ChunkPos::toLong));
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final MinecraftServer server;
    private final LoadGate gate;
    private final DistantHorizonsBridge dh = new DistantHorizonsBridge();
    private final List<Request> requests = new ArrayList<>();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Deque<Teleport> teleports = new ArrayDeque<>();
    private final Set<UUID> pendingTeleports = new HashSet<>();
    private final AsyncJsonStore store;
    private final int maxInFlight;
    private final long timeoutNanos;
    private final long roadTimeoutNanos;
    private int roundRobin;
    private long lastSave;
    private boolean stopped;
    private String admission = "idle";
    private double mspt;

    private static final class Job {
        final String name;
        final ResourceKey<Level> dimension;
        final SquareChunkPlan plan;
        final CompletionFrontier frontier;
        boolean paused = true;
        String error = "";
        Job(String name, ResourceKey<Level> dimension, SquareChunkPlan plan, int window) {
            this.name = name; this.dimension = dimension; this.plan = plan;
            frontier = new CompletionFrontier(0, plan.totalChunks(), window);
        }
    }
    private record Teleport(UUID player, ResourceKey<Level> dimension, int x, int z) {}
    private static final class Request {
        final ServerLevel level;
        final ChunkPos pos;
        final CompletableFuture<Void> planning;
        CompletableFuture<ChunkResult<ChunkAccess>> future;
        final long started;
        long phaseStarted;
        final BiConsumer<ChunkAccess,String> complete;
        boolean ticketHeld;
        boolean timedOut;
        Request(ServerLevel level, ChunkPos pos, CompletableFuture<Void> planning,
                long started, BiConsumer<ChunkAccess,String> complete) {
            this.level=level; this.pos=pos; this.planning=planning; this.started=started; this.phaseStarted=started; this.complete=complete;
        }
    }

    public GenerationService(MinecraftServer server) throws IOException {
        this.server = server;
        maxInFlight = WorldAdminConfig.MAX_IN_FLIGHT.get();
        timeoutNanos = WorldAdminConfig.TIMEOUT_SECONDS.get() * 1_000_000_000L;
        roadTimeoutNanos = WorldAdminConfig.ROAD_TIMEOUT_SECONDS.get() * 1_000_000_000L;
        gate = new LoadGate(WorldAdminConfig.loadGate());
        Path path = server.getWorldPath(LevelResource.ROOT).resolve("worldadmin/jobs.json");
        store = new AsyncJsonStore(path);
        if (Files.exists(path)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (root.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Unsupported job schema");
                for (JsonElement item : root.getAsJsonArray("jobs")) {
                    JsonObject v = item.getAsJsonObject();
                    var plan = new SquareChunkPlan(v.get("x").getAsInt(), v.get("z").getAsInt(), v.get("radius").getAsInt());
                    if (plan.totalChunks() > WorldAdminConfig.MAX_JOB_CHUNKS.get()) throw new IllegalArgumentException("Saved job exceeds configured maximum");
                    String name = v.get("name").getAsString();
                    var job = new Job(name, ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(v.get("dimension").getAsString())), plan, maxInFlight);
                    // A FULL future does not prove region data reached disk. Recheck persisted ranges after restart.
                    job.error = "Restarted: paused; resume rechecks existing chunks from the beginning";
                    if (jobs.putIfAbsent(name, job) != null) throw new IllegalArgumentException("Duplicate saved job");
                }
            } catch (RuntimeException e) { store.close(); throw new IOException("Invalid jobs.json; original file retained", e); }
        }
    }

    public void start(String name, ServerLevel level, int x, int z, int radius) {
        if (jobs.containsKey(name)) throw new IllegalArgumentException("Job already exists; cancel it first: " + name);
        var plan = new SquareChunkPlan(x, z, radius);
        if (plan.totalChunks() > WorldAdminConfig.MAX_JOB_CHUNKS.get()) throw new IllegalArgumentException("Job exceeds maximumJobChunks");
        if (jobs.size() >= 32) throw new IllegalArgumentException("At most 32 jobs may be retained");
        var job = new Job(name, level.dimension(), plan, maxInFlight);
        jobs.put(name, job);
        job.paused = false;
        save();
    }

    public void pause(String name) { job(name).paused = true; save(); }
    public void resume(String name) {
        Job job = job(name);
        if (server.getLevel(job.dimension) == null) throw new IllegalArgumentException("Load the target world first");
        if (requests.stream().anyMatch(r -> timedOut(r,System.nanoTime()))) {
            throw new IllegalArgumentException("A timed-out request is still draining; check generator logs");
        }
        job.paused = false; job.error = ""; save();
    }
    public void cancel(String name) {
        Job job = job(name); job.paused = true;
        if (job.frontier.inFlightCount() != 0) throw new IllegalArgumentException("Paused; wait for in-flight work to drain, then cancel again");
        jobs.remove(name); save();
    }
    private Job job(String name) {
        Job job = jobs.get(name);
        if (job == null) throw new IllegalArgumentException("Unknown job: " + name);
        return job;
    }
    public boolean busy(ResourceKey<Level> dimension) {
        return requests.stream().anyMatch(r -> r.level.dimension().equals(dimension))
                || teleports.stream().anyMatch(t -> t.dimension.equals(dimension))
                || jobs.values().stream().anyMatch(j -> j.dimension.equals(dimension) && !j.paused && !j.frontier.isFinished());
    }
    public void teleport(ServerPlayer player, ServerLevel level, int x, int z) {
        new SquareChunkPlan(Math.floorDiv(x,16), Math.floorDiv(z,16), 0);
        if (teleports.size() >= 16) throw new IllegalArgumentException("Teleport queue is full");
        if (!pendingTeleports.add(player.getUUID())) throw new IllegalArgumentException("You already have a pending teleport");
        teleports.add(new Teleport(player.getUUID(), level.dimension(), x, z));
    }

    public void tick(long elapsedNanos) {
        if (stopped) return;
        long now = System.nanoTime();
        gate.recordTick(elapsedNanos, now);
        Request readyForChunk = null;
        // Finish at most maxInFlight requests per tick; callback never performs chunk generation.
        for (var iterator = requests.iterator(); iterator.hasNext();) {
            Request request = iterator.next();
            if (request.future == null && timedOut(request,now)) request.timedOut = true;
            CompletableFuture<?> activeFuture = request.future == null ? request.planning : request.future;
            if (!activeFuture.isDone()) {
                if (timedOut(request,now)) {
                    request.timedOut = true;
                    jobs.values().forEach(j -> { j.paused = true; j.error = "Request timeout: draining without cancellation"; });
                    admission = "request timeout; no further submissions";
                }
                continue;
            }
            if (request.future == null && !request.planning.isCompletedExceptionally() && !request.timedOut && store.failure()==null) {
                if (readyForChunk == null) readyForChunk = request;
                continue;
            }
            iterator.remove();
            ChunkAccess chunk = null;
            String error;
            try {
                if (request.future == null) {
                    request.planning.getNow(null);
                    error = store.failure()==null ? "Road planning exceeded timeout; work drained, resume to retry"
                            : "Checkpoint I/O failed; planning drained without requesting chunks";
                } else {
                    var result = request.future.getNow(null);
                    chunk = result == null ? null : result.orElse(null);
                    error = result == null ? "Empty chunk result" : result.getError();
                }
            } catch (RuntimeException e) { error = e.toString(); }
            try { request.complete.accept(chunk, error); }
            catch (RuntimeException e) {
                jobs.values().forEach(j -> { j.paused = true; j.error = "Completion callback failed; restart to recheck chunks"; });
                LogUtils.getLogger().error("World Admin completion callback failed", e);
            }
            finally { if (request.ticketHeld) request.level.getChunkSource().removeRegionTicket(TICKET, request.pos, 0, request.pos); }
        }
        boolean work = !teleports.isEmpty() || jobs.values().stream().anyMatch(j -> !j.paused && !j.frontier.isFinished());
        if (!work && requests.isEmpty()) { releaseDh(); admission = "idle"; }
        else if (store.failure() != null) {
            admission = "checkpoint I/O failed: " + store.failure().getMessage();
            if (requests.isEmpty()) releaseDh();
        }
        else if (requests.stream().anyMatch(r -> timedOut(r,now))) { admission = "timed-out request still draining"; }
        else if (!dev.worldadmin.compat.DistantHorizonsRoadGate.pauseForPregeneration(server)) {
            admission = "waiting for existing DH generation to drain";
        }
        else if (!dh.acquire()) {
            admission = "waiting for DH: " + dh.status();
            dev.worldadmin.compat.DistantHorizonsRoadGate.resumeAfterPregeneration(server);
        }
        else {
            Runtime runtime = Runtime.getRuntime();
            double heap = (double)(runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();
            var decision = gate.evaluate(now, heap, requests.size() - (readyForChunk == null ? 0 : 1));
            admission = decision.reason().name(); mspt = decision.tickEwmaNanos()/1_000_000.0;
            if (decision.allowed()) {
                if (readyForChunk != null) submitChunk(readyForChunk, now);
                else if (!teleports.isEmpty()) submitTeleport(teleports.removeFirst(), now);
                else submitJob(now);
            }
        }
        if (now - lastSave > 5_000_000_000L) { save(); lastSave = now; }
    }

    private void submitJob(long now) {
        var candidates = new ArrayList<>(jobs.values());
        for (int i=0; i<candidates.size(); i++) {
            Job job = candidates.get(Math.floorMod(roundRobin++, candidates.size()));
            if (job.paused || job.frontier.isFinished()) continue;
            ServerLevel level = server.getLevel(job.dimension);
            if (level == null) { job.paused=true; job.error="World unloaded"; continue; }
            var failed = job.frontier.firstFailedIndex();
            if (failed.isEmpty() && !job.frontier.hasCapacity()) continue;
            long index = failed.isPresent() ? failed.getAsLong() : job.frontier.nextIndex();
            if (failed.isPresent()) job.frontier.retry(index); else job.frontier.start(index);
            var pos = job.plan.chunkAt(index);
            submit(level, new ChunkPos(pos.x(), pos.z()), now, (chunk, error) -> {
                if (chunk == null) { job.frontier.fail(index); job.paused=true; job.error=String.valueOf(error); }
                else job.frontier.succeed(index);
            });
            return; // Strictly one admission per tick, shared by all worlds and teleports.
        }
    }

    private void submitTeleport(Teleport teleport, long now) {
        ServerLevel level = server.getLevel(teleport.dimension);
        ServerPlayer player = server.getPlayerList().getPlayer(teleport.player);
        if (level == null || player == null) { pendingTeleports.remove(teleport.player); return; }
        submit(level, new ChunkPos(Math.floorDiv(teleport.x,16), Math.floorDiv(teleport.z,16)), now, (chunk,error) -> {
            pendingTeleports.remove(teleport.player);
            ServerPlayer current = server.getPlayerList().getPlayer(teleport.player);
            if (current == null) return;
            if (chunk == null) { current.sendSystemMessage(Component.literal("World Admin teleport failed: " + error)); return; }
            BlockPos safe = findSafePosition(level, chunk, teleport.x, teleport.z);
            if (safe == null) { current.sendSystemMessage(Component.literal("No safe landing position in this chunk; choose other x/z coordinates.")); return; }
            current.teleportTo(level, safe.getX()+.5, safe.getY(), safe.getZ()+.5, current.getYRot(), current.getXRot());
            current.sendSystemMessage(Component.literal("Teleported to " + level.dimension().location()));
        });
    }

    private static BlockPos findSafePosition(ServerLevel level, ChunkAccess chunk, int x, int z) {
        // Search just the requested column, already FULL. Never modify terrain or synchronously load neighbors.
        int top = Math.min(level.getMaxBuildHeight()-2, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x&15,z&15)+1);
        for (int y=top; y>level.getMinBuildHeight(); y--) {
            var feet = new BlockPos(x,y,z);
            if (!level.getWorldBorder().isWithinBounds(feet)) return null;
            var floor = chunk.getBlockState(feet.below());
            var body = chunk.getBlockState(feet);
            var head = chunk.getBlockState(feet.above());
            if (floor.isFaceSturdy(level,feet.below(),net.minecraft.core.Direction.UP)
                    && floor.getFluidState().isEmpty() && body.isAir() && head.isAir()
                    && !floor.is(net.minecraft.tags.BlockTags.FIRE) && !floor.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                    && !floor.is(net.minecraft.world.level.block.Blocks.CACTUS) && !floor.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                    && !floor.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)) return feet;
        }
        return null;
    }

    private void submit(ServerLevel level, ChunkPos pos, long now, BiConsumer<ChunkAccess,String> complete) {
        gate.recordSubmission(now);
        requests.add(new Request(level,pos,RoadWeaverBridge.prepare(level,pos),now,complete));
    }

    private void submitChunk(Request request, long now) {
        gate.recordSubmission(now);
        request.phaseStarted = now;
        try {
            request.level.getChunkSource().addRegionTicket(TICKET,request.pos,0,request.pos);
            request.ticketHeld = true;
            // Public getChunkFuture blocks via managedBlock on the server thread in 1.21.1!
            request.future = request.level.getChunkSource().getChunkFutureMainThread(request.pos.x,request.pos.z,ChunkStatus.FULL,true);
        } catch (RuntimeException e) {
            request.future = CompletableFuture.failedFuture(e);
        }
    }

    private boolean timedOut(Request request,long now) {
        return now-request.phaseStarted > (request.future==null ? roadTimeoutNanos : timeoutNanos);
    }

    public List<String> status() {
        var lines = new ArrayList<String>();
        lines.add(String.format(Locale.ROOT,"Admission=%s, EWMA=%.1f ms, inFlight=%d/%d, teleports=%d, DH=%s", admission,mspt,requests.size(),maxInFlight,teleports.size(),dh.status()));
        long planning = requests.stream().filter(r -> r.future == null).count();
        lines.add("Road plans waiting="+planning+", chunk requests="+(requests.size()-planning));
        lines.add(dev.worldadmin.compat.DistantHorizonsRoadGate.status());
        jobs.values().forEach(j -> lines.add(j.name+": "+j.frontier.cursor()+"/"+j.plan.totalChunks()+" "+(j.frontier.isFinished()?"complete":j.paused?"paused":"running")+" "+j.error));
        return lines;
    }

    private void save() {
        var root = new JsonObject(); root.addProperty("schema",1);
        var array = new JsonArray(); root.add("jobs",array);
        for (Job job : jobs.values()) {
            var v = new JsonObject(); array.add(v);
            v.addProperty("name",job.name); v.addProperty("dimension",job.dimension.location().toString());
            v.addProperty("x",job.plan.centerChunkX()); v.addProperty("z",job.plan.centerChunkZ()); v.addProperty("radius",job.plan.radius());
            v.addProperty("observedCursor",job.frontier.cursor());
        }
        store.save(GSON.toJson(root));
    }

    @Override public void close() {
        stopped=true;
        // Let Minecraft's normal shutdown drain/save chunk work; never cancel shared generation futures.
        teleports.clear(); pendingTeleports.clear(); save(); store.close();
        if (store.failure()!=null) LogUtils.getLogger().error("World Admin checkpoint failed",store.failure());
    }

    /** Called after vanilla has finished closing all levels. */
    public void serverStopped() { dh.release(); }

    private void releaseDh() {
        dh.release();
        dev.worldadmin.compat.DistantHorizonsRoadGate.resumeAfterPregeneration(server);
    }
}
