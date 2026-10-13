# MC-MCP

让 AI Agent 通过 MCP 控制 Minecraft。完整计划与分阶段验收标准见 [PLAN.md](PLAN.md)。

## 结构

- `mod/` — Fabric 模组（Minecraft 1.21.1，Java 21，Mojang 映射）。WebSocket + JSON-RPC 服务端。
- `bridge/` — Rust MCP 服务（`rmcp`，stdio）。连接模组，把 MCP tools 转成 JSON-RPC 调用。
- `protocol/spec.md` — 模组 <-> bridge 协议，唯一权威来源。
- `docs/decisions.md` — 设计决策记录；`docs/manual-test.md` — 手动测试清单。

## 约定

- 严格按 PLAN.md 的阶段推进，验收全部通过后再进入下一阶段，阶段结束时 commit。
- 不确定 Minecraft / Fabric API 时查反编译源码（`cd mod && ./gradlew genSources`），不要凭印象。
- 读写世界只能在服务器主线程：在 `RpcDispatcher` 用 `onMainThread` 注册处理器。
- 改协议时同时更新 `protocol/spec.md`、`mod/`、`bridge/`。
- 需要取舍的设计先写进 `docs/decisions.md`。

## 常用命令

```bash
cd mod && ./gradlew build          # 构建模组
cd mod && ./gradlew runClient      # 带模组启动游戏
cd bridge && cargo test            # bridge 单元测试（含模拟 WebSocket 服务）
cd bridge && cargo build --release # 产物 bridge/target/release/mc-mcp-bridge
```

Bridge 环境变量：`MCMCP_TOKEN`（必填，见游戏目录 `config/mcmcp.json`）、`MCMCP_URL`（默认 `ws://127.0.0.1:25580`）、`MCMCP_TIMEOUT_MS`、`MCMCP_CONNECT_TIMEOUT_MS`、`MCMCP_LOG`。
