# 手动测试清单

每个阶段验收时逐项过一遍。

## 准备

1. `cd mod && ./gradlew runClient`，进入一个单人世界（或 `./gradlew runServer`，需先在 `mod/run/eula.txt` 写入 `eula=true`）。
2. 日志中出现 `MC-MCP listening on ws://127.0.0.1:25580`。
3. 从 `mod/run/config/mcmcp.json` 复制 `token`。
4. `cd bridge && cargo build --release`
5. 注册到 Claude Code（在仓库根目录）：

   ```bash
   claude mcp add mc -e MCMCP_TOKEN=<token> -- ./bridge/target/release/mc-mcp-bridge
   ```

## 阶段 1

- [ ] `ping` 返回 `{"pong":true,...}`。
- [ ] `get_world_info` 返回版本 `1.21.1`、三个维度、在线玩家（单人世界中含自己及坐标）、`gameTime`、`difficulty`。
- [ ] 游戏未启动时调用 `ping`：几秒内返回 "cannot connect to Minecraft ... Is the game running ..."，不挂起。
- [ ] 错误 token：返回 "Minecraft rejected the token ..."。
- [ ] 退出世界再进入（不重启 bridge）：退出期间调用报连接错误，重新进入后调用自动恢复。
