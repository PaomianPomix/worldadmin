# 验证记录

日期：2026-10-23。工作区 `D:\worldadmin`。此记录随后续集成测试更新，不能替代目标整合包的长期压测。

## 0.2.0 独立种子验证

主模组构建成功，83 项 JUnit 全部通过；新增 63 项种子数值/锁文件测试覆盖 64 位精度、并发创建、不可变约束、错误输入与备用文件系统路径。7 个基础 GameTest 全部通过，其中独立种子测试使用真实 Nether 噪声模板验证：

- 大负数、零、最大 long、默认模板快捷命令和 `seed random`。
- 构造时的地形/含水层/群系噪声、结构分布、StructureCheck 和群系缩放种子。
- 同种子一致性、不同种子差异、独立随机序列及其保存后的续接。
- 原生 FULL 区块卸载重载，以及重载后的新区域与同种子对照世界一致。
- 种子锁冲突时拒绝加载、保留文件，恢复正确锁后可正常加载。

专服仍采用 4 个 JVM 可见处理器、6 GB 堆，运行了两组种子专用验证：

| 环境 | 结果 | 证据 |
|---|---|---|
| Epic Terrain Compatible 1.0.3＋DH 3.3.3，无 RoadWeaver | 93 个检查通过，证明 DH 种子修正独立启用 | [JSON](validation/seed-dh-no-road.json)、[日志](validation/seed-dh-no-road.log) |
| Epic＋DH＋原配套 RoadWeaver，复用 0.1.0 旧存档 | 103 个检查通过，含 RoadWeaver 指纹、2 个旧世界清单迁移 | [JSON](validation/seed-migration-road-dh.json)、[日志](validation/seed-migration-road-dh.log) |

实际 Epic 世界高度为 576；负数和零种子下的地形、气候及含水层噪声与指定种子的原生 oracle 一致。DH 的世界选项、worldSeed、结构检查均同步为独立种子。新清单读取及世界重载保留种子，服务器全局种子保持 20261023。旧世界的 24 个已有 region 文件迁移前后 SHA-256 完全一致：[校验记录](validation/seed-legacy-region-hashes.json)。

0.2.0 的这两组种子验证直接检查原生 FULL 区块和 DH 生成参数，没有重复完整道路规划、道路方块或客户端 LOD 渲染测试。原配套 RoadWeaver JAR 未变，其道路/LOD 基线结果保留如下。

## 0.1.0 基线验证

- 主模组：Java 21 / NeoForge 21.1.216 编译打包成功。
- JUnit：20 项通过（范围与溢出 3、连续完成进度 4、负载门控 6、异步文件存储 4、DH 坐标与模式 3）。
- 原生 NeoForge GameTest：5 个用例通过。其中实际执行了世界生命周期、预生成暂停恢复及排空、显式命名空间不被自建世界名遮蔽、DH 延迟回调与卸载竞争保护；没有安装 DH 的基础环境中，DH lease 用例跳过实际 API 检查。生命周期覆盖标记方块卸载重载持久化、创建不预热区块、FULL 非阻塞。记录：[base-gametest.log](validation/base-gametest.log)。
- 真正 DedicatedServer：加载 Epic Terrain Compatible 1.0.3 和 DH 3.3.3，15 个检查通过，包含噪声世界创建、运行时高度保留、DH 临时 override 取得/释放、命令暂停恢复、FULL 生成、卸载重载。检查阶段耗时约 39 秒，不含开服。记录：[JSON](validation/epic-dh-smoke.json)、[日志](validation/epic-dh-smoke.log)。
- 专服测试使用 `-XX:ActiveProcessorCount=4 -Xms1G -Xmx6G`，仅绑定 `127.0.0.1:25589`。实际宿主机为 i7-12700H，**不是 9950X 的吞吐基准**。没有修改 EULA 文件，使用 NeoForge 开发启动器自带的开发环境流程。
- RoadWeaver 最终分支：构建、LWJGL 打包验证通过，117 项测试中 110 项执行成功、7 项跳过、0 失败。包含新增粗采样插值、网格边界和配置指纹验证。记录：[构建日志](validation/roadweaver-build.log)。

