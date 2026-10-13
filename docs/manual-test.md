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

## 阶段 2

- [ ] 自动化：`cd mod && ./gradlew runGametest` 全部通过（放置、撤销/重做、箱子内容恢复、形状更新不出区域、各类错误码、分层格式）。
- [ ] `get_player_state`：位置、朝向、`lookingAt` 与游戏内一致（F3 屏幕核对）。
- [ ] `get_area_summary` 与 `get_layer_slices` 读取一块区域，内容与游戏内一致；分层格式的 x/y/z 方向正确。
- [ ] 让 AI 读取区域后建一个 7 × 5 × 7 空心石砖房（带门洞）；一次 `undo` 后区域与建造前的 `get_layer_slices` 完全一致。选一个旁边有树或水的位置再测一次。
- [ ] `fill_region` 填充 100 × 20 × 100（`loadChunks: true`），之后立即 `get_world_info`：`mspt` 远低于 50，游戏内无明显卡顿；然后 `undo`。
- [ ] 错误码：白名单外 `1001`；300000 格 `1002`；`minecraft:tnt` 和拼错的方块 `1003`；远处未加载区块不带 `loadChunks` 时 `1004`；`job_status` 不存在的 id `1005`；`place_blocks` 4097 项 `-32602`。
- [ ] 一个大填充进行中调用 `cancel_job`：已放置部分保留，`undo` 能撤销这部分。

## 阶段 3

- [ ] 自动化：`./gradlew runGametest` 全部通过（圆柱尺寸、死循环超时行号、禁止 Java、循环内 const 报错、蓝图旋转/镜像及朝向、格式错误）。
- [ ] Bridge 资源：`resources/list` 含 `mc://world/info`、`mc://history`、`mc://jobs`、`mc://docs/script-api`；模板 `mc://player/{name}`；`prompts/list` 含 `build_assistant`。
- [ ] `run_script` + `sourceFile: "examples/scripts/tower.js"`，先 `dryRun`（约 5500 块，包围盒 15 × 31 × 15），再实际执行。游戏内检查：直径 15、高 30、螺旋楼梯可以一路走到顶层平台、垛口、门洞和窗。
- [ ] `arch-bridge.js` 和 `cottage.js` 同样执行并在游戏内检查（拱形、栏杆、台阶；坡屋顶两侧楼梯朝向正确、门两半齐全）。
- [ ] 死循环脚本：约 5 秒后返回 `1006`，带循环所在行号；服务器不卡顿（期间 `get_world_info` 的 mspt 正常）。
- [ ] 建一个带楼梯、原木、门的小结构，`save_blueprint` 带 `name`，再 `place_blueprint` 旋转 90° 放到别处：位置与楼梯朝向、原木轴向、门都正确。
- [ ] 全部 `undo` 恢复。
