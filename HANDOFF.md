# 交接文档（给接手的 Codex / 其他 Agent）

最后更新：2026-10-13，提交 `c1640d4`（v2 阶段 2 完成）。先读本文件，再读 `CLAUDE.md`（约定与常用命令）和 `PLAN-v2.md`（当前计划，英文）。

## 1. 项目是什么

MC-MCP 让 AI 通过 MCP 在 Minecraft 里看、建、玩。

- `mod/`：Fabric 模组，Minecraft **1.21.1**、Java 21、Mojang 映射。它在 `127.0.0.1:25580` 上提供 WebSocket + JSON-RPC 服务，用 token 鉴权，token 在游戏目录的 `config/mcmcp.json`。
- `bridge/`：Rust MCP 服务（`rmcp` 3.5.1，stdio）。每个 MCP tool 转成一次 JSON-RPC 调用。
- `protocol/spec.md`：协议的唯一权威来源（中文）。修改协议时，`spec.md`、`mod/` 和 `bridge/` 三处要同时改。
- `docs/USAGE.md`：用户手册，含全部 39 个工具。`docs/building-plan.md`：建筑方案格式。`docs/decisions.md`：设计决策 D1–D26。`docs/manual-test.md`：手动测试清单。

PLAN.md（v1，阶段 1–5）已全部完成。PLAN-v2.md 的进度：

| 阶段 | 状态 |
|---|---|
| 1 地形分析、建筑方案、建筑校验 | ✅ `97ef100` |
| 2 自主视觉迭代（inspect / revise） | ✅ `c1640d4`（缺口见 §5） |
| 3 原版红石工程 | ⬜ 下一步 |
| 4 统一工作流 | ⬜ |
| 5 测试、可靠性、安全 | ⬜ |

PLAN-v2 的硬性规则：
- 只用原版（不要 Create 等模组），不改 MC 版本。
- 每个阶段都要有真实代码、实际跑过的测试和真实结果，同时更新 MCP 文档和示例。
- 阶段结束时总结改动的文件和局限。
- 不要声称“能用”而没有验证。

## 2. 环境与命令

- 工具链：Java 21，Gradle 9.7.1，**Fabric Loom 1.17.21**（不要升到 1.18，它需要 Java 25）。
- 依赖：Java-WebSocket 和 Rhino 1.9.1 用 `include` 打包。WorldEdit 7.3.8 是可选依赖（`modCompileOnly` + `modLocalRuntime`）。

```bash
cd mod && ./gradlew build            # 构建模组
cd mod && ./gradlew runGametest      # 24 个 GameTest，约 40 秒；结果在 mod/build/gametest/junit.xml
cd mod && ./gradlew runClient        # 带模组启动游戏
cd mod && ./gradlew genSources       # 反编译源码；不确定 MC API 时查源码，别凭印象
cd bridge && cargo test              # 18 个单元测试
cd bridge && cargo clippy --all-targets   # 保持 0 警告
cd bridge && cargo build --release   # 生成 bridge/target/release/mc-mcp-bridge(.exe)
```

当前结果：GameTest 24/24 通过，`cargo test` 全部通过，clippy 0 警告。

### 实机调试（Windows + Git Bash）

- `dev/restart_client.sh` 会结束占用 25580 端口的进程，然后用 quickPlay 进入测试世界 `mod/run/saves/mcmcp-test2`，等待模组开始监听。
- `python dev/call_tool.py <tool> '<json>' [图片前缀]` 通过 release 版 bridge 调用一个 MCP 工具。图片存到当前目录；设置 `FULL=1` 输出不截断的文本。
- 旧世界 `mcmcp-test` 里的玩家已经死亡，截图全黑，**不要用**。删除它需要用户自己动手，见 §6。

## 3. 架构要点

