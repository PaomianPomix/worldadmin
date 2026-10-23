# 兼容性与验证边界

目标：Minecraft 1.21.1 / NeoForge 独立服务端；4 核 Ryzen 9950X、16 GB 内存。核查日期：2026-10-23。

已实现 World Admin 服务端模组与 RoadWeaver 配套分支。原版 RoadWeaver 2.3.1 仍不能直接在新增维度生成道路；必须用配套分支替换原 JAR。工程构建、单元测试及三模组组合的真实独立服务端验证已通过，包含世界隔离、实际道路方块及 DH LOD 道路材料；这不等于完整整合包、真实客户端画面或长期压力测试已经通过。详细证据见 [验证记录](VALIDATION.md)。

## 版本与安装

| 组件 | 模组 ID | 锁定版本 | 处理方式 |
| --- | --- | --- | --- |
| Epic Terrain Compatible | `mr_epic_terrain_compatible` | `1.0.3` | 使用原模组，继承实际生成器与注册表 |
| RoadWeaver 配套分支 | `roadweaver` | `2.3.1-worldadmin.1-1.21.1` | 替换原 RoadWeaver，不可同时安装两份 |
| Distant Horizons | `distanthorizons` | `3.3.3-1.21.1` | 使用原模组，启用此准确版本的可选协调接口与生成入口适配 |

配套源码位于 `compat/roadweaver`，保留上游 MIT LICENSE、NOTICE。基线是上游 `1.21.1-Architectury` 分支的 `331d4ded998b55f90f9c283b30e2e38223016f3f`；它包含 2.3.1 服务端 LWJGL/OpenCL 打包 hotfix。上游没有 2.3.1 Git 标签，发布 JAR 也没有记录 Git SHA，因此这里明确记录源码来源，不宣称与原发布包逐字节一致。结构状态内存缓存部分参考上游 `a6560085c8c70d92da8758d22838c4a39c39acb2`，文件头已注明。

World Admin 本身仅装服务端。RoadWeaver 的既有客户端地图、网络和内容要求仍按其自身规则处理；不能由“管理模组仅服务端”推断其他模组的客户端要求也消失。

## 地形与世界标识

史诗地形兼容版 1.0.3 覆盖 `minecraft:overworld` 的 dimension type 和 noise settings，没有提供独立维度预设。新世界保留运行时 `LevelStem`、生成器 codec 和注册表 holder，不重新拼装未经数据包修改的原版噪声配置。数据包优先级与注册表仍由整台服务器共享。World Admin 0.2.0 支持独立世界种子：初始化时同步影响噪声、结构、群系缩放与独立随机序列；省略种子时固定创建时的服务器种子。

DH 3.3.3 的 `GlobalWorldGenParams_neoforge` 原本直接读取服务器全局种子，0.2.0 针对托管世界修正其 `worldOptions` 和 `worldSeed`，且此修正不依赖 RoadWeaver 是否安装。RoadWeaver 配套分支原本使用 `level.getSeed()` 与该世界 `RandomState`，种子已包含在缓存指纹中，无需修改分支。直接读取服务器全局种子的其他生成模组仍需要逐一核对，不能由标准接口适配推断全部第三方实现都支持独立种子。

配套 RoadWeaver 支持原主世界，以及 `worldadmin:<单段名称>`、使用已注册主世界 dimension type 的真实服务端维度。其他维度不自动开放。内存状态按 `ServerLevel` 实例隔离；存储保留 `minecraft_overworld` 路径，其他维度使用无歧义编码，避免 `a/b` 与 `a_b` 碰撞。

兼容版官方支持 TerraBlender，但警告其模组封装与 Terralith 不兼容；需要组合使用时，应按上游说明改用数据包并调整优先级。原版 Epic Terrain 是另一项目，不能混用两者的兼容结论。

## RoadWeaver 配套分支实现

原版 2.3.1 的问题不只是几处主世界判断：规划、生成队列、坐标去重和部分结构/路牌缓存全局共用，直接放开判断会串世界。分支完成了以下改造：

