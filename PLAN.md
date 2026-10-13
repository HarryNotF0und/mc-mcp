# MC-MCP 项目计划

让 AI Agent 通过 MCP 协议控制 Minecraft，完成建造和干活任务。

---

## 0. 给 Claude Code 的工作约定

- 严格按阶段推进。每个阶段的验收标准全部满足后再进入下一阶段，并在阶段结束时 commit。
- 不确定 Minecraft / Fabric API 的方法名或签名时，查 Fabric 文档、Fabric API 源码或反编译后的游戏源码（`./gradlew genSources`），不要凭印象编造。
- 所有修改世界的操作必须在服务器主线程执行（`server.execute(...)` 或 tick 回调），网络线程只负责收发和排队。
- 协议变更必须同时更新 `protocol/spec.md`、模组端和 bridge 端，三者保持一致。
- 遇到需要做取舍的设计问题，先写进 `docs/decisions.md` 并说明理由，再实现。

---

## 1. 目标

**第一优先级（阶段 1–4）：建筑向的"上帝模式"。** AI 能读取地形、批量放置方块、用脚本生成建筑、预览、撤销。

**第二优先级（阶段 5）：生存向的"具身模式"。** AI 控制一个假玩家走路、挖矿、收集、合成。

**非目标（暂不做）：** 多人权限系统、NeoForge 移植、在游戏内显示聊天界面、训练模型。

---

## 2. 技术栈

| 部分 | 选型 |
|---|---|
| 模组加载器 | Fabric，Minecraft 1.21.1，Java 21 |
| 构建 | Gradle + Fabric Loom，使用 Mojang 官方映射（`loom.officialMojangMappings()`） |
| 模组内 WebSocket | `org.java-websocket:Java-WebSocket`，通过 `include` 打进 jar |
| JSON | Gson（游戏自带） |
| 脚本沙箱（阶段 3） | Mozilla Rhino，用 instruction observer 限制执行时间 |
| MCP Bridge | Rust：`rmcp`（MCP SDK）、`tokio`、`tokio-tungstenite`、`serde`/`serde_json`、`tracing` |
| Bridge 传输 | stdio（供 Claude Code / Claude Desktop 直接接入） |

---

## 3. 仓库结构

```
mc-mcp/
├── CLAUDE.md              # 项目约定的简版，指向本文件
├── PLAN.md                # 本文件
├── docs/
│   └── decisions.md       # 设计决策记录
├── protocol/
│   └── spec.md            # 模组 <-> bridge 协议规范（唯一权威来源）
├── mod/                   # Fabric 模组（Gradle 工程）
│   └── src/main/java/dev/haochen/mcmcp/
│       ├── McMcpMod.java          # 入口
│       ├── net/                   # WebSocket 服务、JSON-RPC 分发
│       ├── api/                   # 每个 method 的处理器
│       ├── world/                 # 读取、放置、区域、调色板
│       ├── history/               # ChangeSet、undo
│       ├── jobs/                  # 异步任务队列
│       ├── script/                # 阶段 3：Rhino 沙箱
│       ├── preview/               # 阶段 4：假方块预览
│       └── agent/                 # 阶段 5：假玩家
├── bridge/                # Rust MCP 服务（Cargo 工程）
│   └── src/
│       ├── main.rs
│       ├── mc_client.rs           # WebSocket 客户端、请求/响应匹配、重连
│       ├── tools/                 # 每个 MCP tool 一个模块
│       └── resources.rs
└── examples/
    ├── scripts/           # 示例建造脚本
    └── blueprints/        # 示例蓝图
```

包名 `dev.haochen.mcmcp` 可以按需修改。

---

## 4. 模组 <-> Bridge 协议

- 传输：WebSocket，默认只监听 `127.0.0.1:25580`，端口可在配置文件中修改。
- 认证：模组首次启动时在 `config/mcmcp.json` 生成随机 token；bridge 连接后的第一条消息必须是 `auth`，否则断开。
- 格式：JSON-RPC 2.0。
  - 请求：`{"jsonrpc":"2.0","id":1,"method":"world.fillRegion","params":{...}}`
  - 响应：`result` 或 `error`
  - 服务端推送（无 id）：`job.progress`、`job.finished`、`log`
- 方法命名：`<领域>.<动作>`，例如 `world.getBlock`、`build.runScript`、`history.undo`、`job.status`。
- 错误码：
  - `-32602` 参数错误
  - `1001` 区域超出白名单
  - `1002` 体积超出上限
  - `1003` 未知方块 ID 或方块状态
  - `1004` 区块未加载
  - `1005` 任务不存在
  - `1006` 脚本执行错误（附带行号和消息）

完整的 method 列表与参数 schema 写在 `protocol/spec.md`，随阶段增量补充。

---

## 5. 数据约定