- **线程**：读写世界只能在服务器主线程。`RpcDispatcher` 提供三种注册方式：`onMainThread`、`onNetworkThread`、`async`。`async` 的处理器返回 `CompletableFuture`，适合截图这类需要等待的调用。
- **写世界**：一律通过 `jobs/`（`PlaceJob`、`RestoreJob` 按 FIFO 执行，每 tick 有预算）和 `BlockSnapshot.SET_FLAGS`，并记录到 `ChangeSet`，以便撤销和重做（D6）。
  - `SET_FLAGS = UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS`，**不发邻居更新**。这对阶段 3 很关键，见 §4。
- **`Plan`**（`api/Plan.java`）：先校验，再 `submit(services)` 成为任务。
- **`PreviewManager`**：只发给客户端的假方块（区块段包），不改动世界。
- **截图**：`ViewHook`，实现在 `src/client`，只有单人客户端可用。
- **假玩家**：`agent/`，`AgentPlayer`，A* 寻路（`Pathfinder`）。
- **建筑**（`arch/`）：
  - 编译：`BuildingPlan`（JSON）→ `PlanCompiler` → `BuildingGrid`，再得到 `worldCells()`，即“位置 → (方块状态, 组件 id)”。
  - 校验：`PlanValidator`。
  - 选址：`SiteAnalyzer` + `Terrain`。
  - 局部坐标：从正面看 x 从左到右，z 从后到前，y=0 是地板层。
  - 朝向与旋转：south=不转，west=顺时针 90°，north=180°，east=逆时针 90°。origin 是旋转后占地的最小角。
- **阶段 2 新增**：
  - `BuildingRegistry`：按 name 登记，存盘到 `<游戏目录>/mcmcp/buildings/`。
  - `PlanOps`：update / remove / add / set 操作。
  - `DesignMetrics`：对称、窗墙比、屋顶、需求检查。
  - `ViewPlanner`：自动视点，带遮挡判断。
  - `ArchApi.inspect` / `revise` / `get`，以及 `PreviewManager.update`（只更新预览中的差异方块）。
- **错误码**：1001 超出允许区域，1002 过大，1003 方块未知或被禁用，1004 区块未加载，1005 任务不存在，1006 脚本错误，1007 建筑校验失败。参数错误用 -32602。

## 4. 下一步：PLAN-v2 阶段 3（原版红石）

验收标准：AI 能设计、建造、激活并**验证**一个可以工作的 2×2 活塞门。

1. **先调查邻居更新的影响**（PLAN 明确要求）。
   - 现状：用 `SET_FLAGS` 放下的红石线、中继器、活塞不会收到邻居更新，可能“放好了但不工作”，也可能状态不一致。
   - 建议：给红石相关的放置加一种“有界更新模式”。放置完成后，对本次改动的位置及其 6 个邻居调用 `level.updateNeighborsAt` / `neighborChanged`，或者对红石方块用 `Block.UPDATE_ALL` 放置。
   - 要求：影响范围限制在改动包围盒外扩 1 格。撤销（`RestoreJob`）时同样处理。
   - 把取舍写进 `decisions.md`，编号 D27。
2. **新增 RPC 和工具**：
   - `redstone.inspect`：网络图，包括电源、导线功率、中继器延迟和朝向、比较器、活塞的伸缩状态、侦测器、门。
   - `redstone.trigger`：按钮、拉杆、压力板，或者放置/移除红石块。
   - `redstone.observe`：在 N 个 tick 内采样指定位置的状态。
   - `redstone.test`：给定输入序列和期望输出，返回差异与可能的故障点。
   - 每个 RPC 对应一个 MCP 工具，命名见 PLAN-v2 §3.3。
   - observe 需要跨 tick：参照 `JobManager`，或者用 async 处理器配合 tick 回调。
