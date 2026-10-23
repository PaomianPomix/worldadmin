package dev.worldadmin;

import com.mojang.logging.LogUtils;
import dev.worldadmin.pregen.GenerationService;
import dev.worldadmin.world.WorldManager;
import dev.worldadmin.compat.DistantHorizonsRoadGate;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(value = WorldAdmin.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class WorldAdmin {
    public static final String MOD_ID = "worldadmin";
    private WorldManager worlds;
    private GenerationService generation;
    private String startupError;
    private long tickStart;
    private long previousServiceNanos;
    private long tickIntervalOverrun;

    public WorldAdmin(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, WorldAdminConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(this::started);
        // Release deferred DH work before DH's own synchronous queue shutdown listeners run.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,this::stopping);
        NeoForge.EVENT_BUS.addListener(this::stopped);
        NeoForge.EVENT_BUS.addListener(this::preTick);
        NeoForge.EVENT_BUS.addListener(this::postTick);
        NeoForge.EVENT_BUS.addListener(this::commands);
    }

    private void started(ServerStartedEvent event) {
        try {
            worlds = new WorldManager(event.getServer());
            for (String failure : worlds.restoreLoadedWorlds()) LogUtils.getLogger().error("World Admin restore: {}",failure);
            generation = new GenerationService(event.getServer());
            LogUtils.getLogger().info("World Admin ready; managed world seeds are pinned independently. Optional generation is load-gated.");
        } catch (Exception e) {
            startupError = e.getMessage();
            LogUtils.getLogger().error("World Admin disabled to preserve invalid or unreadable saved state",e);
        }
    }
    private void stopping(ServerStoppingEvent event) {
        DistantHorizonsRoadGate.stop(event.getServer());
        if (generation != null) generation.close();
        worlds = null;
    }
    private void stopped(ServerStoppedEvent event) {
        if (generation != null) generation.serverStopped();
        generation = null;
    }
    private void preTick(ServerTickEvent.Pre event) {
        long now=System.nanoTime();
        // Chunk tasks also run between ticks. Account for delayed starts without counting
        // the normal 50 ms sleep (or the administrator's configured tick-rate interval).
        tickIntervalOverrun=tickStart==0 ? 0 : Math.max(0,now-tickStart-event.getServer().tickRateManager().nanosecondsPerTick());
        tickStart=now;
    }
    private void postTick(ServerTickEvent.Post event) {
        long serviceStart = System.nanoTime();
        // Include our last Post-handler cost; vanilla's tick timer excludes Post listeners.
        long elapsed = Math.max(Math.max(0, serviceStart-tickStart) + previousServiceNanos,tickIntervalOverrun);
        if (generation != null) generation.tick(elapsed);
        // The optional mixin can still receive DH work when admin metadata failed to load.
        DistantHorizonsRoadGate.tick(event.getServer(),elapsed);
        previousServiceNanos = Math.max(0, System.nanoTime()-serviceStart);
    }
    private void commands(RegisterCommandsEvent event) { WorldAdminCommands.register(event.getDispatcher(),this); }
    public WorldManager worlds() { ready(); return worlds; }
    public GenerationService generation() { ready(); return generation; }
    private void ready() {
        if (worlds == null || generation == null) throw new IllegalStateException("World Admin unavailable: " + (startupError == null ? "server still starting" : startupError));
    }
}
