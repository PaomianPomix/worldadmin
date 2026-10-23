package dev.worldadmin.gametest;

import dev.worldadmin.WorldAdmin;
import dev.worldadmin.compat.DistantHorizonsBridge;
import dev.worldadmin.compat.DistantHorizonsRoadGate;
import dev.worldadmin.world.WorldManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(WorldAdmin.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GenerationGameTests {
    @GameTest(template="empty",batch="worldadmin_namespace",timeoutTicks=100)
    public static void explicitNamespaceCannotBeShadowedByManagedName(GameTestHelper helper) throws Exception {
        var server=helper.getLevel().getServer();
        var capture=new Capture();
        String name="overworld";
        if(server.getLevel(WorldManager.dimensionKey(name))==null) {
            if(command(server,capture,"wa create "+name)!=1) {
                helper.assertTrue(command(server,capture,"wa load "+name)==1,"Unable to load managed overworld-name fixture");
            }
        }
        helper.assertTrue(command(server,capture,"wa pregen start minecraft:overworld 0")==1,"Explicit native world argument failed");
        command(server,capture,"wa status");
        helper.assertTrue(capture.lines.stream().anyMatch(s -> s.startsWith("minecraft:overworld: 0/1 running")),
                "Explicit minecraft namespace resolved to managed world: "+capture.lines);
        helper.assertTrue(command(server,capture,"wa pregen cancel minecraft:overworld")==1,"Native fixture job did not clean up");
        helper.assertTrue(command(server,capture,"wa pregen start overworld 0")==1,"Managed shorthand failed");
        command(server,capture,"wa status");
        helper.assertTrue(capture.lines.stream().anyMatch(s -> s.startsWith("worldadmin:overworld: 0/1 running")),
                "Unqualified name did not resolve managed world");
        helper.assertTrue(command(server,capture,"wa pregen cancel worldadmin:overworld")==1,"Managed fixture job did not clean up");
        helper.assertTrue(command(server,capture,"wa unload overworld")==1,"Managed namespace fixture did not unload");
        helper.succeed();
    }

    @GameTest(template="empty",batch="worldadmin_gate_lifecycle",timeoutTicks=200000)
    public static void rejectedDhContinuationCannotUseUnloadedLevel(GameTestHelper helper) {
        if(ModList.get().isLoaded("roadweaver")) { helper.succeed(); return; }
        var level=helper.getLevel();
        var server=level.getServer();
        var executor=new DeferredExecutor();
        var writes=new AtomicInteger();
        DistantHorizonsRoadGate.resumeAfterPregeneration(server);
        var future=DistantHorizonsRoadGate.wrap(new FakeGenerator(level),0,0,(byte)0,"FEATURES",executor,
                    () -> { writes.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        helper.assertFalse(DistantHorizonsRoadGate.beginUnload(level),"Pending DH request must prevent unloading");
        boolean[] done={false};
        helper.onEachTick(() -> {
            if(done[0] || executor.tasks.isEmpty()) return;
            done[0]=true;
            try {
            helper.assertTrue(DistantHorizonsRoadGate.pauseForPregeneration(server),"Nondelegated work must drain before disabling DH");
            helper.assertTrue(future.isCompletedExceptionally(),"Deferred DH owner was stranded by suspension");
            DistantHorizonsRoadGate.resumeAfterPregeneration(server);
            executor.runQueued();
            helper.assertTrue(writes.get()==0,"Late executor continuation touched a released DH source");
            helper.assertTrue(DistantHorizonsRoadGate.beginUnload(level),"Rejected work kept an unload lease");
            var stale=DistantHorizonsRoadGate.wrap(new FakeGenerator(level),0,0,(byte)0,"FEATURES",executor,
                    () -> { writes.incrementAndGet(); return CompletableFuture.completedFuture(null); });
            helper.assertTrue(stale.isCompletedExceptionally(),"A sealed level accepted new DH work");
            helper.assertTrue(writes.get()==0,"Sealed level was accessed by late generation");
            helper.succeed();
            } finally {
                DistantHorizonsRoadGate.cancelUnload(level);
                DistantHorizonsRoadGate.resumeAfterPregeneration(server);
            }
        });
    }

    @net.minecraft.gametest.framework.AfterBatch(batch="worldadmin_gate_lifecycle")
    public static void cleanupGateProbe(net.minecraft.server.level.ServerLevel level) {
        DistantHorizonsRoadGate.pauseForPregeneration(level.getServer());
        DistantHorizonsRoadGate.cancelUnload(level);
        DistantHorizonsRoadGate.resumeAfterPregeneration(level.getServer());
    }

    @GameTest(template="empty",batch="worldadmin_scheduler",timeoutTicks=200000)
    public static void pauseResumeAndDrain(GameTestHelper helper) throws Exception {
        // RoadWeaver's real terrain planner is checked by the separate compatibility test.
        if (ModList.get().isLoaded("roadweaver")) { helper.succeed(); return; }
        MinecraftServer server=helper.getLevel().getServer();
        String name="scheduler_"+UUID.randomUUID().toString().replace("-","");
        String job="worldadmin:"+name;
        Capture capture=new Capture();
        helper.assertTrue(command(server,capture,"wa create "+name)==1,"Create failed: "+capture.lines);
        helper.assertTrue(command(server,capture,"wa pregen start "+name+" 0")==1,"Start failed");
        helper.assertTrue(command(server,capture,"wa pregen pause "+job)==1,"Pause failed");
        command(server,capture,"wa status");
        helper.assertTrue(capture.lines.stream().anyMatch(s -> s.contains(job+": 0/1 paused")),"Pause admitted work unexpectedly");
        helper.assertTrue(command(server,capture,"wa pregen resume "+job)==1,"Resume failed");
        boolean[] finished={false};
        long started=System.nanoTime();
        helper.onEachTick(() -> {
            if (finished[0]) return;
            try {
                command(server,capture,"wa status");
                if(System.nanoTime()-started>30_000_000_000L) throw new IllegalStateException("No scheduler progress after 30 seconds: "+capture.lines);
                if (capture.lines.stream().noneMatch(s -> s.contains(job+": 1/1 complete"))) return;
                var level=server.getLevel(WorldManager.dimensionKey(name));
                if (level.getChunkSource().chunkMap.hasWork()) return;
                helper.assertTrue(command(server,capture,"wa pregen cancel "+job)==1,"Completed job not removable");
                helper.assertTrue(command(server,capture,"wa unload "+name)==1,"Scheduler leaked a ticket: "+capture.lines);
                finished[0]=true;
                helper.succeed();
            } catch (Exception e) { finished[0]=true; helper.fail("Scheduler lifecycle: "+e); }
        });
    }

    @GameTest(template="empty",batch="worldadmin_dh",timeoutTicks=20000)
    public static void distantHorizonsLease(GameTestHelper helper) {
        if (!ModList.get().isLoaded("distanthorizons")) { helper.succeed(); return; }
        var bridge=new DistantHorizonsBridge();
        boolean[] finished={false};
        helper.onEachTick(() -> {
            if (finished[0] || !bridge.acquire()) return;
            helper.assertTrue(bridge.acquire(),"DH lease must be idempotent");
            bridge.release();
            helper.assertTrue(bridge.status().contains("restored") || bridge.status().contains("already disabled"),
                    "DH temporary override not released: "+bridge.status());
            finished[0]=true;
            helper.succeed();
        });
    }

    private static int command(MinecraftServer server,Capture capture,String text) throws Exception {
        capture.lines.clear();
        return server.getCommands().getDispatcher().execute(text,server.createCommandSourceStack().withSource(capture));
    }
    private static final class Capture implements CommandSource {
        final List<String> lines=new ArrayList<>();
        public void sendSystemMessage(Component message) { lines.add(message.getString()); }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
    }
    public static final class FakeGenerator {
        public final FakeWrapper serverLevelWrapper;
        FakeGenerator(net.minecraft.server.level.ServerLevel level) { serverLevelWrapper=new FakeWrapper(level); }
    }
    public static final class FakeWrapper {
        private final net.minecraft.server.level.ServerLevel level;
        FakeWrapper(net.minecraft.server.level.ServerLevel level) { this.level=level; }
        public Object getWrappedMcObject() { return level; }
    }
    private static final class DeferredExecutor extends AbstractExecutorService {
        final java.util.Queue<Runnable> tasks=new java.util.ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void runQueued() { Runnable task; while((task=tasks.poll())!=null) task.run(); }
        public void shutdown() {}
        public List<Runnable> shutdownNow() { return List.of(); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long timeout,TimeUnit unit) { return false; }
    }
}
