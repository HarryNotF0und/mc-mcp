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
