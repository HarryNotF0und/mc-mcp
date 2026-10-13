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
| `1007` | 建筑方案校验未通过（`data` 为校验结果），见 `arch.validate` |

## 数据约定

- 坐标：整数世界坐标数组 `[x, y, z]`，y 向上；北为 -z，东为 +x。
- 区域：`{"min":[x,y,z],"max":[x,y,z]}`，两端都包含；两个角的顺序任意，模组会归一化。
- 维度：参数 `dimension`，带命名空间的 ID，默认 `minecraft:overworld`。
- 方块：游戏原生方块状态字符串，例如 `minecraft:oak_stairs[facing=north,half=bottom]`；未写的属性取默认值。整个字符串必须是一个方块，多余文本报 `1003`。
- 图案（需要服务器装有 WorldEdit）：`world.fillRegion` 的 `block` 与脚本 `b.*` 的方块参数也可以是 WorldEdit 图案，例如 `50%minecraft:stone,50%minecraft:andesite`。图案逐块求值，每个结果都过黑名单检查；执行中遇到黑名单方块时任务 `failed`。
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
- `warning`：游戏暂停时，提交的任务对象和未结束任务的 `job.status` 会带上提示文字。
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

`gameTime` / `dayTime` 取自主世界，单位为 tick。`difficulty` 为 `peaceful` / `easy` / `normal` / `hard`。`mspt` 为最近 100 tick 的平均 tick 耗时（毫秒），≥ 50 表示服务器卡顿。`paused` 为 `true` 表示单人游戏已暂停（暂停菜单打开或窗口失焦），此时任务不推进。

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

### `build.runScript`

在独立的脚本线程上用 Rhino 执行 JavaScript（API 见 `docs/script-api.md`），完成后回到主线程把结果作为一个写任务提交。

- params：`{"source": string, "origin": [x,y,z], "dryRun"?: false, "dimension"?, "loadChunks"?}`
- 脚本内坐标相对于 `origin`。`b.get` 读取世界时，每个区块第一次读取会在主线程复制该区块的方块调色板。
- 限制：`scriptTimeoutMs`（默认 5000）、最多 `maxVolumePerOp` 个方块、`ClassShutter` 拒绝所有 Java 类、不提供 LiveConnect；循环体内的 `const` 在执行前被拒绝（见 decisions D11）。
- result：
  - `dryRun = true`：`{"dryRun": true, "blocks": n, "bounds": {...}, "counts": [{"block","count"}...], "otherStates"?, "logs": [...], "scriptMs": n}`（`counts` 按方块状态统计，前 20 种）。
  - 否则：任务对象，另带 `"script": {同上统计}`。
- 错误：`1006`，`data` 为 `{"line"?: n, "message": string, "logs"?: [...]}`；结果超出白名单/体积/黑名单时为 `1001` / `1002` / `1003`；脚本未产生任何方块时 `-32602`。

### `build.placeBlueprint`

- params：`{"blueprint"?: 分层格式 | "name"?: string | "schematic"?: string, "origin"?: [x,y,z], "rotation"?: 0|90|180|270, "mirror"?: "none"|"x"|"z", "skipAir"?: false, "dryRun"?: false, "dimension"?, "loadChunks"?}`
  - `blueprint`、`name`（`build.saveRegionAsBlueprint` 保存的名字）、`schematic`（WorldEdit 原理图目录中的文件名，可省略扩展名；需要 WorldEdit）三选一。
  - `origin` 默认取蓝图自带的 `origin`；放置后变换后包围盒的最小角落在 `origin`。
  - 先镜像再旋转。`rotation` 为俯视顺时针（90 度时北变东）：`(x, z) -> (sz-1-z, x)`；180：`(sx-1-x, sz-1-z)`；270：`(z, sx-1-x)`。`mirror: "x"` 翻转 x（东西互换），`"z"` 翻转 z（南北互换）。
  - 方块状态用游戏的 `BlockState.mirror/rotate` 同步变换（`facing`、`axis`、`rotation`、铁轨形状等）。
  - `skipAir = true` 时不放置蓝图中的空气。
