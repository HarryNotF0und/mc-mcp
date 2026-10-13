# 设计决策记录

## D1. 构建工具链：Fabric Loom 1.17.21 + Gradle 9.7.1（阶段 1）

**背景**：Fabric 官方 1.21.1 示例模组目前使用 Loom 1.18-SNAPSHOT，而 Loom 1.18 要求以 Java 25 运行 Gradle。PLAN.md 指定 Java 21，开发机也只装了 JDK 21。

**决定**：固定 `loom_version=1.17.21`（最后一个以 Java 21 运行的版本），插件 ID 使用经典的 `fabric-loom`（带重映射，支持 `loom.officialMojangMappings()`）。Gradle wrapper 沿用示例模组的 9.7.1。

**后果**：开发者只需 JDK 21。将来若升级到 Loom 1.18+，需要 JDK 25 运行 Gradle，模组本身仍可 `release = 21` 编译。

## D2. Java-WebSocket 以 `transitive = false` 引入（阶段 1）

Java-WebSocket 唯一的依赖是 `slf4j-api`，Minecraft 本身已经提供。只 `include` Java-WebSocket 本体，避免把第二份 slf4j 打进 jar。

## D3. `system.ping` 在网络线程响应，其余方法默认上主线程（阶段 1）

`RpcDispatcher` 注册处理器时显式区分 `onMainThread` / `onNetworkThread`。`ping` 不读取任何游戏状态，放在网络线程可以在服务器卡顿时仍然证明"连接是通的"；所有读写世界的方法一律走 `server.submit(...)`，满足"修改世界只在主线程"的约定，读取也一并上主线程以避免读到不一致的状态。

## D4. Bridge 采用按需连接 + 惰性重连（阶段 1）

Bridge 启动时不连接模组；每次工具调用发现没有可用连接时才连接并认证（连接超时默认 3 秒，`MCMCP_CONNECT_TIMEOUT_MS`）。连接断开时，所有在途请求立即以 `Disconnected` 失败，下一次调用自动重连。

**理由**：Claude Code 会在游戏启动前就拉起 bridge；单人游戏每次退出/进入世界都会关闭/重开 WebSocket。按需重连无需后台轮询，也让"游戏未启动"直接表现为一个清晰的工具错误而不是挂起。

**代价**：在没有工具调用时收不到推送。等阶段 2 引入任务进度推送时，若需要持续接收，再加后台重连循环。

## D5. 模组错误以 MCP 工具错误（`isError: true`）返回（阶段 1）

模组返回的 JSON-RPC 错误、连接失败、超时，都转换为文本形如 `error 1001: ...` 的工具结果并标记 `isError`，而不是 MCP 协议层错误。这样模型能看到错误码和信息并自行调整参数。

## D6. 写入不向区域外传播任何更新（阶段 2）

**背景**：最初按 `/setblock` 的方式写入（`UPDATE_CLIENTS`，保留邻居形状更新）。验收测试时，在树木旁建房再撤销后区域内有方块不一致：建造会改变区域外邻居的形状（树叶距离、蘑菇方块面、栅栏连接），并为它们排程计划刻；这些修改不在 ChangeSet 里，撤销无法还原。

**决定**：所有写入（放置、撤销、重做）使用 `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS`：
- 不触发方块更新（无物理、无红石、水不流入被挖开的位置）。
- 不更新邻居形状，所以区域外的方块永远不变，撤销/重做是精确的快照恢复。
- 放置的方块仍按已有邻居计算形状（`Block.updateFromNeighbourShapes`），同一任务内后放的方块会更新先放的邻居形状（并记入 ChangeSet），因此一次建造内部的栅栏、墙、楼梯是相连的。

**代价**：区域外已有的栅栏不会连到新墙上；被挖空的树留下的树叶不会腐烂。对建造工具来说这是可预期的行为（与 WorldEdit 默认一致），换来的是撤销的可靠性。

## D7. `loadChunks` 参数（阶段 2）