- 规划、生成、空闲窗口、待放置结构、路牌、道路缓存及相关采样状态按加载中的世界实例隔离；卸载前检查在途计算、回调及待写数据。
- 所有道路计算共享有界工作池，4 核默认 1 个工作线程，硬上限 2 个；粗地形采样不再额外扩张瓦片线程池。新配置运行占比默认 100%，避免在统一线程上限之外重复限速；已有配置中明确设置的 50% 等值会保留。过载拒绝新任务，不把计算转移到游戏主线程。
- 创建世界不执行同步道路出生区域预热。结构 JSON 先异步加载，内存为权威状态，由独立单线程合并写盘；主线程未就绪访问不能偷偷同步读取文件。
- `prepareRegionAsync` 按 64×64 区块核心网格、外扩 128 区块规划范围准备道路；核心网格以原点为中心，避免默认出生点恰在四个网格交界而重复启动四批规划。每世界串行，最多 64 个待准备网格，缓存最多 4096 个已准备网格。原版区块请求在道路 future 完成后才发出。
- future 完成表示道路数据已可供 FEATURES 消费，不等于原版区块或 DH 数据已持久化。规划发布、道路计算和主线程状态回调都必须完成，不能仅派发任务便报告成功。
- 未完成道路的区域筛选在工作线程进行；已规划路径使用实际路径范围，没有路径时使用受限搜索范围与放置余量。主线程每次最多接收 512 个候选，道路状态通过索引更新，远方失败道路不再阻塞整个世界。
- 当前加载会话中同一道路累计失败 3 次后停止自动重试。失败不会当作生成成功；管理员处理原因后可执行 `/wa roads retry <世界> <半径> [区块X 区块Z]`，再恢复相应预生成任务。半径与可选中心均以区块为单位。
- 关闭道路生成会结束未运行的排队 future；已运行任务保留在途记录至真实完成。停服时最多等待工作线程退出 30 秒，过期线程还受到写入隔离保护，不能在缓存关闭后重新创建世界状态。

有限规划范围并不保证以后修改路网、手动连线或更换生成配置能补写已经生成的区块。应先确定道路配置，再进行区域预生成。既有原版 RoadWeaver 存档的 JSON 是权威来源；只有旧 `data/roadweaver_world_data.dat`、没有 JSON 的存档会明确拒绝自动加载并保留原文件，需要先离线迁移，不能静默当成空世界。

启动阶段的阻塞道路预热已经取消。因此，全新原主世界的出生区块可能在道路规划开始之前由 Minecraft 生成，不承诺与原 RoadWeaver 的出生道路一致，也不补生成这些已有区块。已有主世界的道路数据继续保留。World Admin 显式预生成和安全传送发出的新区块请求会先等道路准备；玩家自由探索触发的普通 Minecraft 区块请求不等同于该显式预生成屏障，应先预生成开放区域，再允许玩家进入探索。

CPU 粗地形采样与寻路精度分开设置：默认最小采样间距 32 格，通过 JVM 参数 `-Droadweaver.coarseSampleStep=8`、`16` 或 `32` 选择，修改后重启。A* 默认步长仍为 8 格，最终道路仍使用实际生成器的精确高度解析；粗采样高度采用双线性插值，水体标记在相邻有效样本之间保守合并。32 格配置降低前期采样量，但会改变路线选择，对窄河、小地形细节也存在精度权衡，不能声称路线与原版 8 格采样相同。新的网格划分和采样策略只影响后续规划，不会自动改造既有区块。粗瓦片按采样步长分开保存，待生成路径指纹也包含采样策略。

## Distant Horizons 3.3.3

标准 `LevelEvent.Load/Unload`、`ChunkEvent.Load` 和玩家换维度事件保留，DH 可以识别新增世界。稳定且唯一的维度标识十分重要；重建世界复用旧 key 时，客户端旧 LOD 的处理仍须单独验收。

协调分为两部分：

1. World Admin 预生成期间，通过 DH API 临时禁用新的远方生成，同时暂停自己的 DH 道路入口。已有禁用状态只借用；已有其他主动 override 不覆盖；只释放仍属于自己的临时 override，不修改 DH 配置文件。
2. DH 自主调用 `generateLod` 时，准确版本适配对 detail 0 的 `FEATURES` / `INTERNAL_SERVER` 请求先准备道路，再在 DH 提供的工作线程执行原生成器。没有主线程 `join/get` 等待；池化数据的生命周期延续至原生成 future 真正完成，调用方取消不能使数据提前回收。