- result：`dryRun` 时为统计信息（同 `runScript` 的统计，无 `logs`），否则为任务对象。
- 错误：`-32602`（格式错误：调色板键不是单字符、行长度不一致、未知字符、`size` 不符、名字不存在等）、`1003`、`1001`、`1002`、`1004`。

### `build.saveRegionAsBlueprint`

- params：`{"region": {...}, "name"?: string, "format"?: "json" | "schem", "dimension"?, "loadChunks"?}`；体积上限 `maxVolumePerOp`。
- `format: "schem"`（需要 WorldEdit 和 `name`）：写出 Sponge v3 原理图到 WorldEdit 原理图目录，result 为 `{"schematic": 文件名, "path", "size"}`。
- 无 `name`：result 为分层格式（`origin` 为区域最小角）。
- 有 `name`（`[a-z0-9_-]{1,64}`）：保存到 `<游戏目录>/mcmcp/blueprints/<name>.json`，result 为 `{"name", "path", "origin", "size", "paletteSize"}`。
- 方块实体 NBT 不导出。

### `build.preview`

只向该维度内所有玩家发送方块更新包（`ClientboundSectionBlocksUpdatePacket`，每个区块 section 一个），世界本身不变。客户端重新加载区块后看到的是原始世界。

- params：与 `build.runScript`（有 `source` 时）或 `build.placeBlueprint`（否则）相同，不含 `dryRun`。
- 预览前执行与真实写入相同的检查（`1001` / `1002` / `1003` / `1004`）。
- 最多同时保留 5 个预览，超出时最旧的自动丢弃（向客户端恢复原方块）。
- 之后加入的玩家看不到预览。
- result：`{"previewId": n, "players": n, ...统计信息}`（统计同 `dryRun`）。

### `build.confirmPreview`

- params：`{"previewId": n}`
- 把预览的方块作为一个写任务真正放置（一个 ChangeSet）。
- result：任务对象。错误：`-32602`（预览不存在）。

### `build.discardPreview`

- params：`{"previewId": n}`
- 向客户端重新发送这些位置的真实方块。
- result：`{"discarded": true}`。错误：`-32602`。

### `view.screenshot`

仅当游戏客户端与服务器在同一进程（单人游戏 / 开放局域网的主机）时可用，否则返回 `-32603`。在渲染线程把世界（不含 HUD、手、方块选框）渲染到主帧缓冲后截取。

- params：`{"pos"?: [x,y,z], "lookAt"?: [x,y,z], "yaw"?: deg, "pitch"?: deg, "width"?: 854, "height"?: 480, "fov"?: 70, "daylight"?: true}`
  - `pos` 为相机（眼睛）位置，可为小数；省略时取第一个玩家的眼睛位置，且 `yaw`/`pitch` 默认取该玩家的朝向。
  - `yaw`：0 朝南（+z），90 朝西，180 朝北，270 朝东；`pitch` 正值向下。`lookAt` 优先于 `yaw`/`pitch`。
  - 分辨率最大 1920 × 1080，`fov` 30–110。
  - `daylight`：渲染时把客户端的时间临时设为正午（只影响这次渲染，服务器时间不变）。
- 只绘制客户端已加载的区块（玩家渲染距离内）。预览的假方块可见。
- result：`{"width", "height", "shots": [{"label": "screenshot", "pos", "yaw", "pitch", "png": base64}]}`

### `view.orbitShots`

- params：`{"region": {...}, "count"?: 4, "pitch"?: 30, "distance"?, "width"?, "height"?, "fov"?, "daylight"?}`；`count` 1–8。
- 相机围绕区域中心等角度分布、俯角 `pitch`，默认距离使整个包围盒落在视野内。`count = 4` 时依次为 `from north` / `from east` / `from south` / `from west`。
- result：同 `view.screenshot`，`shots` 有 `count` 项。

### `build.listBlueprints`

- params：`{}`
- result：`{"blueprints": [名字...], "schematics"?: [文件名...], "worldEdit": bool}`（`schematics` 仅在装有 WorldEdit 时出现）。

`world.getInfo` 在装有 WorldEdit 时额外返回 `"worldEdit": 版本号`。