- **方块**：带命名空间的 ID，方块状态用游戏原生字符串格式，例如 `minecraft:oak_stairs[facing=north,half=bottom]`。
- **坐标**：整数世界坐标 `[x, y, z]`。区域用 `{"min":[x,y,z],"max":[x,y,z]}`，两端都包含。
- **维度**：参数 `dimension`，默认 `minecraft:overworld`。
- **调色板 + 分层格式**（感知输出与蓝图输入共用同一格式，降低 AI 学习成本）：

```json
{
  "origin": [100, 64, 200],
  "size": [5, 3, 5],
  "palette": { ".": "minecraft:air", "S": "minecraft:stone_bricks", "P": "minecraft:oak_planks" },
  "layers": [
    ["SSSSS", "S...S", "S...S", "S...S", "SSSSS"],
    ["SSSSS", "S...S", "S...S", "S...S", "SSSSS"],
    ["PPPPP", "PPPPP", "PPPPP", "PPPPP", "PPPPP"]
  ]
}
```

`layers[i]` 对应 `y = origin.y + i`；每一层中，第 `j` 行对应 `z = origin.z + j`，第 `k` 个字符对应 `x = origin.x + k`。调色板字符由模组自动分配，优先使用有含义的字母。

---

## 6. 分阶段任务

### 阶段 1：骨架与连通

**任务**
1. 用 Fabric 模板初始化 `mod/`，确认 `./gradlew runClient` 能进游戏。
2. 在服务器启动事件中开启 WebSocket 服务，服务器关闭时关闭；实现 token 认证与 JSON-RPC 分发器。
3. 实现 `system.ping` 与 `world.getInfo`（版本、维度列表、在线玩家、游戏时间、难度）。
4. 初始化 `bridge/`，实现 WebSocket 客户端，按 id 匹配请求与响应，支持超时和断线重连。
5. 用 `rmcp` 暴露 MCP tools：`ping`、`get_world_info`。token 和地址从环境变量 `MCMCP_TOKEN`、`MCMCP_URL` 读取。
6. 编写 `CLAUDE.md` 与 `protocol/spec.md` 初版。

**验收**
- 在单人世界中执行 `claude mcp add mc -- ./bridge/target/release/mc-mcp-bridge` 后，Claude Code 能成功调用 `ping` 和 `get_world_info`。
- 游戏未启动时调用工具，返回清晰的错误信息，而不是挂起。

### 阶段 2：感知、基础建造与撤销

**任务**
1. 感知：
   - `world.getPlayerState`：位置、朝向、维度、手持物品、游戏模式
   - `world.getBlock`
   - `world.getAreaSummary`：方块计数（前 N 种）、地表高度图（降采样）、水和岩浆的位置、附近实体
   - `world.getLayerSlices`：输出第 5 节的分层格式，单次体积上限 32 × 32 × 32
2. 建造：
   - `world.placeBlocks`：批量，单次最多 4096 个
   - `world.fillRegion`：支持 `hollow` 和 `outline` 模式
   - `world.clearRegion`
3. 任务系统（`jobs/`）：放置操作统一进入队列，按每 tick N 个方块分批执行（N 可配置，默认 2000）；立即返回 `job_id`，通过推送报告进度。实现 `job.status` 与 `job.cancel`。
4. 历史记录（`history/`）：每次修改生成一个 ChangeSet，记录修改前的方块状态（含方块实体 NBT）。实现 `history.list`、`history.undo`、`history.redo`，最多保留 50 个。
5. 安全：配置文件中的区域白名单（默认以世界出生点为中心 256 格）、单次操作体积上限（默认 262144 个方块）、方块黑名单（默认禁止 `minecraft:command_block`、`minecraft:tnt` 等）。
6. Bridge 端暴露对应的 MCP tools。所有 tool 的 description 要写清楚坐标约定和格式，并给出简短示例；返回内容保持精简。

**验收**
- AI 能先读取某块区域，再在其中建出一个 7 × 5 × 7 的空心石砖房（带门洞），然后一次 undo 将其完全复原。
- 一次填充 100 × 20 × 100 不会让服务器 TPS 明显下降。
- 越界和超量的请求返回对应错误码。

### 阶段 3：建造脚本与蓝图

**任务**
1. 集成 Rhino，提供一个受限的全局对象 `b`：
   - `b.set(x, y, z, block)`、`b.get(x, y, z)`
   - `b.fill(x1, y1, z1, x2, y2, z2, block, mode)`
   - `b.line(...)`、`b.cylinder(...)`、`b.sphere(...)`
   - `b.origin`：脚本内坐标相对于传入的 `origin`
   - `b.log(msg)`：输出返回给 AI