PLAN 中错误码 `1004` 表示区块未加载。只有出生点附近或玩家周围的区块是加载的，AI 常常需要在别处读写。读写方法都接受 `loadChunks`（默认 `false`）：`false` 时遇到未加载区块返回 `1004`，`true` 时在主线程同步加载/生成。没有加票（ticket），区块之后会照常卸载，因此每次调用都需要各自传 `loadChunks`。执行中的任务写入时若区块已卸载，`Level.setBlock` 会自动重新加载。

## D8. 任务串行执行，历史只在内存中（阶段 2）

- 写任务和撤销/重做共用一个 FIFO 队列，一次只运行一个。这样撤销的语义简单确定（总是撤销最近完成的修改），也不会有两个任务同时改同一区域。每 tick 的方块预算可以跨任务使用。
- 撤销/重做在任务开始执行时才选取 ChangeSet。
- 取消运行中的写任务会把已完成的部分作为 ChangeSet 记入历史；取消撤销/重做会把 ChangeSet 放回原栈（快照是绝对值，重新执行即可完成）。
- ChangeSet 用并行数组存储（每块约 16 字节 + 稀疏 NBT），50 个上限尺寸的 ChangeSet 约 200 MB。历史不持久化，服务器关闭即清空。

## D9. Bridge 写工具默认等待任务完成（阶段 2）

写工具和 undo/redo 先订阅推送再提交请求，然后等待对应 `jobId` 的 `job.finished`，最长 `MCMCP_JOB_WAIT_MS`（默认 20 秒）；超时则查询一次 `job.status`，返回当前进度和提示"用 job_status 轮询"。任务 `failed` 时以工具错误返回。

## D10. GameTest 不启动 WebSocket（阶段 2）

GameTest 直接调用 API 处理器，不需要网络；检测到 `-Dfabric-api.gametest` 时不监听端口，以免与正在运行的开发服务器抢占 25580。GameTest 放在独立的 `gametest` source set（独立 mod `mcmcp-gametest`），不会打进发布 jar。

## D11. 脚本：Rhino 的 `const` 作用域问题直接报错（阶段 3）

**背景**：Rhino 1.9.1 中 `let` / `const` 实际上按函数作用域处理。循环体内的 `const x = ...` 只在第一次迭代赋值，之后静默保留旧值（解构形式则报 "redeclaration"）。验收测试中示例脚本因此静默地建错了结构。AI 写脚本时极易使用这种写法。

**决定**：执行前用 Rhino 的 `Parser` 解析出 AST，若有 `const` 声明位于同一函数内的循环体中，直接返回 `1006` 并给出行号和"用 let 代替"的提示。`docs/script-api.md` 列出了该引擎支持/不支持的语法（`for (const v of ...)`、`class`、展开运算符不可用）。语言版本设为 `VERSION_ECMASCRIPT`，错误信息固定为英文（`Locale.ENGLISH`），不随系统区域变化。

## D12. 脚本在独立线程执行，读世界通过区块快照（阶段 3）

脚本最长运行 5 秒，放在主线程会让服务器卡住 5 秒，因此在单独的 `mcmcp-script` 线程执行（串行，一次一个脚本）。`b.get` 第一次访问某个区块时，用 `server.submit` 在主线程复制该区块各 section 的 `PalettedContainer`，之后在脚本线程本地读取；脚本自己 `set` 过的位置优先返回脚本中的值。脚本结束后在主线程做白名单/体积检查并提交任务。

时限由指令计数观察器（每 10000 条指令）检查墙钟时间，超时抛出 `Error` 子类，脚本中的 `try/catch` 捕获不到；行号通过 `Context.reportRuntimeError` 取得。Rhino 没有内存限制，脚本若在 5 秒内分配大量内存仍可能影响服务器，这一风险目前接受（只监听本机且需要 token）。

## D13. 蓝图可按名字保存在服务器上（阶段 3）

