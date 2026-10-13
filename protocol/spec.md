# 模组 <-> Bridge 协议规范

本文件是协议的唯一权威来源。修改协议时必须同时更新本文件、模组端（`mod/`）和 bridge 端（`bridge/`）。

## 传输与认证

- WebSocket，模组默认监听 `ws://127.0.0.1:25580`（`config/mcmcp.json` 中的 `host` / `port`）。
- 模组随服务器启动（`SERVER_STARTED`）开启监听，随服务器关闭（`SERVER_STOPPING`）停止。单人游戏中即"进入世界时开启，退出世界时关闭"。
- 连接后第一条消息必须是 `auth` 请求，否则模组返回 `-32001` 错误并关闭连接（close code 1008）。
- 消息格式为 JSON-RPC 2.0，每条 WebSocket 文本帧一条消息，不使用批量请求。

## 消息

请求：

```json
{"jsonrpc":"2.0","id":1,"method":"world.getInfo","params":{}}
```

成功响应：`{"jsonrpc":"2.0","id":1,"result":{...}}`

错误响应：`{"jsonrpc":"2.0","id":1,"error":{"code":1001,"message":"...","data":{...}}}`（`data` 可选）

服务端推送（无 `id`）：`{"jsonrpc":"2.0","method":"job.finished","params":{...}}`，发给所有已认证的连接：

- `job.progress`：运行中的任务约每秒一次，params 为任务对象（见下文"任务对象"）。
- `job.finished`：任务结束（`done` / `failed` / `cancelled`）时一次，params 为任务对象。
- `log`：保留，阶段 3 起用于脚本输出。

`params` 省略时视为 `{}`。

## 错误码

| code | 含义 |
|---|---|
| `-32700` | JSON 解析失败 |
| `-32600` | 请求格式错误（例如缺少 `method`） |
| `-32601` | 未知方法 |
| `-32602` | 参数错误 |
| `-32603` | 模组内部错误 |
| `-32001` | 未认证或 token 错误 |
| `1001` | 区域超出白名单 |
| `1002` | 体积超出上限 |
| `1003` | 未知方块 ID 或方块状态 |
| `1004` | 区块未加载 |
| `1005` | 任务不存在 |
| `1006` | 脚本执行错误（`data` 附带行号和消息） |

## 数据约定

- 坐标：整数世界坐标数组 `[x, y, z]`，y 向上；北为 -z，东为 +x。
- 区域：`{"min":[x,y,z],"max":[x,y,z]}`，两端都包含；两个角的顺序任意，模组会归一化。
- 维度：参数 `dimension`，带命名空间的 ID，默认 `minecraft:overworld`。
- 方块：游戏原生方块状态字符串，例如 `minecraft:oak_stairs[facing=north,half=bottom]`；未写的属性取默认值。
- `loadChunks`（读写方法通用，默认 `false`）：为 `false` 时区域内有未加载区块则返回 `1004`；为 `true` 时同步加载（必要时生成）这些区块。
- 分层格式（感知输出与阶段 3 蓝图输入共用）：

```json
{
  "origin": [100, 64, 200],
  "size": [5, 3, 5],
  "palette": { ".": "minecraft:air", "S": "minecraft:stone_bricks" },
  "layers": [["SSSSS", "S...S", "S...S", "S...S", "SSSSS"], ...]
}
```

`layers[i]` 对应 `y = origin.y + i`；每层第 `j` 个字符串对应 `z = origin.z + j`；其中第 `k` 个字符对应 `x = origin.x + k`。空气固定为 `.`；其他方块状态按出现次数从多到少分配字符，优先用名称各单词首字母（大写再小写），然后是名称中的其他字母、其余字母、数字、符号。

## 安全限制（`config/mcmcp.json`）

