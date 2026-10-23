# World Admin 0.2.0 安装说明

运行环境：Minecraft 1.21.1、Java 21、NeoForge 21.1.216 或兼容的后续 21.1 版本。本次验证使用 21.1.216。

1. 停服并备份存档，用 `worldadmin-0.2.0.jar` 替换服务端 `mods` 内的旧 World Admin JAR，不能保留两个版本。
2. 需要 RoadWeaver 多世界道路时，移除原 RoadWeaver JAR，放入 `roadweaver-neoforge-2.3.1-worldadmin.1-1.21.1.jar`。两份 RoadWeaver 不能共装。
3. Epic Terrain Compatible 1.0.3 和 Distant Horizons 3.3.3 使用各自原 JAR。World Admin 不需要装到客户端；其他模组的客户端要求仍遵循其本身规则。
4. `-sources.jar` 是源码附件，**不要放入 mods**。SHA-256 校验值在 `SHA256SUMS.txt`。

首次使用（OP 权限等级 2）：

```text
/wa compatibility
/wa create resource seed 12345
/wa seed resource
/wa pregen start resource 32
/wa status
/wa pregen pause worldadmin:resource
/wa pregen resume worldadmin:resource
/wa tp resource 100 200
```

预生成半径及其可选中心坐标单位为区块，传送 x/z 单位为方块。半径 32 为 4,225 个目标区块，存在邻区生成依赖。传送需要该列有安全落脚点。

4 核 / 16 GB 机器建议先使用 Java 21、`-Xms6G -Xmx10G`；已分配 4 核时可加 `-XX:ActiveProcessorCount=4`。默认原版目标区块在途上限 1，道路共享工作线程在 4 核下默认 1，MSPT 或堆压力过高时停止新准入。不要关闭 watchdog 代替排查。

配置在 `config/worldadmin-server.toml`，存档 `serverconfig/` 可覆盖。道路粗采样默认 32 格，可用 JVM 参数 `-Droadweaver.coarseSampleStep=8` 或 `=16` 提高采样密度；更细会明显增加首次规划时间。最终路面高度仍逐点精采，路线与原版可能不同。

支持独立种子：`/wa create resource seed 12345`、`/wa create resource seed random`、`/wa create cave minecraft:the_nether seed -98765`。省略种子会固定创建时的服务器种子；随机种子也只生成一次。`/wa seed resource` 可查询已卸载世界的值。种子写入存档 `worldadmin/worlds.json`，并由维度目录的 `worldadmin-seed.json` 绑定；创建后不能直接改种子，冲突时拒绝加载，需另建新世界。

旧世界自动迁移并保留原种子数值及原有随机序列行为，不重新生成已有区块。时间、规则和主世界边界仍共享。重启后预生成任务默认暂停，恢复时重新检查已有区块。先预生成准备开放的区域；自由探索不是全部受本模组屏障控制。全新原主世界的出生区块不保证已有道路，不补建已经生成区块的道路。

仅有旧 `roadweaver_world_data.dat` 而无 JSON 状态的道路存档需要离线迁移；加载会明确拒绝而保留原文件。常见的既有 JSON 状态可读取。

0.2.0 的独立种子已通过主模组 83 项单元测试、7 个 GameTest 和两组真实专服验证，包括 Epic 地形、DH 参数、RoadWeaver 指纹、旧清单迁移、区块重载及同种子对照。配套 RoadWeaver JAR 无需更新。0.1.0 已通过道路方块及 DH 道路 LOD 组合验证；完整整合包长期压测、真实客户端显示/重连仍需验收。

完整命令、源码与验证记录位于项目根目录的 `README.md`、`src/`、`compat/roadweaver/`、`docs/VALIDATION.md`。