`build.saveRegionAsBlueprint` 返回完整 JSON 时，大区域会占用大量模型上下文。增加可选 `name`：保存到 `<游戏目录>/mcmcp/blueprints/`，只返回摘要；`build.placeBlueprint` 可按 `name` 读取。小区域仍可直接返回 JSON，便于 AI 查看和修改。蓝图不包含方块实体 NBT。

## D14. Bridge 的 `run_script` 支持 `sourceFile`（阶段 3）

`run_script` 可传 `sourceFile`（bridge 所在机器上的路径），由 bridge 读取后作为 `source` 发送，方便直接运行 `examples/scripts/` 中的脚本或 AI 先写入文件再执行。协议层仍只有 `source`。

## D15. 预览用 section 更新包，只含计划中的方块（阶段 4）

预览按区块 section 分组，每组发一个 `ClientboundSectionBlocksUpdatePacket`（大预览不会产生几十万个单方块包）。该包的构造函数从一个 `LevelChunkSection` 读取所列位置的状态，所以用一个新建的空 section 只写入计划方块即可。不能用 `PalettedContainer.copy()` 后写入：单值调色板的副本仍把扩容回调指向原容器，写入会抛异常（测试中实际遇到）。恢复时直接用真实 section 构造同样的包。预览前做与真实写入相同的检查，确认时直接提交同一个 Plan。

## D16. 截图：替换相机实体、临时缩放主帧缓冲（阶段 4）

- 相机：把 `Minecraft.cameraEntity` 临时换成一个不加入世界的 `Marker`（眼高为 0，位置即相机位置）；玩家模型因此会出现在画面中。
- 渲染目标：最初按原版全景截图的做法渲染到单独的 `TextureTarget`，结果是纯白图——`LevelRenderer` 在一帧中途会重新绑定主帧缓冲。改为把主帧缓冲和 `GameRenderer`/`LevelRenderer` 的辅助缓冲临时缩放到请求的分辨率，渲染后截取，再恢复。
- 渲染前补上 `Minecraft.runTick` 每帧的前置状态（清屏、`FogRenderer.setupNoFog()`、`RenderSystem.enableCull()`）。缺少这一步时画面背面可见、光照全黑。
- 每个镜头渲染 6 帧（间隔 15 ms），让新进入视野的 section 完成编译上传后再截取。
- `daylight`（默认开）：只在客户端临时把 `ClientLevelData` 的时间设为 6000，并刷新光照贴图，渲染完恢复；不用 `ClientLevel.setDayTime`，因为它会顺带修改客户端的游戏规则。
- 只支持客户端与服务器同进程（单人/局域网主机）。专用服务器上的玩家客户端截图需要自定义网络包，暂不做。

## D17. 单人游戏暂停时任务不推进，只提示不绕过（阶段 4）

单人游戏在暂停菜单打开时不执行服务器 tick（`IntegratedServer.tickPaused`），任务不推进，方块变化也不会同步给客户端；默认设置下窗口失焦（例如切到 Claude）也会暂停。我们不 hook 暂停逻辑去强行推进（那需要绕过区块同步等原版流程），而是在 `world.getInfo` 中报告 `paused`，并在提交的任务对象和未完成的 `job.status` 中加 `warning`，提示玩家关闭菜单或按 F3+P 关闭"失焦暂停"。截图不受影响（在客户端线程执行）。

## D18. WorldEdit 作为可选库集成（阶段 4 之后，按用户要求）

**目的**：用户希望借助 WorldEdit 简化建造。WorldEdit 的价值主要在两处：社区大量现成的 `.schem` 原理图，以及成熟的图案（pattern）语法（`50%stone,50%andesite`、`##wool` 等）。