- `allowedRegions`：写操作的整个区域必须落在同一维度的某个允许区域内，否则 `1001`。列表为空则禁止一切写操作。读操作不受此限制。
- `maxVolumePerOp`（默认 262144）：单次写操作或 `getAreaSummary` 的体积上限，超出返回 `1002`。
- `blockBlacklist`：写入这些方块返回 `1003`。
- 所有区域的 y 必须在维度建筑高度内，否则 `-32602`。

## 写入语义

- 所有写操作进入任务队列，按提交顺序逐个执行，每 tick 最多处理 `blocksPerTick`（默认 2000）个方块。
- 写入不触发方块更新（沙子不下落、红石不响应、水不流入），也不改变区域外的任何方块。
- 同一任务内放置的方块之间会互相更新形状（栅栏、墙、玻璃板相连，楼梯拐角），放置的方块也会根据已有邻居计算形状；但区域外的邻居不会改变。
- 被替换的容器内容不掉落。
- 每个写任务生成一个 ChangeSet，记录每个方块修改前后的状态与方块实体 NBT，可撤销。

## 任务对象

```json
{
  "jobId": 3, "kind": "fillRegion", "description": "fill hollow 7x5x7 at 100, 64, 200 with minecraft:stone_bricks",
  "state": "done", "done": 245, "total": 245, "elapsedMs": 12,
  "changed": 170, "changeSetId": 2
}
```

- `state`：`queued` / `running` / `done` / `failed` / `cancelled`。
- `changed`：实际写入的记录条数（与原状态相同的方块会跳过）。仅写任务有此字段。
- `changeSetId`：仅在有修改时出现；撤销/重做任务还会带 `changeSetDescription`。
- `error`：仅 `failed` 时出现。
- 模组保留最近 100 个已结束的任务。

## 方法

### `auth`

连接后的第一条消息。

- params：`{"token": string}`
- result：`{"ok": true}`
- 错误：`-32001`（token 错误，随后断开）

### `system.ping`

在网络线程上直接响应，不等待服务器 tick。

- params：`{}`
- result：

```json
{"pong": true, "modVersion": "0.1.0", "serverTimeMs": 1760000000000}
```

### `world.getInfo`

在服务器主线程执行。

- params：`{}`
- result：

```json
{
  "minecraftVersion": "1.21.1",
  "modVersion": "0.1.0",
  "dedicated": false,
  "dimensions": ["minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"],
  "players": [
    {"name": "Dev", "uuid": "…", "dimension": "minecraft:overworld", "pos": [12, 64, -5]}
  ],
  "gameTime": 123456,
  "dayTime": 6000,
  "difficulty": "normal"
}
```

`gameTime` / `dayTime` 取自主世界，单位为 tick。`difficulty` 为 `peaceful` / `easy` / `normal` / `hard`。`mspt` 为最近 100 tick 的平均 tick 耗时（毫秒），≥ 50 表示服务器卡顿。

### `world.getPlayerState`

- params：`{"name"?: string}`，省略时取第一个在线玩家。
- result：

```json
{
  "name": "Dev", "dimension": "minecraft:overworld",
  "pos": [12.5, 64.0, -4.3], "blockPos": [12, 64, -5],
  "yaw": 90.0, "pitch": 15.5, "facing": "west", "gameMode": "creative",
  "health": 20.0, "food": 20,
  "mainHand": {"item": "minecraft:stone", "count": 64}, "offHand": {"item": "minecraft:air", "count": 0},
  "lookingAt": {"pos": [9, 63, -5], "face": "up", "block": "minecraft:grass_block[snowy=false]"}
}
```

`lookingAt` 为准星指向的方块（64 格内），没有则省略。错误：`-32602`（玩家不在线 / 无人在线）。

### `world.getBlock`

- params：`{"pos": [x,y,z], "dimension"?, "loadChunks"?}`
- result：`{"pos": [x,y,z], "block": "minecraft:chest[facing=north,type=single,waterlogged=false]", "nbt"?: "{Items:[...]}"}`（`nbt` 为 SNBT 字符串，仅方块实体有）

