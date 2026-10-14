# MC-MCP

让 AI Agent 通过 MCP 控制 Minecraft。完整计划与分阶段验收标准见 [PLAN.md](PLAN.md)。

## 结构

- `mod/` — Fabric 模组（Minecraft 1.21.1，Java 21，Mojang 映射）。WebSocket + JSON-RPC 服务端。
  主要包：`net/`（WebSocket、分发）、`api/`（各方法）、`world/`、`history/`、`jobs/`、`script/`（Rhino）、`blueprint/`、`preview/`、`view/`（截图钩子，实现在 `src/client`）、`agent/`（假玩家）、`worldedit/`（可选集成）。
- `bridge/` — Rust MCP 服务（`rmcp`，stdio）。连接模组，把 MCP tools 转成 JSON-RPC 调用。
- `protocol/spec.md` — 模组 <-> bridge 协议，唯一权威来源。
- `docs/decisions.md` — 设计决策记录；`docs/manual-test.md` — 手动测试清单。

## 约定

- 严格按 PLAN.md 的阶段推进，验收全部通过后再进入下一阶段，阶段结束时 commit。
- 不确定 Minecraft / Fabric API 时查反编译源码（`cd mod && ./gradlew genSources`），不要凭印象。
- 读写世界只能在服务器主线程：在 `RpcDispatcher` 用 `onMainThread` 注册处理器。
- 修改世界一律通过 `jobs/` 任务队列和 `BlockSnapshot.SET_FLAGS`，并记录到 ChangeSet；不要直接 `setBlock`（见 decisions D6）。
- 改协议时同时更新 `protocol/spec.md`、`mod/`、`bridge/`。
- 需要取舍的设计先写进 `docs/decisions.md`。
- 用户使用说明在 `docs/USAGE.md`；新增或修改工具时同步更新，同时更新给使用工具的 agent 的说明 `docs/AGENTS.md`。
- 每做完一部分工作，把细节写进 `HANDOFF.md`（改了什么、为什么、文件、实际测试结果、未验证的部分、已知问题），再 commit。Claude 和 Codex 轮流开发，`HANDOFF.md` 是共享状态。

## 常用命令

```bash
cd mod && ./gradlew build          # 构建模组
cd mod && ./gradlew runClient      # 带模组启动游戏
cd mod && ./gradlew runGametest    # 运行 Fabric GameTest（mod/src/gametest）
cd bridge && cargo test            # bridge 单元测试（含模拟 WebSocket 服务）
cd bridge && cargo build --release # 产物 bridge/target/release/mc-mcp-bridge
```

Bridge 环境变量：`MCMCP_TOKEN`（必填，见游戏目录 `config/mcmcp.json`）、`MCMCP_URL`（默认 `ws://127.0.0.1:25580`）、`MCMCP_TIMEOUT_MS`、`MCMCP_CONNECT_TIMEOUT_MS`、`MCMCP_JOB_WAIT_MS`（写工具等待任务完成的时长）、`MCMCP_LOG`。