## 具身模式（阶段 5）：`agent.*`

假玩家（`AgentPlayer`，带假网络连接的 `ServerPlayer`）像普通玩家一样加入游戏：可见、受伤、拾取物品、背包按玩家数据保存。所有方法都接受 `agent`（名字，默认 `"Agent"`）。耗时动作作为**并行任务**运行（与建造队列并行，不占方块预算），返回任务对象，可用 `job.status` / `job.cancel`，结束时推送 `job.finished`；每个假玩家同一时间只能有一个任务（否则 `-32602`）。任务对象额外带 `agent`、`agentPos` 和 `result`（动作结果，见各方法）。

失败原因写在任务的 `error` 中，例如：`no path to ...`、`could not reach ...: stuck ...`、`agent died at ... (原因); agent_spawn brings it back`、`agent left the game`、`timed out after N s`。

假玩家不受 `allowedRegions` 限制（它和真实玩家一样只能做生存模式能做的事），但放置仍检查方块黑名单。

| 方法 | params | 说明 |
|---|---|---|
| `agent.spawn` | `{agent?, pos?: [x,y,z], dimension?, gameMode?: "survival"}` | 生成；已存在则移动到 `pos`；死亡状态则复活。默认出现在第一个真实玩家旁边。返回状态。名字 3–16 位字母数字下划线，不能与在线真实玩家重名。 |
| `agent.despawn` | `{agent?}` | 离开游戏（背包保存）。 |
| `agent.getState` | `{agent?}` | `{agent, dimension, pos, blockPos, dead, health, food, gameMode, mainHand, inventory: [{item,count}], equipment: {slot: item}, busyWithJob?}` |
| `agent.goto` | `{agent?, pos, range?: 1, dig?: false}` | A* 寻路：行走、跳上 1 格、下落最多 4 格；`dig` 时可挖穿方块。任务 5 分钟超时。`result.reached`、`result.blocksDug`。 |
| `agent.mineBlock` | `{agent?, pos, dig?: true}` | 走到触及范围（4.5 格）内，用背包中最合适的工具按原版耗时挖掘。`result: {block, tool, ticks, warning?}`（无合适工具不掉落时给出警告）。 |
| `agent.collectItems` | `{agent?, radius?: 8}` | 走到附近掉落物处拾取。`result.collected: {item: count}`。 |
| `agent.placeBlock` | `{agent?, pos, block}` | 消耗背包中对应物品放置；需要可替换的目标、相邻有方块可依附、方块能存活。放置精确的方块状态。 |
| `agent.craft` | `{agent?, item, count?: 1}` | 同步。按配方表合成直到产出 ≥ `count`；超过 2×2 的配方需要 4.5 格内有工作台。结果 `{item, crafted, have, warning?, inventory}`；缺料时 `-32602`，`data.inventory` 为当前背包。容器类返还物（桶）不返还。 |
| `agent.equip` | `{agent?, item, slot?: "mainhand"}` | 同步。`slot`：`mainhand` / `offhand` / `head` / `chest` / `legs` / `feet`。 |
| `agent.gather` | `{agent?, item, count?: 1, radius?: 24}` | 反复：找最近的、暴露在外、按战利品表（以背包最佳工具）会掉落该物品且当前工具能采集的方块 → 挖掘 → 拾取，直到背包中有 `count` 个。任务 20 分钟超时。`result: {item, have, gained, blocksMined}`（运行中也实时更新）。 |
| `agent.buildBlueprint` | 同 `build.placeBlueprint`（`blueprint` / `name` / `schematic`、`origin`、`rotation`、`mirror`），加 `agent?` | 自下而上逐块放置，挡路的方块先挖掉，蓝图中的空气跳过。开始前检查材料：缺料时 `-32602`，`data.missing: [{item, missing}]`。`result: {placed, alreadyThere, errors?}`。 |

寻路细节：只在已加载区块内搜索，最多 30000 个节点、水平 96 格。不进入水、岩浆、火、仙人掌、甜浆果丛、蜘蛛网、细雪等。挖掘时不挖与流体相邻的方块、会下落的方块（沙、砂砾）及其下方、带方块实体的方块、基岩，以及用最佳工具需要超过 5 秒才能挖掉的方块。

