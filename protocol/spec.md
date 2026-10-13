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

服务端推送（无 `id`）：`{"jsonrpc":"2.0","method":"log","params":{...}}`。计划中的推送：`job.progress`、`job.finished`、`log`。

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

- 坐标：整数世界坐标数组 `[x, y, z]`。
- 维度：带命名空间的 ID，例如 `minecraft:overworld`。
- 其余约定（方块状态、区域、分层格式）见 `PLAN.md` 第 5 节，随阶段 2 补充到本文件。

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

`gameTime` / `dayTime` 取自主世界，单位为 tick。`difficulty` 为 `peaceful` / `easy` / `normal` / `hard`。