DH 道路入口有独立的有界队列，并使用相同类型的 tick/内存准入控制；默认道路准备超时 600 秒。超时停止后续准入，已经进入第三方代码的计算不能被安全强制抢占。DH 的粗略 surface / 更低精度 LOD 本来就不执行 FEATURES，因而不承诺在这些模式显示完整道路、桥梁和装饰。

DH 的 LOD worldgen 不写入原版 region 区块文件，不能代替实体区块预生成。原版 FULL 完成也不能证明 LOD 已保存并在客户端正确显示。Distant Horizons 未安装时，管理模组仍可工作；不受支持的 DH 版本或适配失败会明确阻止相关准入，不伪装成兼容成功。

## 验证状态

已完成的工程验证：

- 正式 Epic Terrain Compatible 1.0.3、RoadWeaver 2.3.1、DH 3.3.3 文件的元数据、关键字节码与 API 签名核查。
- World Admin 当前 20 项单元测试通过；基础 GameTest 的多世界生命周期与区块调度验证通过。DH 专用测试需按其真实 DedicatedServer 环境执行，不能使用 DH 不支持的 GameTestServer 冒充。
- RoadWeaver 分支 Java 编译、NeoForge 重映射、JAR 打包与 LWJGL 打包检查通过；最终测试报告为 117 项，7 项跳过，0 失败，即 110 项实际执行。新增覆盖包括世界状态隔离、异步持久化、关闭后写入保护、取消后的任务保活、线程预算、空间范围、采样插值和配置指纹。
- 独立 DH 桥接状态机断言与 API 测试替身只验证控制逻辑，不等同于 Minecraft 内运行 DH。

Epic Terrain Compatible + DH + 配套 RoadWeaver 的新存档组合独服验证已经通过 19 个检查，耗时约 505.8 秒。包含 10 条实际道路记录、第二世界零道路/连接、原生区块路面方块、DH 真实 FEATURES 调用和 145 列编码后的道路材料，以及卸载重载和正常停服。定向压力修复、32 格采样及居中格网都已进入该测试产物。证据见 [最终记录](validation/combined-smoke.json)。

原 8 格 CPU 采样基线在约 611 秒时按准备超时规则退出：尚未发出原版区块生成请求，主线程仍保持响应，粗瓦片仅完成约 84/441。32 格采样和原点居中网格随后完成了上述新存档组合验收。这个对照包含策略与网格变化，不能直接解释为同一算法的速度倍数，更不能当作 9950X 的吞吐基准。

上线前还需在实际整合包与客户端上验证：重启/重新连接、种子和坐标的地形比对、道路/桥梁/隧道/路牌、维度间 LOD 隔离、预生成暂停/取消/恢复、票据与回调排空。随后至少进行 30–60 分钟多人探索叠加预生成测试，记录 tick P95/P99/最大值、堆内存、GC、吞吐和磁盘延迟。

这些限制控制的是工作准入、并发与队列，无法抢占第三方模组的一次无界同步操作，因此不承诺绝不触发 watchdog，也不关闭 watchdog 来掩盖阻塞。

## 核查来源

- [Epic Terrain Compatible 官方说明](https://modrinth.com/datapack/epic-terrain_compatible)；[1.0.3 JAR](https://cdn.modrinth.com/data/sc69VpnK/versions/6WRQyW1o/epic-terrain_compatible-1.0.3.jar)，SHA-1 `3c32f62b97233d06d5f1a1e242624cc459af4f8c`。
- [RoadWeaver 官方项目](https://modrinth.com/mod/roadweaver)；[配套分支基线源码](https://github.com/shiroha-233/RoadWeaver/tree/331d4ded998b55f90f9c283b30e2e38223016f3f)；[原版 2.3.1 JAR](https://cdn.modrinth.com/data/6jk8Pote/versions/G0vR1X03/roadweaver-neoforge-2.3.1-1.21.1.jar)，SHA-1 `aa104b98f021b059833bf413d172eb045e2d9738`。
- [DH 官方仓库](https://gitlab.com/distant-horizons-team/distant-horizons)；[DH 配置 API](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/main/api/src/main/java/com/seibel/distanthorizons/api/interfaces/config/IDhApiConfigValue.java)；[DH worldgen API](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/main/api/src/main/java/com/seibel/distanthorizons/api/interfaces/config/both/IDhApiWorldGenerationConfig.java)；[3.3.3 JAR](https://cdn.modrinth.com/data/uCdwusMi/versions/9w34y8ai/DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar)，SHA-256 `864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab`。