2. 沙箱限制：禁止访问 Java 类（`ClassShutter` 拒绝所有类）、执行时间上限（默认 5 秒）、方块数上限。脚本只生成方块列表，不直接修改世界；执行完毕后交给阶段 2 的队列，产生一个 ChangeSet。
3. 新增 `build.runScript`（参数：`source`、`origin`、`dryRun`）。`dryRun = true` 时只返回统计信息（方块数、包围盒、各方块计数），不写入世界。
4. 新增 `build.placeBlueprint`：接收第 5 节的分层格式，支持旋转（0/90/180/270）和镜像，旋转时同步转换方块状态中的 `facing` 等属性。
5. 新增 `build.saveRegionAsBlueprint`：将世界中的区域导出为蓝图 JSON。
6. 在 `examples/scripts/` 中放 3 个示例：塔、拱桥、带坡屋顶的小屋。

**验收**
- AI 用一次 `build.runScript` 建出一座直径 15、高 30 的圆塔，带螺旋楼梯。
- 死循环脚本会在时限内被终止，并返回包含行号的错误。
- 蓝图导出后，旋转 90° 再放置，结果正确（楼梯朝向一并正确）。

### 阶段 4：预览与视觉反馈

**任务**
1. 预览：`build.preview` 接收与 `runScript` / `placeBlueprint` 相同的输入，只向玩家客户端发送方块更新包，显示"假方块"，世界本身不变。实现 `build.confirmPreview`（真正写入）与 `build.discardPreview`（向客户端恢复原方块）。
2. 截图（仅客户端，单人世界或客户端装模组时可用）：`view.screenshot`，参数包括相机位置、朝向、分辨率；在渲染线程截取帧缓冲，返回 PNG。Bridge 端以 MCP image content 返回给 AI。截图过程中临时隐藏 HUD。
3. 新增 `view.orbitShots`：围绕一个包围盒从 4 个方向各拍一张，方便 AI 检查建筑效果。

**验收**
- 预览期间，其他客户端重新加载区块后看到的是原始世界；确认后才真正改变。
- 多模态模型能拿到截图，并据此修正一处明显的建造错误。

### 阶段 5：具身模式（生存）

**任务**
1. 假玩家：基于 `ServerPlayer` 加一个假的网络连接，实现 `agent.spawn`、`agent.despawn`、`agent.getState`（血量、饥饿、背包、位置）。
2. 移动：`agent.goto`，先实现一个简单的 A*（支持行走、跳跃、下落，限制搜索范围），以异步任务形式运行。如需更强的寻路能力，再评估集成 Baritone API，并把决策写入 `docs/decisions.md`。
3. 动作：`agent.mineBlock`（按工具和硬度计算挖掘耗时）、`agent.collectItems`、`agent.placeBlock`（消耗背包物品）、`agent.craft`（从配方表查找配方并校验材料）、`agent.equip`。
4. 组合任务：`agent.gather`（"收集 N 个某物品"，自动搜索附近的对应方块），以及 `agent.buildBlueprint`（按蓝图消耗背包材料逐块建造，缺料时返回材料清单）。

**验收**
- AI 让假玩家砍树、合成工作台和木镐、挖 10 个圆石，全程不使用上帝模式工具。
- 假玩家死亡或卡住时，任务以明确的原因失败，而不是一直挂着。

---

## 7. MCP 层设计要点

- **Tools**：每个 tool 的 description 都要说明用途、坐标约定、常见错误和一个最小示例。工具数量控制在 AI 能一眼看懂的范围，相近的功能用参数区分，而不是拆成多个工具。
- **Resources**：
  - `mc://world/info`
  - `mc://player/{name}`
  - `mc://history`
  - `mc://jobs`
  - `mc://docs/script-api`：脚本 API 文档，AI 写脚本前可以读取
- **Prompts**：提供一个 `build_assistant` prompt，内容是推荐工作流：先感知，再用 dryRun 或 preview，确认后写入，出错时 undo。
- **长任务**：工具调用默认等待任务完成（设超时）；超时后返回 `job_id`，让 AI 用 `job_status` 轮询。

---

## 8. 测试

- **模组**：用 Fabric GameTest 测试放置、撤销、蓝图旋转、区域白名单。
- **Bridge**：用模拟的 WebSocket 服务做单元测试，覆盖请求匹配、超时、重连、错误映射。
- **端到端**：`docs/manual-test.md` 中维护一份手动测试清单，每个阶段验收时逐项过一遍。

---

## 9. 配置文件示例（`config/mcmcp.json`）

```json
{
  "port": 25580,
  "token": "自动生成",
  "allowedRegions": [{ "dimension": "minecraft:overworld", "min": [-256, -64, -256], "max": [256, 320, 256] }],
  "maxVolumePerOp": 262144,
  "blocksPerTick": 2000,
  "blockBlacklist": ["minecraft:command_block", "minecraft:tnt"],
  "scriptTimeoutMs": 5000,
  "historySize": 50
}
```