**决定**：
- WorldEdit 为可选依赖（`fabric.mod.json` 的 `suggests`），开发环境通过 `modLocalRuntime` 加载以便测试。未安装时一切照常，只是图案和原理图功能返回清晰的错误。
- 只把 WorldEdit 当作"计算库"使用：解析图案、读写原理图文件。**所有放置仍走我们的任务队列**，因此撤销、预览、白名单、体积上限、方块黑名单都继续有效。不使用 WorldEdit 的 `EditSession` 直接写世界——那样会绕过我们的 ChangeSet，撤销不再可靠。
- 所有 WorldEdit 类型只出现在 `WorldEditBridgeImpl` 中，通过反射创建；其他代码只依赖我们自己的 `WorldEditBridge` 接口，未安装 WorldEdit 时不会加载这些类。
- 图案：`fill_region` 的 `block` 和脚本中 `b.set/fill/line/cylinder/sphere` 的方块参数，先按普通方块状态解析，失败且装有 WorldEdit 时按图案解析。图案每次产生的方块都过黑名单检查；由于图案是逐块求值的，黑名单方块会在执行时才被发现，任务以 `failed` 结束，已放置部分照常可撤销。
- 原理图：`place_blueprint` 的 `schematic` 参数读取 WorldEdit 原理图目录（`config/worldedit/schematics`）中的 `.schem` / `.schematic`，转换成我们的蓝图后复用旋转/镜像逻辑；`save_blueprint` 的 `format: "schem"` 写出 Sponge v3 原理图。方块实体 NBT 暂不导入导出。
- 顺带修复：`BlockStateParser` 解析到方块后会忽略后续文本（`stone,dirt` 被当成 `stone`），现在要求整个字符串都被消费，否则报 `1003`。

## D19. 假玩家：`ServerPlayer` 子类 + 假连接，自己跑移动物理（阶段 5）

- 参照 Carpet 的做法：`AgentPlayer extends ServerPlayer`，通过 `PlayerList.placeNewPlayer` 用 `FakeConnection` 加入。`FakeConnection` 覆盖所有会触碰 netty channel 的方法（`send`、`flushChannel`、`tick`、`setupInbound/OutboundProtocol`、`setupCompression`、`setReadOnly`、`disconnect`、`handleDisconnection`），测试中漏掉 `flushChannel` 曾导致服务器崩溃。
- 真实玩家的移动由客户端模拟；假玩家没有客户端，所以在 `tick()` 中额外调用 `doTick()` 执行移动物理，并每 10 tick 更新区块加载。移动通过设置 `zza`（前进）、朝向和跳跃输入实现，而不是直接传送，因此碰撞、重力、摔落伤害都是真实的。
- 放置方块：检查触及距离、目标可替换、有可依附面、方块能存活，消耗物品后直接 `setBlock` 精确状态（而不是模拟右键使用物品，后者的朝向取决于视角，不利于按蓝图建造）。
- 合成：直接用配方表和背包，不打开 GUI；超过 2×2 的配方要求附近有工作台。
- 假玩家不受建造白名单限制：它只能做生存玩家能做的事（要材料、要时间、会受伤）。

## D20. 寻路：自研 A*（含挖掘），不集成 Baritone（阶段 5）

PLAN 建议在需要更强寻路时评估 Baritone。Baritone 是客户端 mod，依赖客户端玩家和输入系统，不能驱动服务器端的假玩家，因此不集成。实测中只会行走/跳跃/下落的 A* 在真实地形中会被困住（掉进无法爬出的洞穴、站在巨型蘑菇或树冠上），所以扩展了自研 A*：
- 允许挖穿、挖台阶向上、向下挖一格，代价为该方块用最佳工具的真实挖掘时间（`ticks / 5` 换算成行走格数）。
- 安全限制：不挖流体旁、会下落的方块及其下方、方块实体、基岩、超过 5 秒（100 tick）才能挖掉的方块；下落最多 4 格（半颗心）。
- `agent.goto` 默认不挖（避免破坏地形），`mineBlock` / `gather` / `buildBlueprint` 默认可挖。
- 工具耐久：选择工具时，若工具对该方块没有加速，则优先空手或非工具物品（实测中木镐被拿去挖泥土而耗尽）。
- 采集判断：原版战利品表对石头总是列出圆石，"需要镐"的规则在破坏时单独判断；因此判断掉落时额外检查 `requiresCorrectToolForDrops`。没有这一步时，没有镐的假玩家会无限空手挖石头。