## 建筑规划（v2 阶段 1）

建筑方案格式（`mcmcp.building/1`）的完整说明见 `docs/building-plan.md`。

### `site.analyze`

在主线程上逐列扫描（有界：半径 4–48）。

- params：`{"center"?: [x,y,z]（默认第一个真实玩家）, "radius"?: 24, "footprint"?: [width, depth]（默认 [9, 7]）, "margin"?: 2, "facing"?: 方向, "top"?: 3, "dimension"?, "loadChunks"?}`
- 每列：地面高度（自上而下跳过树木、植物、雪，遇到天然地面或人造方块为止，最多 40 格）、表面类型（ground / water / lava / tree / built / void）、是否道路（土径、圆石、石砖、平滑石头、磨制安山岩）。
- result：

```json
{
  "area": {"min": [...], "max": [...]},
  "summary": {"columns": 2401, "groundMin": 61, "groundMax": 71, "meanSlope": 0.98, "maxStep": 5, "buildableFraction": 0.25,
              "surfaces": {"ground": 1014, "water": 330, "lava": 0, "tree": 1880, "built": 15, "road": 0}},
  "map": {"step": 2, "originXZ": [x, z], "legend": "...", "rows": [".TT~#=..."], "heights": [[64, 65, ...]]},
  "candidates": [
    {"origin": [14, 65, 8], "facing": "east", "footprint": [11, 9], "worldSizeXZ": [9, 11], "score": 118.2,
     "stats": {"floorY": 65, "groundMin": 61, "groundMax": 67, "cut": 16, "fill": 34, "maxFoundationDepth": 4,
               "treeColumns": 31, "waterColumns"?: n, "builtColumns"?: n, "lavaColumns"?: n,
               "entranceAccessCost": {"east": 4.1, "west": 9.7}, "distanceFromCenter": 22}}
  ]
}
```

- 候选位置：两种朝向（宽沿 x 或沿 z）在区域内滑动；地板高度取足迹内地面高度中位数；`cut` = 高出地板的方块数，`fill` = 地板下需要填的方块数；评分 = cut + 0.6·fill + 40·水 + 1000·岩浆 + 100·人造 + 2·树 + 0.3·距中心 + 2·入口可达代价（+5 若未采用请求的朝向）。不在允许区域内的位置被跳过。返回互不重叠的前 `top` 个。
- `facing`：在该朝向可选的两侧中，选择墙外 `margin` 范围内与地板高差最小、无水/人造、靠近道路的一侧；若请求的 `facing` 不比最优差 2 以上则采用请求的。
- `origin` / `facing` 可直接用作建筑方案的 `origin` / `facing`。

### `arch.validate`

- params：`{"building": 方案, "dimension"?, "loadChunks"?}`
- 编译（只读世界：地基深度、需要清除的地形）并校验，不放置。
- result：`{"blocks", "bounds", "counts", ..., "building", "facing", "origin", "floors", "terrain": {"foundationMaxDepth"}, "validation": {...}, "components": [{"id", "role", "blocks", "bounds"}]}`
- `validation`：`{"ok": bool, "errors": [问题], "warnings": [问题], "reachable": {"floors": {id: bool}, "rooms": {id: bool}}}`；问题为 `{"code", "message", "components": [...], "positions"?: [世界坐标], "localPositions"?: [方案局部坐标]}`。错误码表见 `docs/building-plan.md`。

### 方案作为放置来源

`build.placeBlueprint` 与 `build.preview` 接受 `"building": 方案`（代替 `blueprint` / `name` / `schematic` / `source`），以及 `"ignoreValidation"?: false`。

- `build.preview`：总是允许，结果中带 `validation`。
- `build.placeBlueprint`：校验有错误时返回 `1007`（`data` 为校验结果），除非 `ignoreValidation: true`；`dryRun` 返回统计和校验结果。
- 放置的方块包括地基、平台、清除的地形（空气），作为一个 ChangeSet 可撤销。