3. **建议提供 2×2 活塞门的参数化模板**，例如 `examples/redstone/piston-door-2x2.json`。可以作为建筑方案的组件，或者独立的蓝图/脚本。
4. **GameTest**：建门 → 拉杆打开 → observe 确认 4 个方块都缩回、门洞是空气 → 关门 → 确认复原。还要有一个故意接错线的电路，`test` 必须能报告故障位置。
5. **文档**：更新 `spec.md`、`USAGE.md`、`manual-test.md`，以及 bridge 的 `build_assistant` 提示词（`bridge/src/resources.rs`）。

阶段 4（统一工作流，例如“把入口换成自动活塞门”）会把建筑登记表和红石结合起来。阶段 5 要补测试、安全限制（每次红石更新的范围、tick 预算）和性能。

## 5. 已知问题与坑

- **GameTest 的 `thenExecute` 失败后不会停下**：`executeWithoutFail` 只调用 `parent.fail(...)`，后面的步骤在同一 tick 内照样执行，最后报告的可能是**后面某步**的错误。
  - 阶段 2 的“登记表被重置”假象就是这样来的：真正失败的是第一步的核对断言（玻璃板连接属性，已修复，见 D26）。
  - 调试时在每步开头 `System.out.println`，日志里会显示为 `[STDOUT]`。
- **GameTest 布局**：
  - 同一批次共享基准位置，每个测试场地 8×8，四周有屏障。
  - 大型建筑测试要放到 `ArchGameTests.FAR = (400,0,0)` 的偏移处：房子在 FAR，屋顶测试在 FAR+(0,0,120)，世界修改测试在 FAR+(0,0,240)，选址测试在 (-400,0,0)。
  - 用 `loadChunks: true`，或调用 `Guard.checkChunks(..., true)`。
  - 服务器 tick 不等待：要等任务完成用 `thenWaitUntil(() -> McMcpGameTests.assertDone(helper, jobId))`。
- **`AgentGameTests.chopCraftAndMineCobblestone` 曾经不稳定**：报错“agent is standing where … should go”。`PlaceTask` 已加 `clearOf(pos)` 目标，最近一次运行通过，但不保证 100% 稳定。
- **阶段 2 没做实机验收**：带真实截图、由视觉模型判断的完整“预览 → 观察 → 修改”循环没在游戏里跑过，GameTest 只覆盖确定性的部分（`images: false`）。建议：
  1. 用 `dev/restart_client.sh` 启动游戏。
  2. 依次调用 `preview` 两层房子示例（`examples/buildings/two-story-house.json`，origin 选在玩家附近）、`inspect_building`、`revise_building`。
  3. 检查各视点图片是否合理，尤其是室内视角和遮挡。
- **验证器**：世界核对会忽略 open/powered/occupied/lit，并按邻居规范化连接形状（`ArchApi.verify`）。阶段 3 加红石后，powered 状态是否该参与核对需要重新考虑。
- `PlanOps` 的 `set` 用 `field`（顶层字段），不是路径。屋顶通过它的 id 用 `update` 修改。

## 6. 需要用户处理的事

- 删除旧测试世界 `mod/run/saves/mcmcp-test`（玩家已死亡）。删除数据请让用户自己做，不要绕过安全检查。
- push 前先问用户。

## 7. 本次（阶段 2）改动的文件

- mod：
  - `arch/` 下新增 `BuildingRegistry`、`PlanOps`、`DesignMetrics`、`ViewPlanner`；修改 `PlanCompiler`、`PlanValidator`、`SiteAnalyzer`。
  - `api/` 下修改 `ArchApi`、`PreviewApi`、`BlueprintApi`。
  - 修改 `preview/PreviewManager`、`Services`、`McMcpConfig`（`maxRevisionRounds`）、`agent/PlaceTask`。
  - GameTest：新增 `RevisionGameTests`。
- bridge：`tools/arch.rs`（`inspect_building`、`revise_building`、`get_building`），`resources.rs`（工作流提示词）。
- 文档：`spec.md`、`USAGE.md`、`building-plan.md`、`decisions.md`（D25、D26）。
