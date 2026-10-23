package dev.worldadmin.gametest;

import com.google.gson.GsonBuilder;
import dev.worldadmin.WorldAdmin;
import dev.worldadmin.compat.DistantHorizonsBridge;
import dev.worldadmin.compat.DistantHorizonsRoadGate;
import dev.worldadmin.compat.RoadWeaverBridge;
import dev.worldadmin.world.WorldManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Opt-in local dedicated-server smoke checks; inactive unless explicitly enabled by JVM property. */
@EventBusSubscriber(modid=WorldAdmin.MOD_ID,value=Dist.DEDICATED_SERVER)
public final class SmokeServerChecks {
    private static final boolean ENABLED=Boolean.getBoolean("worldadmin.smokeTest");
    private static final Capture OUTPUT=new Capture();
    private static final List<String> CHECKS=new ArrayList<>();
    private static String world;
    private static String job;
    private static int stage;
    private static long started;
    private static boolean ended;
    private static String secondWorld;
    private static CompletableFuture<RoadWeaverSmokeFixture.Result> roadCheck;
    private static CompletableFuture<String> dhCheck;

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!ENABLED) return;
        started=System.nanoTime();
        world="smoke_"+UUID.randomUUID().toString().replace("-","");
        job="worldadmin:"+world;
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (!ENABLED || started==0 || ended) return;
        MinecraftServer server=event.getServer();
        try {
            RoadWeaverSmokeFixture.tick(server);
            DistantHorizonsRoadFixture.tick(server);
            if (System.nanoTime()-started > 1_200_000_000_000L) throw new IllegalStateException("Smoke test timed out; stage="+stage+", status="+status(server));
            switch(stage) {
                case 0 -> {
                    if (System.nanoTime()-started < 5_000_000_000L) return;
                    check(command(server,"wa create "+world)==1,"Created noise world from live overworld template");
                    var level=server.getLevel(WorldManager.dimensionKey(world));
                    check(level!=null && level.getChunkSource().getLoadedChunksCount()==0,"Create did not generate spawn chunks");
                    check(level.getMinBuildHeight()==server.overworld().getMinBuildHeight()
                            && level.getHeight()==server.overworld().getHeight(),"Dimension heights match live Epic/vanilla template");
                    var bridge=new DistantHorizonsBridge();
                    check(bridge.acquire(),"DH temporary override acquired: "+bridge.status());
                    bridge.release();
                    check(!ModList.get().isLoaded("distanthorizons") || bridge.status().contains("restored")
                            || bridge.status().contains("already disabled"),"DH override released: "+bridge.status());
                    check(command(server,"wa pregen start "+world+" 0")==1,"Queued one FULL chunk through admission control");
                    check(command(server,"wa pregen pause "+job)==1,"Paused before admission");
                    check(status(server).stream().anyMatch(s -> s.contains(job+": 0/1 paused")),"Pause preserves unfinished work");
                    check(command(server,"wa pregen resume "+job)==1,"Resumed task");
                    stage=1;
                }
                case 1 -> {
                    var lines=status(server);
                    if (lines.stream().anyMatch(s -> s.contains(job) && s.contains("paused"))) throw new IllegalStateException("Generation paused with error: "+lines);
                    if (lines.stream().noneMatch(s -> s.contains(job+": 1/1 complete"))) return;
                    check(true,"Bounded scheduler completed a real FULL chunk");
                    stage=2;
                }
                case 2 -> {
                    var level=server.getLevel(WorldManager.dimensionKey(world));
                    if(level.getChunkSource().chunkMap.hasWork()) return;
                    check(command(server,"wa pregen cancel "+job)==1,"Drained job removable");
                    if(ModList.get().isLoaded("roadweaver")) {
                        secondWorld=world+"_b";
                        check(command(server,"wa create "+secondWorld)==1,"Created second world for isolation check");
                        roadCheck=RoadWeaverSmokeFixture.start(level,server.getLevel(WorldManager.dimensionKey(secondWorld)));
                        stage=3;
                    } else { saveReload(server); stage=6; }
                }
                case 3 -> {
                    if(!roadCheck.isDone()) return;
                    var result=roadCheck.getNow(null);
                    check(result!=null,result==null?"Missing road fixture result":result.message());
                    if(ModList.get().isLoaded("distanthorizons")) {
                        dhCheck=DistantHorizonsRoadFixture.start(server.getLevel(WorldManager.dimensionKey(world)),
                                Math.floorDiv(result.chunkX(),4)*4,Math.floorDiv(result.chunkZ(),4)*4,result.expectedMaterialIds());
                        stage=4;
                    } else stage=5;
                }
                case 4 -> {
                    if(!dhCheck.isDone()) return;
                    check(true,dhCheck.getNow("DH returned no report"));
                    stage=5;
                }
                case 5 -> {
                    if(busy(server,world) || busy(server,secondWorld)) return;
                    check(command(server,"wa unload "+secondWorld)==1,"Isolated second world unloaded");
                    saveReload(server); stage=6;
                }
                case 6 -> {
                    if(busy(server,world)) return;
                    check(command(server,"wa unload "+world)==1,"Reloaded empty world cleanly unloaded");
                    finish(server,null);
                }
            }
        } catch(Exception error) { finish(server,error); }
    }
    private static boolean busy(MinecraftServer server,String name) {
        var level=server.getLevel(WorldManager.dimensionKey(name));
        return level.getChunkSource().chunkMap.hasWork() || RoadWeaverBridge.busy(level)
                || DistantHorizonsRoadGate.hasPendingWork(level);
    }
    private static void saveReload(MinecraftServer server) throws Exception {
        check(command(server,"wa unload "+world)==1,"Drained world saved and unloaded");
        check(server.getLevel(WorldManager.dimensionKey(world))==null,"World removed from server map");
        check(command(server,"wa load "+world)==1,"Persisted world reloaded");
    }
    private static List<String> status(MinecraftServer server) throws Exception {
        command(server,"wa status"); return List.copyOf(OUTPUT.lines);
    }
    private static int command(MinecraftServer server,String text) throws Exception {
        OUTPUT.lines.clear();
        return server.getCommands().getDispatcher().execute(text,server.createCommandSourceStack().withSource(OUTPUT));
    }
    private static void check(boolean passed,String label) {
        if(!passed) throw new IllegalStateException(label+"; command output="+OUTPUT.lines);
        CHECKS.add(label);
    }
    private static void finish(MinecraftServer server,Exception failure) {
        ended=true;
        var report=new LinkedHashMap<String,Object>();
        report.put("passed",failure==null); report.put("checks",CHECKS);
        report.put("elapsedSeconds",(System.nanoTime()-started)/1_000_000_000.0);
        report.put("failure",failure==null?null:failure.toString());
        report.put("mods",ModList.get().getMods().stream().map(m -> m.getModId()+"@"+m.getVersion()).toList());
        try { Files.writeString(Path.of("smoke-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
        catch(Exception writeFailure) { com.mojang.logging.LogUtils.getLogger().error("Unable to write smoke report",writeFailure); }
        if(failure!=null) com.mojang.logging.LogUtils.getLogger().error("World Admin smoke test failed",failure);
        else com.mojang.logging.LogUtils.getLogger().info("World Admin dedicated smoke test passed: {}",CHECKS);
        server.halt(false);
    }
    private static final class Capture implements CommandSource {
        final List<String> lines=new ArrayList<>();
        public void sendSystemMessage(Component message) { lines.add(message.getString()); }
        public boolean acceptsSuccess(){return true;}
        public boolean acceptsFailure(){return true;}
        public boolean shouldInformAdmins(){return false;}
    }
}