## 最终组合独服验证

在新存档中同时加载 World Admin 0.1.0、Epic Terrain Compatible 1.0.3、RoadWeaver 2.3.1-worldadmin.1-1.21.1、DH 3.3.3；4 个 JVM 可见处理器、6 GB 堆。自动验证 **19 个检查全部通过**，检查阶段耗时约 505.8 秒（含首次道路准备、生成与排空；不是每个区块的平均耗时）。

- 使用实际噪声主世界模板创建新世界，继承高度且没有同步出生区块预热。
- DH 临时暂停、恢复，以及预生成任务暂停/恢复/完成均通过。
- 世界 A 中产生 10 条道路记录；世界 B 同坐标范围道路与连接记录为 0，验证存储和缓存隔离。
- 原生 FULL 区块在 `(407,84,-2288)` 检测到实际 `minecraft:mud_bricks` 路面。
- 直接运行真正的 DH FEATURES 生成器，规划屏障的拦截/委托/完成计数为 `1/1/1`；生成 4,096 个非空 LOD 列。
- 解码实际 LOD 数据点，在 145 列、145 个垂直片段中检测到 `mud_brick_slab`、`mud_bricks`、`packed_mud` 道路材料；仅有调色板条目不算通过。
- 两个托管世界都正常卸载，世界 A 的持久定义可重载，再次卸载和整个服务器关停均正常。

记录：[最终 JSON](validation/combined-smoke.json)、[最终日志](validation/combined-smoke.log)。该验证包含已有区块读取及 DH FEATURES 路径，未连接真实客户端，不等于客户端缓存/画面已验收。

组合测试后仅修正了命令显式命名空间选择，并通过上述最终 GameTest 回归；同时补齐 JAR 内授权和来源文件。发布包逐类比对确认 RoadWeaver 字节码未改变，主模组仅 `WorldAdminCommands.class` 改变，世界生成核心与组合测试一致。测试辅助类未进入发布 JAR。记录：[打包核对](validation/package-verification.json)。

## 粗规划性能基线

原始 8 格粗采样在 Epic＋RoadWeaver＋DH 真独服中触发 600 秒道路准备超时，未到实际区块提交阶段；状态记录 EWMA 约 0.2 ms、1 个道路准备请求、0 个原版区块请求。服务器仍可正常停服，未发生 watchdog 崩溃。这个结果验证了限流/超时保护，却**没有通过道路功能验收**。

当时一个 160 区块半径范围需要 441 个高度场 tile、410,881 个核心样本，600 秒只完成约 84 个 tile。最终实现采用可配置的粗采样间距与插值，保持最终路径逐点精确高度计算，另将规划单元围绕原点居中。32 格策略的日志记录为 25,921 个核心样本；这一策略已完成上述新存档组合验收，但会改变选路，不承诺与原版路线一致。

原始基线：[JSON](validation/road-step8-timeout.json)、[日志](validation/road-step8-timeout.log)。

## 测试中发现并修复

- 1.21.1 公共 `getChunkFuture` 在主线程会等待，因此改用访问转换器开放的非阻塞请求入口。
- 将完成回调异常处理与 future 取值分离，确保回调不会重复执行。
- 限流采样加入调度器自身开销；排队和在途传送统一去重。
- 命令中的完整维度 ID 需要 `ResourceLocationArgument`，否则 Brigadier 普通单词参数不能接受冒号。
- DH 3.3.3 自身把 GameTestServer 强制转换成 DedicatedServer，无法运行组合 GameTest。已改用真实 DedicatedServer 验证；原错误保留：[上游测试框架不兼容日志](validation/dh-gametest-upstream-failure.log)。

## 尚未覆盖

- 无本模组客户端的传送/重连与包含其他模组的完整整合包测试。
- 意外断电恢复、长时间内存/磁盘增长及高玩家负载下的 MSPT 分布。

当前已通过的测试不能证明任意整合包环境绝不触发 watchdog；单个第三方同步钩子不能被安全抢占。
