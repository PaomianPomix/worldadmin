# World Admin — NeoForge 1.21.1

仅服务端多世界管理模组，面向 **4 核 Ryzen 9 9950X / 16 GB 内存** 的整合包服务器。客户端不需要安装本模组。当前版本 **0.2.0**，使用 Java 21、NeoForge 21.1.216。

Epic Terrain Compatible 的运行时生成配置保留和 DH 3.3.3 负载协调已实现。RoadWeaver 多世界支持使用本项目的 **2.3.1-worldadmin.1-1.21.1 配套分支**；原版 2.3.1 的全局状态无法直接安全支持新增维度。实际通过项目和未覆盖范围见 [验证记录](docs/VALIDATION.md)，设计与版本限制见 [兼容报告](docs/COMPATIBILITY.md)。

## 构建与安装

在 Java 21 环境运行：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat test build
```

生成 `build/libs/worldadmin-0.2.0.jar`，替换服务端 `mods` 中的旧 World Admin JAR。配置默认写入 `config/worldadmin-server.toml`，存档的 `serverconfig/worldadmin-server.toml` 可覆盖该配置；修改后重启。本模组入口仅在专用服务端注册。

需要道路支持时，再构建配套分支：

```powershell
Set-Location compat/roadweaver
.\gradlew.bat :neoforge:build :common:test
```

将 `neoforge/build/libs/roadweaver-neoforge-2.3.1-worldadmin.1-1.21.1.jar` 放入服务端，**替换**原 RoadWeaver JAR，不能同时安装两个 RoadWeaver。配套源码及 MIT 授权保留在 `compat/roadweaver`，来源见该目录 `UPSTREAM.md`。主模组与分支各自包含 Gradle wrapper，均使用 Java 21。

现有存档安装前应备份，首次在测试服验证整合包。这里的测试不是完整整合包的稳定性认证。

## 管理命令

所有命令要求权限等级 2（OP 或控制台）。世界名称仅允许 1–48 位小写字母、数字、下划线。新世界维度 ID 为 `worldadmin:<名称>`。

| 命令 | 作用 |
|---|---|
| `/wa templates` | 列出启动时加载的维度模板 |
| `/wa create resource` | 复制实际 `minecraft:overworld` 生成配置并加载新世界 |
| `/wa create resource seed 12345` | 使用独立种子 12345 创建主世界模板的新世界 |
| `/wa create resource seed random` | 随机选择一次种子并持久保存，重载不重新抽取 |
| `/wa create cave minecraft:the_nether` | 从指定已注册维度模板创建 |
| `/wa create cave minecraft:the_nether seed -98765` | 指定模板及独立种子，支持负数和零 |
| `/wa seed resource` | 查询托管世界的固定种子，卸载后也可查询 |
| `/wa list` | 显示已加载维度和已卸载的托管世界 |
| `/wa load resource` | 加载已定义的世界 |
| `/wa unload resource` | 无玩家、无在途任务、区块工作排空后保存并卸载 |
| `/wa tp resource` | 排队准备目标区块，传送本人到 x=0,z=0 的安全位置 |
| `/wa tp minecraft:overworld 100 200` | 指定目标世界和方块坐标，控制台不可使用 |
| `/wa pregen start resource 32` | 以区块 (0,0) 为中心生成半径 32 的正方形，共 4,225 个目标区块 |
| `/wa pregen start resource 32 100 -100` | 自定义中心，后三个数字单位均为**区块** |
| `/wa pregen pause worldadmin:resource` | 停止发起新请求，在途生成自然完成 |
| `/wa pregen resume worldadmin:resource` | 恢复任务，仍遵守压力限制 |
| `/wa pregen cancel worldadmin:resource` | 移除已排空任务；若在途先暂停，排空后重试 |
| `/wa status` | 查看负载门控、在途数、DH 状态及进度 |
| `/wa compatibility` | 查看运行时模组版本与已知兼容限制 |
| `/wa roads retry resource 32` | 重置指定范围失败道路的重试预算；然后手动恢复预生成 |
| `/wa roads retry resource 32 100 -100` | 指定重试区域中心，单位为区块 |

预生成任务 ID 是完整维度 ID，每个维度同时保留一个任务，最多保留 32 个任务。任务生成完成后可 `cancel` 清除记录再建立新范围。由于 Minecraft 生成阶段有邻区依赖，实际读取/生成范围可能比目标正方形更大。

默认传送只寻找目标列中“脚下支撑、两格空气、无常见伤害地板”的位置，不建造平台。不保证识别第三方所有伤害方块；下界顶部/洞穴等地形应由管理员选点。未找到落脚点则拒绝传送。

## 压力控制

- 全服默认最多 **1 个目标区块请求在途**，预生成和传送共用额度；一次 tick 最多发起一个请求。
- 最短发起间隔 100 ms，随 tick 平均耗时变长而放缓，最长默认 2 s。
- tick EWMA 达到 40 ms 停止发起，低于 30 ms 恢复；单次 tick 超过 100 ms 冷却 5 s。采样包含本模组上一次调度开销，以及两刻之间处理区块任务造成的超时；扣除正常刻间隔。
- JVM 堆占用达到 85% 暂停，降到 78% 恢复。该指标不是整个系统的内存或 native/DH 缓存占用。
- 原版区块阶段超过 120 s、道路准备阶段超过 600 s 后暂停任务并排空；不会取消 Minecraft 共享 future，也不会继续堆积请求。道路阶段未发起区块请求，因此没有提前持有区块票据。两种超时可分别配置；长期不排空需排查生成器日志。
- 没有在 tick 或命令中调用 `join` / `get` 等待区块。1.21.1 的公共 `getChunkFuture` 在主线程会 `managedBlock`，因此使用访问转换器开放真正返回 future 的 `getChunkFutureMainThread`。
- 采用不强制实体 ticking 的持久区块票据，完成后释放。没有创建自己的世界生成线程池，也没有从任意工作线程修改 `ServerLevel`。
- 元数据快照在单独 I/O 线程合并写入，写入失败阻止新的生成请求。
- 配套 RoadWeaver 全世界共享工作线程预算，4 核默认 1 个、硬上限 2 个，排队上限 256；道路状态异步加载与合并保存。每条失败道路最多自动尝试 3 次，之后由管理员重置预算。
- DH 3.3.3 自主生成 detail 0 的 FEATURES / INTERNAL_SERVER 时，先异步准备道路，再继续 DH 原生成器；此入口最多 1 个执行任务、64 个等待项。开启实体预生成前排空该入口并临时禁用 DH 新生成，完成后恢复。更粗糙的 surface LOD 原本不执行 FEATURES，不保证显示道路。

这些措施限制**新增任务量**，不能抢占模组正在执行的单个同步生成阶段、世界构造钩子、GC 或磁盘卡顿，不能承诺绝不触发 watchdog。一个 FULL 目标区块也会调度多个依赖区块，`maxInFlight=1` 不等于一个 CPU 线程。

4 核 / 16 GB 基线建议：Java 21、先给 JVM `-Xms6G -Xmx10G`，余量留给系统、native 内存与 DH；只有确实分配 4 核时使用 `-XX:ActiveProcessorCount=4`，它不是 CPU 硬配额。DH 自身后台线程从 1 开始，其他异步世界生成模组也需分别限流。不要靠关闭 watchdog 掩盖问题。

## 世界与恢复语义

新世界使用注册表感知的 `LevelStem.CODEC` 复制模板，不手工重建原版噪声生成器、不绕过第三方 Features/Structures。每个世界可指定独立的 64 位整数种子。省略 `seed` 时沿用原命令习惯，将创建时的服务器种子固定到新世界；`seed random` 则生成并固定一个随机值。相同模板、种子和生成配置下保持确定性。时间、天气、游戏规则、出生点数据和边界仍跟随主世界。

种子保存于 `<存档>/worldadmin/worlds.json` 的 `seed` 字段，使用十进制字符串避免大整数精度丢失；对应维度目录还有 `worldadmin-seed.json` 固定种子锁。种子在世界构造前就参与 `RandomState`、群系缩放、结构检查、结构分布和随机序列初始化，DH 3.3.3 的世界生成参数也使用该种子。新建世界有独立的持久随机序列。

升级会把旧 v1 世界定义固定为升级时主世界存档的种子，并保留旧随机序列共享行为，避免改变既有世界行为。升级不会重新生成已有区块。创建后的种子不可通过编辑清单更改；种子与锁不匹配时拒绝加载，应创建另一个世界使用新种子。备份和恢复时同时保留世界定义、维度目录及种子锁。

创建阶段构造 `ServerLevel` 并发出标准加载事件，但不生成出生区块、不调用原版出生点扫描。世界初始化本身和模组事件仍必须在主线程执行。默认仅保留本服务创建的世界，拒绝覆盖已有维度目录或接管数据包维度。

维度数据在 `world/dimensions/worldadmin/<名称>/`；定义与任务在 `world/worldadmin/`。删除/导入/复制现有世界未纳入首版。加载状态持久化，服务器启动时恢复此前加载的世界；卸载只管理本模组创建的世界。服务器关闭由原版统一保存和关闭，避免重复关闭。

预生成进度在内存中只推进连续成功区间。磁盘保存 `observedCursor` 供观察，但 FULL future 完成不意味着 region 文件已落盘，因此**每次重启恢复任务时都暂停，并从范围起点重新检查已有区块**；已存在区块直接加载，缺失区块才生成。此方式牺牲重启后的扫描时间，避免断电后漏生成。不会在正常运行期间为进度强制全服同步保存。

RoadWeaver 按 64×64 区块规划单元准备道路，并加 128 区块结构探测余量。首次准备可能明显慢于后续区块；目标在规划边界上时可能需要多个单元。只有准备成功才提交 FULL。配套分支仅对主世界及本模组使用主世界维度类型的新世界开放道路，下界/末地不会被误当作主世界。既有 JSON 数据保持权威；仅有旧 NBT `roadweaver_world_data.dat` 而没有 JSON 的存档会拒绝加载道路状态，需先离线迁移，不会覆盖旧文件。

规划单元以原点居中，默认的原点附近任务只需准备一个单元。粗地形采样默认间隔 32 格，双线性插值用于选路，A* 步长及最终逐点精确路面高度计算保持独立。可用 JVM 参数 `-Droadweaver.coarseSampleStep=8` 或 `=16` 提高粗规划采样密度；合法值为 8/16/32。更细采样明显增加首次准备时间，粗采样会改变道路走向和细小水体附近的选路。新配置的道路工作占比默认 100%，线程总数仍受全局预算限制；已有配置显式设置的占比会保留。

本分支不再阻塞开服等待原主世界初始道路规划，因此全新原主世界的出生区块可能先于道路准备生成，也不会自动补建已生成区块的道路。新托管世界通过本模组的预生成、传送准备及支持的 DH 入口获得道路准备屏障；开放给玩家自由探索前，应先预生成计划使用的区域。

## 验证

```powershell
.\gradlew.bat test build
.\gradlew.bat runGameTestServer
```

JUnit 覆盖范围边界/溢出、失败和乱序完成、恢复进度、压力迟滞/冷却与磁盘快照。GameTest 用真实 NeoForge 检查动态世界注册、模板复制和区块 future。实际执行结果见 [验证记录](docs/VALIDATION.md)。

上线前仍需用完整模组清单验证：客户端无本模组连接、跨世界传送与重连、普通停止后重启、进程意外退出恢复、长时间预生成的 MSPT/GC/内存，以及客户端实际看到的 DH LOD。不要同时运行多个独立预生成控制器。