### `world.getAreaSummary`

- params：`{"region": {...}, "top"?: 10, "dimension"?, "loadChunks"?}`；体积上限 `maxVolumePerOp`。
- result：

```json
{
  "region": {...}, "volume": 4096, "air": 2000,
  "blocks": [{"block": "minecraft:stone", "count": 1500}, ...],
  "otherBlocks": 12,
  "heightmap": {"step": 1, "originXZ": [0, 0], "layout": "...", "rows": [[64, 64, 65], ...]},
  "fluids": {"water": {"count": 30, "sources": 20, "sample": [[1,62,3], ...]}, "lava": {"count": 0, "sources": 0}},
  "entities": {"total": 2, "list": [{"type": "minecraft:cow", "pos": [3,64,4]}, {"type": "minecraft:player", "name": "Dev", "pos": [...]}]}
}
```

- `blocks`：前 `top` 种非空气方块（按方块，不区分状态）；其余合计在 `otherBlocks`。
- `heightmap.rows[j][k]`：`x = originXZ[0] + k*step`、`z = originXZ[1] + j*step` 处最高非空气方块的 y，与区域的 y 范围无关；网格最多 32 × 32。
- `fluids.*.sample`：最多 32 个位置（按扫描顺序，自下而上）。
- `entities.list`：最多 50 个；玩家和有自定义名字的实体带 `name`。

### `world.getLayerSlices`

- params：`{"region": {...}, "dimension"?, "loadChunks"?}`；每个轴最多 32 格，超出返回 `1002`。
- result：分层格式（见"数据约定"）。

### `world.placeBlocks`

- params：`{"blocks": [[x,y,z,"block"] | {"pos":[x,y,z],"block":"..."}, ...], "dimension"?, "loadChunks"?}`；1–4096 项。
- 每个位置都要通过白名单检查。同一位置出现多次时按顺序执行。
- result：任务对象（`state` 为 `queued`）。

### `world.fillRegion`

- params：`{"region": {...}, "block": "...", "mode"?: "solid" | "hollow" | "outline", "dimension"?, "loadChunks"?}`
  - `solid`（默认）：全部填充。
  - `hollow`：外壳填充，内部设为空气。
  - `outline`：只填外壳，内部不动。
- 执行顺序：自下而上（y），再 z，再 x。
- result：任务对象。

### `world.clearRegion`

- params：`{"region": {...}, "dimension"?, "loadChunks"?}`，等同于用 `minecraft:air` 实心填充。
- result：任务对象。

### `job.status`

- params：`{"jobId"?: int}`
- result：带 `jobId` 时返回该任务对象，否则返回 `{"jobs": [任务对象, ...]}`（按提交顺序）。错误：`1005`。

### `job.cancel`

- params：`{"jobId": int}`
- 排队中的任务直接取消；运行中的任务停止，已写入的部分作为一个 ChangeSet 记入历史，可撤销。已结束的任务不受影响。
- result：任务对象。错误：`1005`。

### `history.list`

- params：`{}`
- result：`{"undo": [ChangeSet, ...], "redo": [ChangeSet, ...]}`，最近的在前；ChangeSet 为 `{"id", "description", "dimension", "blocks", "bounds": {...}, "createdAt"}`。

### `history.undo` / `history.redo`

- params：`{}`
- 作为任务进入队列；任务**开始执行时**才选取要撤销/重做的 ChangeSet，所以排在某个写任务后面的 undo 撤销的就是那个写任务。
- 撤销按记录的逆序精确恢复修改前的方块状态和 NBT；重做按顺序恢复修改后的状态。新的写操作会清空重做栈。最多保留 `historySize`（默认 50）个 ChangeSet；历史只保存在内存中，服务器关闭后清空。
- 无可撤销/重做内容时任务以 `failed` 结束，`error` 为 `nothing to undo` / `nothing to redo`。
- result：任务对象。
