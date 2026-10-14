# 交接文档（给接手的 Codex / 其他 Agent）

最后更新：2026-10-14（Claude），在 `75c15f1` 之上修复一次大型实际建造（城堡）反馈的 17 个问题，见 §8；此前 2026-10-13 基于 `d0ade3b` 追加了登记表生命周期/持久化修复与性能优化（D28）。先读本文件，再读 `CLAUDE.md`（约定与常用命令）和 `PLAN-v2.md`（当前计划，英文）。完整审查和测量结果见 [docs/review-2026-10-13.md](docs/review-2026-10-13.md)。

## 1. 项目是什么

MC-MCP 让 AI 通过 MCP 在 Minecraft 里看、建、玩。

- `mod/`：Fabric 模组，Minecraft **1.21.1**、Java 21、Mojang 映射。它在 `127.0.0.1:25580` 上提供 WebSocket + JSON-RPC 服务，用 token 鉴权，token 在游戏目录的 `config/mcmcp.json`。
- `bridge/`：Rust MCP 服务（`rmcp` 3.5.1，stdio）。每个 MCP tool 转成一次 JSON-RPC 调用。
- `protocol/spec.md`：协议的唯一权威来源（中文）。修改协议时，`spec.md`、`mod/` 和 `bridge/` 三处要同时改。
- `docs/USAGE.md`：用户手册，含全部 43 个工具。`docs/building-plan.md`：建筑方案格式。`docs/decisions.md`：设计决策 D1–D32。`docs/manual-test.md`：手动测试清单。

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
cd mod && ./gradlew runGametest      # 53 个 GameTest；结果在 mod/build/gametest/junit.xml
cd mod && ./gradlew runClient        # 带模组启动游戏
cd mod && ./gradlew genSources       # 反编译源码；不确定 MC API 时查源码，别凭印象
cd bridge && cargo test              # 25 个单元测试
cd bridge && cargo clippy --all-targets   # 保持 0 警告
cd bridge && cargo build --release   # 生成 bridge/target/release/mc-mcp-bridge(.exe)
```

当前结果（2026-10-14）：`gradlew build runGametest` 成功，GameTest 53/53 通过，`cargo test` 25/25、`cargo clippy --all-targets` 0 警告。release 版 bridge 没有重新编译：`bridge/target/release/mc-mcp-bridge.exe` 正被用户的 MCP 客户端（3 个进程）占用，需关闭这些客户端后再 `cargo build --release`。以下为 2026-10-13 的记录：GameTest 46/46 通过，`cargo test` 22/22、`cargo clippy --all-targets -- -D warnings`、`cargo build --release` 均通过。上轮四个回归测试及本轮排队状态/容器恢复测试在旧代码上失败，修复后通过。Windows 沙箱中测试需要 Gradle 缓存和 localhost socket 权限；无权限时 Rust 的 socket 错误不是代码失败。`cargo fmt --check` 的原有格式差异尚未做全库调整。性能测量：1791 方块建筑的 worldCells() 预热后调用 1000 次，58.4216 ms / 292,784,000 字节分配 → 0.0800 ms / 0 字节；这是方法局部测量，不是整体 TPS 测量。

### 实机调试（Windows + Git Bash）

- `dev/restart_client.sh` 会结束占用 25580 端口的进程，然后用 quickPlay 进入测试世界 `mod/run/saves/mcmcp-test2`，等待模组开始监听。
- `python dev/call_tool.py <tool> '<json>' [图片前缀]` 通过 release 版 bridge 调用一个 MCP 工具。图片存到当前目录；设置 `FULL=1` 输出不截断的文本。
- 旧世界 `mcmcp-test` 里的玩家已经死亡，截图全黑，**不要用**。删除它需要用户自己动手，见 §6。

## 3. 架构要点

- **线程**：读写世界只能在服务器主线程。`RpcDispatcher` 提供三种注册方式：`onMainThread`、`onNetworkThread`、`async`。`async` 的处理器返回 `CompletableFuture`，适合截图这类需要等待的调用。
- **写世界**：一律通过 `jobs/`（`PlaceJob`、`RestoreJob` 按 FIFO 执行，每 tick 有预算）和 `BlockSnapshot.SET_FLAGS`，并记录到 `ChangeSet`，以便撤销和重做（D6）。
  - `SET_FLAGS = UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS`，**不发邻居更新**。这对阶段 3 很关键，见 §4。
- **`Plan`**（`api/Plan.java`）：先校验，再 `submit(services)` 成为任务。稀疏列表检查实际涉及的区块，不加载包围盒中的空白区；建筑通过 `BuildingJob` 提交，执行时记录原始快照，结束与 undo/redo 同步登记状态。
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
  - `BuildingRegistry`：按 name 登记，存盘到 `<存档目录>/mcmcp/buildings/`；版本化元数据包含维度、编译基准、原始 NBT 和状态，串行后台写入/同名合并，正常关闭时 flush。旧游戏目录的纯方案只作为 planned 设计导入。
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
   - 把取舍写进 `decisions.md`，编号 D33（D27–D32 已用于可靠性和使用反馈修复）。
   - 注意 D30：现在默认 `loadChunks` 为 true；红石观察等新 RPC 也应用 `Params.loadChunks(params)`。
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
- **假玩家放置的斜对角相交已修复**：本轮重现“agent is standing where … should go”，发现 `clearOf(pos)` 的曼哈顿距离允许身体与目标角落相交。改为至少一个水平轴相隔 2 格，含第三层高度保护；新增确定性的 `placementWalksClearOfDiagonalCorners` 测试通过，生存验收也通过。
- **阶段 2 没做实机验收**：带真实截图、由视觉模型判断的完整“预览 → 观察 → 修改”循环没在游戏里跑过，GameTest 只覆盖确定性的部分（`images: false`）。建议：
  1. 用 `dev/restart_client.sh` 启动游戏。
  2. 依次调用 `preview` 两层房子示例（`examples/buildings/two-story-house.json`，origin 选在玩家附近）、`inspect_building`、`revise_building`。
  3. 检查各视点图片是否合理，尤其是室内视角和遮挡。
- **验证器**：世界核对会忽略 open/powered/occupied/lit，并按邻居规范化连接形状（`ArchApi.verify`）。阶段 3 加红石后，powered 状态是否该参与核对需要重新考虑。
- `PlanOps` 的 `set` 用 `field`（顶层字段），不是路径。屋顶通过它的 id 用 `update` 修改。
- **审查修复（D27）**：场地参数在访问区块前校验；完整方案与 ops 都不可改建筑名称；预览修改执行写入安全检查；失败的修改/确认保留当前方案和预览，失败的 `userApproved` 不重置轮数；自动淘汰清除登记表 ID；确认有建筑校验错误的预览返回 1007（可显式 `ignoreValidation: true`）；核对保留完整深地基列表，修改用原始地形编译，避免误删地基。脚本文档里的循环内 `const` 示例也已改为 `let`。
- **登记表问题已修复（D28）**：设计与已执行基准分离；排队/运行状态为 building，部分失败为 incomplete；确认/取消/undo/redo 同步基准。同名预览可安全丢弃/差分确认，删除部件恢复 NBT。持久化按存档隔离并保留维度；列表发现存盘记录，旧数据只作未绑定设计导入。确认保持预览方块范围，不随地形变化扩大。假玩家排队取消不再继续运行且释放 busy。邻居形状修改保留容器内容。Bridge 等待修改中的嵌套任务，超时返回报告和 job 状态。
- **后续限制**：截图视觉质量/真实客户端预览叠加仍需实机验证；自动化只测合成状态和截图 FOV 回调。登记 JSON 与 Minecraft 世界文件并非跨文件崩溃事务，骤停/磁盘错误后需核对。大型方案完整编译/重载仍在主线程，未做全流程性能测量。GameTest 登记目录按运行隔离，避免测试残留污染。

## 6. 需要用户处理的事

- 删除旧测试世界 `mod/run/saves/mcmcp-test`（玩家已死亡）。删除数据请让用户自己做，不要绕过安全检查。
- push 前需要用户授权（当前聊天用户已明确授权完成后推送 main）。

## 7. 本次（阶段 2）改动的文件

- mod：
  - `arch/` 下新增 `BuildingRegistry`、`PlanOps`、`DesignMetrics`、`ViewPlanner`；修改 `PlanCompiler`、`PlanValidator`、`SiteAnalyzer`。
  - `api/` 下修改 `ArchApi`、`PreviewApi`、`BlueprintApi`。
  - 修改 `preview/PreviewManager`、`Services`、`McMcpConfig`（`maxRevisionRounds`）、`agent/PlaceTask`。
  - GameTest：新增 `RevisionGameTests`。
- bridge：`tools/arch.rs`（`inspect_building`、`revise_building`、`get_building`），`resources.rs`（工作流提示词）。
- 文档：`spec.md`、`USAGE.md`、`building-plan.md`、`decisions.md`（D25、D26）。

## 8. 2026-10-14：大型建造反馈修复（Claude）

来源：另一个 agent 用这些工具建了一座城堡后的 17 条反馈（玩家在 x≈-350，在允许区域外；100×200 平地难找；楼梯撞头；截图全雾等）。
处理结果：除第 9 条（自动家具偏少，有意保持简单）外都已修。**还没做实机验收**：游戏客户端没有启动，release bridge 也没重编（原因见 §2）。

| # | 问题 | 处理 |
|---|---|---|
| 8 | 校验通过但楼梯撞头（最严重） | 根因：玩家上一级时身体还在下一级上方就已抬升，需要三格净空。编译器原来只开 2 格楼板洞；校验对台阶豁免了"第三格"检查（阶段 1 为消除误报加的）。现在 `PlanCompiler.headroom()` 统一计算需要的格子并开洞（满层高为 3 格）；寻路上/下一级都要求较低一列有第三格；新增错误 `STAIRS_HEADROOM`。D29。测试：`ArchGameTests` 的缺陷用例和建成后楼梯井 3 格为空气 |
| 1 | 1001 只给坐标 | `Guard.nearestAllowed`：消息和 `data.nearestAllowed` 给出同尺寸盒子最近能放的位置。测试 `outsideAllowedRegionSuggestsTheNearestSpot` |
| 2 | 找大块平地费力 | 新 RPC `site.findFlat` / 工具 `find_flat_area`（`arch/FlatFinder.java`）。半径 ≤256、尺寸 ≤256，网格采样，结果只取允许区域内的候选，默认只搜已加载区块。测试 `flatAreasAreFound`（测试世界那里是虚空，测试先自己铺地） |
| 3 | 单次 262144 上限 | `fill_region`/`clear_region` 加 `split:true`，按层/行拆成依次执行的任务，总量受 `maxSplitVolume` 限制（默认 4×）。outline 模式拆分后仍只放外壳（`shellCount`、`positions(area, shell, …)`）。测试 `largeFillsCanBeSplit` |
| 4 | 处处要 loadChunks | 配置 `loadChunksByDefault`（默认 true），`Params.loadChunks(params)`；`find_flat_area` 例外。D30 |
| 5 | 方案只能内联，返回太长 | bridge：`buildingFile`（validate/preview/place_blueprint/revise），`validate_building` 默认省略组件列表，只给 `componentCount`（`details:true` 恢复） |
| 6 | 参数名 | `run_script`/`preview` 接受 `file` 作为 `sourceFile` 的别名；`orbit_shots` 可传 `building` 代替 `region` |
| 7 | 门尺寸限制没写 | 错误消息和 building-plan.md 说明大门用 `arch` |
| 10 | 碰撞后要重发整个 JSON | 用 `buildingFile` 改文件后再调用 |
| 11 | 塔被屋顶遮住 | 新警告 `TOWER_HIDDEN`（塔高出周围屋顶不足 4 格时，写明需要的高度）。测试 `towerHiddenByTheRoofIsWarned` |
| 12 | Rhino 限制 | 实测 Rhino 1.9.1：展开、剩余参数、箭头函数、模板字符串可用；循环内 const 保留首值、`for (const …of)` 无法解析，而 let 都正确。所以执行前把 `const` 关键字换成 `let  `（等长，`ScriptSandbox.constToLet`，会跳过字符串/模板/注释），取代 D11 的拒绝策略（D31）。`class` 给出明确错误 |
| 13 | 没有只在空气处放置 | `keepExisting` 参数 + `b.keepExisting(bool)`，结果含 `skippedExisting` |
| 14 | 不支持 include | 参数 `libraries:[{name,source}]`，bridge 的 `includeFiles` 读文件；库中错误带文件名 |
| 15 | 远处截图全是雾 | 客户端只有玩家附近的区块，相机远移也画不出来；screenshot/orbit_shots/inspect 在目标超出渲染距离时返回 `warning`（`ViewApi.farWarning`，`ViewHook.renderDistanceBlocks`） |
| 16 | 雾和残影 | 截图期间关闭地形雾：客户端 mixin `client/mixin/FogRendererMixin`（`mcmcp.client.mixins.json`，fabric.mod.json 已登记），D32。残影原因未复现；新增 `view.refresh` / `refresh_view` 把真实方块重发给客户端 |
| 17 | 朝向靠猜 | 新 RPC `world.blockInfo` / 工具 `block_info`（`world/BlockInfo.java`）：属性取值和朝向说明（门、活板门的说明按 1.21.1 源码中的形状和放置代码核对过）。测试 `blockInfoExplainsFacing` |

新增和修改的文件：
- mod：
  - 新增 `arch/FlatFinder.java`、`world/BlockInfo.java`；客户端 `client/ScreenshotAccess.java`、`client/mixin/FogRendererMixin.java`、`client/resources/mcmcp.client.mixins.json`。
  - 修改 `PlanCompiler`、`PlanValidator`、`BuildingPlan`、`Guard`、`Region`、`McMcpConfig`、`Params`、`BuildApi`、`ScriptApi`、`ViewApi`、`WorldApi`、`ArchApi`、`PreviewManager`、`ScriptSandbox`、`BuilderObject`、`ViewHook`、`ScreenshotRenderer`。
  - 新测试类 `UsabilityGameTests`（已在 gametest 的 fabric.mod.json 登记）；`ScriptBlueprintGameTests` 的 const 拒绝用例换成新语法、库和 keepExisting 的用例。
- bridge：`tools/mod.rs`（`read_file`、`load_building`、`load_includes`、`brief`），`arch.rs`（`find_flat_area`、`buildingFile`），`scripts.rs`、`view.rs`（`refresh_view`）、`world.rs`（`block_info`）、`build.rs`（`split`），`resources.rs`（提示词）。
- 文档：`spec.md`（"使用反馈修复"一节）、`USAGE.md`、`building-plan.md`、`script-api.md`、`decisions.md`（D29–D32，D11 标为已取代）、`manual-test.md`（新的实机检查清单）、`docs/AGENTS.md`（给使用 MCP 的 agent 的操作说明，已补充以上能力）。

已知限制和待办：
- **实机验收未做**：需要按 `docs/manual-test.md` 最后一节检查 `runClient` 能启动（新 mixin）、截图无雾、距离警告、`refresh_view`、楼梯实走。
  - 已确认的部分：`./gradlew build` 通过；jar 里 mixin 的目标已被 Loom 重映射为 `method_3211`，生产环境不需要 refmap。
- 残影的根因没有找到（可能就是雾，也可能是客户端区块没刷新），`refresh_view` 只是补救手段。
- 已有建筑若是旧版本编译的（楼梯井只开 2 格），需要 revise 或重新放置才能得到 3 格净空。
- `find_flat_area` 是采样近似：步长约为短边的 1/12（≤8），并保证总采样不超过约 129×129；单块岩石或小坑可能漏掉。去掉了最高/最低各 2% 的采样再判断平整度。
- 分拆的填充是多个任务：中途失败时前面的部分已完成，需要按 `jobIds` 逐个撤销。
- 第 9 条（自动家具偏少）未处理。
