package dev.worldadmin.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/** Optional, versioned bridge to the companion RoadWeaver branch. Never waits on planning. */
public final class RoadWeaverBridge {
    private static final String API = "net.shiroha233.roadweaver.api.";
    private static Access access;
    private static RuntimeException failure;
    private record Access(Method supports, Method prepare, Method busy, Method release) {}
    private RoadWeaverBridge() {}

    private static Access access() {
        if (access != null) return access;
        if (failure != null) throw failure;
        try {
            Class<?> worlds = Class.forName(API + "RoadWeaverWorlds");
            if (!Integer.valueOf(1).equals(worlds.getMethod("apiVersion").invoke(null))) {
                throw new IllegalStateException("Unsupported companion API version");
            }
            Class<?> network = Class.forName(API + "RoadNetworkApi");
            access = new Access(worlds.getMethod("supports", Level.class),
                    network.getMethod("prepareRegionAsync", ServerLevel.class, int.class,int.class,int.class,int.class),
                    network.getMethod("hasPendingWork",ServerLevel.class),
                    network.getMethod("releaseWorld",ServerLevel.class));
            return access;
        } catch (ReflectiveOperationException | LinkageError e) {
            failure = new IllegalStateException("Install the World Admin companion RoadWeaver JAR in place of the original RoadWeaver JAR",e);
            throw failure;
        }
    }

    public static CompletableFuture<Void> prepare(ServerLevel level, ChunkPos target) {
        int margin = 8 * 16;
        return prepareRegion(level,target.getMinBlockX()-margin,target.getMinBlockZ()-margin,
                target.getMaxBlockX()+margin,target.getMaxBlockZ()+margin);
    }

    public static CompletableFuture<Void> prepareRegion(ServerLevel level,int minBlockX,int minBlockZ,int maxBlockX,int maxBlockZ) {
        if (!ModList.get().isLoaded("roadweaver")) return CompletableFuture.completedFuture(null);
        try {
            Access api = access();
            if (!Boolean.TRUE.equals(api.supports.invoke(null,level))) return CompletableFuture.completedFuture(null);
            // FULL status can generate FEATURES in dependency neighbors. Prepare the entire radius first.
            Object result = api.prepare.invoke(null,level,minBlockX,minBlockZ,maxBlockX,maxBlockZ);
            if (!(result instanceof CompletableFuture<?> future)) throw new IllegalStateException("RoadWeaver returned no readiness future");
            return future.thenApply(ignored -> null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e instanceof InvocationTargetException invocation ? invocation.getCause() : e;
            return CompletableFuture.failedFuture(cause);
        }
    }

    public static boolean busy(ServerLevel level) {
        if (!ModList.get().isLoaded("roadweaver")) return false;
        try { return Boolean.TRUE.equals(access().busy.invoke(null,level)); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot check RoadWeaver pending work",e); }
    }

    public static CompletableFuture<Integer> retryRegion(ServerLevel level,int minX,int minZ,int maxX,int maxZ) {
        if (!ModList.get().isLoaded("roadweaver")) throw new IllegalStateException("RoadWeaver is not installed");
        try {
            access();
            Object value=Class.forName(API+"RoadNetworkApi").getMethod("retryFailedRegion",ServerLevel.class,
                    int.class,int.class,int.class,int.class).invoke(null,level,minX,minZ,maxX,maxZ);
            if(!(value instanceof CompletableFuture<?> future)) throw new IllegalStateException("No retry future returned");
            return future.thenApply(count -> ((Number)count).intValue());
        } catch(ReflectiveOperationException e) { return CompletableFuture.failedFuture(e); }
    }

    public static String description() {
        if(!ModList.get().isLoaded("roadweaver")) return "RoadWeaver: not installed";
        try { access(); return "RoadWeaver: companion API v1; per-world road planning before FULL"; }
        catch(RuntimeException e) { return "RoadWeaver: companion JAR required; original 2.3.1 cannot prepare managed worlds"; }
    }

    public static void releaseWorld(ServerLevel level) {
        if (!ModList.get().isLoaded("roadweaver")) return;
        try { access().release.invoke(null,level); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot release RoadWeaver world state",e); }
    }
}
