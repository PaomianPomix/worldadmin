package dev.worldadmin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import static net.minecraft.commands.Commands.*;

public final class WorldAdminCommands {
    @FunctionalInterface private interface Action { void run() throws Exception; }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, WorldAdmin mod) {
        var root = literal("wa").requires(s -> s.hasPermission(2));
        root.then(literal("compatibility").executes(c -> execute(c, () -> {
            for (String id : new String[]{"mr_epic_terrain_compatible", "roadweaver", "distanthorizons"}) {
                var info = net.neoforged.fml.ModList.get().getModContainerById(id);
                say(c, id + ": " + info.map(m -> m.getModInfo().getVersion().toString()).orElse("not installed"));
            }
            say(c,"Epic: preserves registered template generation settings; full modpack testing required.");
            say(c,dev.worldadmin.compat.RoadWeaverBridge.description());
            say(c,"DH 3.3.3: optional temporary generation suspension; LOD generation does not create vanilla region chunks.");
        })));
        root.then(literal("list").executes(c -> execute(c, () -> {
            for (ServerLevel level : c.getSource().getServer().getAllLevels()) say(c,level.dimension().location()+" [loaded, seed="+level.getSeed()+"]");
            mod.worlds().list().stream().filter(w -> !w.loaded()).forEach(w -> say(c,w.dimension()+" [unloaded, seed="+w.seed()+"]"));
        })));
        root.then(literal("templates").executes(c -> execute(c, () ->
                c.getSource().getServer().registryAccess().registryOrThrow(Registries.LEVEL_STEM).keySet().forEach(k -> say(c,k.toString())))));
        root.then(literal("create").then(argument("name", StringArgumentType.word())
                .executes(c -> create(c,mod,ResourceLocation.withDefaultNamespace("overworld")))
                .then(seedOptions(mod,false))
                .then(argument("template",ResourceLocationArgument.id()).suggests((c,b) -> SharedSuggestionProvider.suggestResource(
                        c.getSource().getServer().registryAccess().registryOrThrow(Registries.LEVEL_STEM).keySet(), b))
                        .executes(c -> create(c,mod,ResourceLocationArgument.getId(c,"template")))
                        .then(seedOptions(mod,true)))));
        root.then(literal("seed").then(argument("name",StringArgumentType.word()).executes(c -> execute(c,() ->
                say(c,"worldadmin:"+name(c)+" 的固定种子: "+mod.worlds().seed(name(c)))))));
        root.then(literal("load").then(argument("name",StringArgumentType.word()).executes(c -> execute(c, () -> {
            var level=mod.worlds().load(name(c)); say(c,"已加载 "+level.dimension().location());
        }))));
        root.then(literal("unload").then(argument("name",StringArgumentType.word()).executes(c -> execute(c, () -> {
            var level=mod.worlds().getLevel(name(c));
            if (level!=null && mod.generation().busy(level.dimension())) throw new IllegalStateException("请暂停预生成并等待在途区块/传送完成后重试");
            mod.worlds().unload(name(c)); say(c,"已卸载 "+name(c));
        }))));
        root.then(literal("tp").then(argument("world",ResourceLocationArgument.id())
                .executes(c -> teleport(c,mod,0,0))
                .then(argument("x",IntegerArgumentType.integer(-29_999_984,29_999_983))
                        .then(argument("z",IntegerArgumentType.integer(-29_999_984,29_999_983))
                                .executes(c -> teleport(c,mod,IntegerArgumentType.getInteger(c,"x"),IntegerArgumentType.getInteger(c,"z")))))));
        var pregen = literal("pregen");
        pregen.then(literal("start").then(argument("world",ResourceLocationArgument.id())
                .then(argument("radius",IntegerArgumentType.integer(0,1_000_000))
                        .executes(c -> start(c,mod,0,0))
                        .then(argument("chunkX",IntegerArgumentType.integer())
                                .then(argument("chunkZ",IntegerArgumentType.integer())
                                        .executes(c -> start(c,mod,IntegerArgumentType.getInteger(c,"chunkX"),IntegerArgumentType.getInteger(c,"chunkZ"))))))));
        pregen.then(literal("pause").then(argument("job",ResourceLocationArgument.id()).executes(c -> execute(c, () -> {
            mod.generation().pause(ResourceLocationArgument.getId(c,"job").toString()); say(c,"已暂停新请求；在途区块会自然完成");
        }))));
        pregen.then(literal("resume").then(argument("job",ResourceLocationArgument.id()).executes(c -> execute(c, () -> {
            mod.generation().resume(ResourceLocationArgument.getId(c,"job").toString()); say(c,"已恢复任务；高负载时仍会自动等待");
        }))));
        pregen.then(literal("cancel").then(argument("job",ResourceLocationArgument.id()).executes(c -> execute(c, () -> {
            mod.generation().cancel(ResourceLocationArgument.getId(c,"job").toString()); say(c,"已移除任务");
        }))));
        pregen.then(literal("status").executes(c -> execute(c, () -> mod.generation().status().forEach(s -> say(c,s)))));
        root.then(pregen);
        root.then(literal("roads").then(literal("retry").then(argument("world",ResourceLocationArgument.id())
                .then(argument("radius",IntegerArgumentType.integer(0,256))
                        .executes(c -> retryRoads(c,mod,0,0))
                        .then(argument("chunkX",IntegerArgumentType.integer())
                                .then(argument("chunkZ",IntegerArgumentType.integer())
                                        .executes(c -> retryRoads(c,mod,IntegerArgumentType.getInteger(c,"chunkX"),IntegerArgumentType.getInteger(c,"chunkZ")))))))));
        root.then(literal("status").executes(c -> execute(c, () -> mod.generation().status().forEach(s -> say(c,s)))));
        dispatcher.register(root);
    }
    private static int create(CommandContext<CommandSourceStack> c, WorldAdmin mod, ResourceLocation template) {
        return create(c,mod,template,null);
    }
    private static int create(CommandContext<CommandSourceStack> c, WorldAdmin mod, ResourceLocation template,Long seed) {
        return execute(c, () -> {
            var world=seed==null ? mod.worlds().create(name(c),template) : mod.worlds().create(name(c),template,seed);
            say(c,"已创建 "+world.dimension()+"，固定种子 "+world.seed()+"；尚未批量生成区块");
        });
    }
    private static LiteralArgumentBuilder<CommandSourceStack> seedOptions(WorldAdmin mod,boolean hasTemplate) {
        return literal("seed")
                .then(argument("seed",LongArgumentType.longArg()).executes(c -> create(c,mod,
                        hasTemplate ? ResourceLocationArgument.getId(c,"template") : ResourceLocation.withDefaultNamespace("overworld"),
                        LongArgumentType.getLong(c,"seed"))))
                .then(literal("random").executes(c -> create(c,mod,
                        hasTemplate ? ResourceLocationArgument.getId(c,"template") : ResourceLocation.withDefaultNamespace("overworld"),
                        net.minecraft.world.level.levelgen.WorldOptions.randomSeed())));
    }
    private static int start(CommandContext<CommandSourceStack> c, WorldAdmin mod, int x, int z) {
        return execute(c, () -> {
            ServerLevel level=resolve(c,mod);
            String job=level.dimension().location().toString();
            mod.generation().start(job,level,x,z,IntegerArgumentType.getInteger(c,"radius"));
            say(c,"已加入预生成任务 "+job+"；半径和中心坐标单位均为区块");
        });
    }
    private static int teleport(CommandContext<CommandSourceStack> c, WorldAdmin mod, int x, int z) {
        return execute(c, () -> {
            mod.generation().teleport(c.getSource().getPlayerOrException(),resolve(c,mod),x,z);
            say(c,"传送已排队，目标区块准备好并找到安全落脚点后执行");
        });
    }
    private static int retryRoads(CommandContext<CommandSourceStack> c,WorldAdmin mod,int x,int z) {
        return execute(c,() -> {
            int radius=IntegerArgumentType.getInteger(c,"radius");
            new dev.worldadmin.pregen.core.SquareChunkPlan(x,z,radius);
            var level=resolve(c,mod);
            var future=dev.worldadmin.compat.RoadWeaverBridge.retryRegion(level,(x-radius)*16,(z-radius)*16,
                    (x+radius)*16+15,(z+radius)*16+15);
            say(c,"正在重置该范围内失败道路的重试次数；完成后可恢复预生成");
            future.whenComplete((count,error) -> c.getSource().getServer().execute(() -> {
                if(error==null) say(c,"已重置 "+count+" 条失败道路，请运行 /wa pregen resume "+level.dimension().location());
                else c.getSource().sendFailure(Component.literal("道路重试失败: "+error));
            }));
        });
    }
    private static ServerLevel resolve(CommandContext<CommandSourceStack> c, WorldAdmin mod) {
        ResourceLocation world=ResourceLocationArgument.getId(c,"world");
        boolean explicitNamespace=c.getNodes().stream()
                .filter(node -> node.getNode().getName().equals("world"))
                .anyMatch(node -> node.getRange().get(c.getInput()).contains(":"));
        ServerLevel level;
        if (!explicitNamespace && world.getNamespace().equals("minecraft") && mod.worlds().contains(world.getPath())) level=mod.worlds().getLevel(world.getPath());
        else level=c.getSource().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,world));
        if (level==null) throw new IllegalArgumentException("世界不存在或未加载: "+world);
        return level;
    }
    private static String name(CommandContext<CommandSourceStack> c) { return StringArgumentType.getString(c,"name"); }
    private static void say(CommandContext<CommandSourceStack> c,String message) { c.getSource().sendSuccess(() -> Component.literal(message),false); }
    private static int execute(CommandContext<CommandSourceStack> c,Action action) {
        try { action.run(); return 1; }
        catch (Exception e) { c.getSource().sendFailure(Component.literal("World Admin: "+e.getMessage())); return 0; }
    }
    private WorldAdminCommands() {}
}
