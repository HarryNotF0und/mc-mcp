# 手动测试清单

每个阶段验收时逐项过一遍。

## 准备

1. `cd mod && ./gradlew runClient`（可加 `--args="--quickPlaySingleplayer <存档名>"` 直接进入存档），进入一个单人世界。建议在游戏中按 F3+P 关闭"失焦暂停"，否则切到其他窗口时任务会停住（或 `./gradlew runServer`，需先在 `mod/run/eula.txt` 写入 `eula=true`）。
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

## 阶段 4（需要客户端：`runClient` 单人游戏）

- [ ] 自动化：`./gradlew runGametest` 全部通过（含预览：确认前世界不变，确认后放置，重复确认报错）。
- [ ] `screenshot` 不带参数：与游戏画面一致，无 HUD、无手、无准星；夜里调用时默认 `daylight` 为白天光照。
- [ ] `preview` 塔脚本：游戏内能看到假方块；此时 `get_layer_slices` 读取同一区域与预览前完全一致（服务器世界未变）；`orbit_shots` 能拍到预览。
- [ ] 第二个客户端（或走远再回来让区块重新加载）看到的是原始地形。
- [ ] `discard_preview` 后游戏内恢复原貌；`confirm_preview` 后真实放置，`undo` 可撤销。
- [ ] 让 AI 建一个有明显错误的建筑（如一侧屋顶楼梯朝向反了），只给它 `orbit_shots`：它应能指出错误并修正。
- [ ] 暂停游戏（Esc）后提交任务：返回带 `warning`；`get_world_info` 的 `paused` 为 `true`；关闭菜单后任务完成。

## WorldEdit 集成（服务器装有 WorldEdit 7.3.x）

- [ ] 自动化：`runGametest` 中 `WorldEditGameTests` 通过（图案填充、图案中的黑名单方块使任务失败、`.schem` 保存后旋转放置）。
- [ ] `get_world_info` 返回 `worldEdit` 版本。
- [ ] `fill_region` 用 `60%minecraft:stone_bricks,25%minecraft:mossy_stone_bricks,15%minecraft:cracked_stone_bricks`：截图中三种砖混杂；`undo` 可撤销。
- [ ] `save_blueprint` 带 `format: "schem"`；在游戏中用 `//schem load <名字>` 和 `//paste` 能读出同样的结构（与 WorldEdit 互通）。
- [ ] 把下载的 `.schem` 放到 `config/worldedit/schematics/`，`list_blueprints` 能列出，`place_blueprint` 带 `schematic` 能放置并旋转。
- [ ] 不装 WorldEdit 时：图案返回 `1003` 并提示需要 WorldEdit；其余功能不受影响。

## 阶段 5（具身模式）

- [ ] 自动化：`AgentGameTests` 通过：空背包假玩家砍树 → 合成木板/工作台/木棍 → 没有工作台时合成木镐被拒并说明原因 → 放置工作台 → 合成木镐 → 采集 10 个圆石（获得"石器时代"进度）；被困且不许挖时报 "no path"；允许挖时能挖出坑；缺物品放置/缺料合成报错；死亡使任务以 "agent died" 失败；没有镐时采集圆石快速失败（"better tool"）。
- [ ] 真实世界中，仅用 `agent_*` 工具完成上述流程（不使用 `fill_region` 等上帝模式工具）；`job_status` 中能看到 `gather` 的实时进度。
- [ ] `agent_build` 一个小蓝图：缺料时返回 `missing` 清单；材料足够时逐块建成。
- [ ] 让假玩家走进岩浆或从高处跳下致死：任务以明确原因失败；`agent_spawn` 复活。
- [ ] `agent_despawn` 后再 `agent_spawn`：背包保留。

## v2 阶段 1：场地分析与建筑方案

自动化（无需视觉模型）：`ArchGameTests` —— 场地分析避开水面并保留请求的朝向；示例两层住宅在起伏地形（2 格土丘 + 坑）上校验通过、所有楼层和房间可达、放置后门/窗/楼梯/楼板/屋顶挑檐/内部清空/地基填坑逐块正确；每类缺陷（缺楼梯、缺入口、房间不可达、屋顶缺口、屋顶朝向、墙缺口、悬空、开口越界、楼梯越界、门朝向、与已有建筑冲突、白名单外 + 放置被拒 1007）都报出对应错误码。

手动（客户端）：
- [ ] `analyze_build_site`（footprint [11, 9]）在起伏/森林地形上给出候选，`map` 与游戏内地形一致。
- [ ] 用第一个候选的 `origin`/`facing` 套用 `mc://examples/two-story-house`，`validate_building` 无错误、所有房间可达。
- [ ] `preview` + `orbit_shots`：外观完整（两层、窗、门、山墙、屋顶、平台、栅栏）；`confirm_preview` 后在室内截图：楼梯、家具、灯、门可见；`undo` 完全复原。
- [ ] 改坏方案（删楼梯、去掉门、patch 掉屋顶块）后 `validate_building` 指出对应组件。

## v2 阶段 2：预览、修改与核对

- [ ] 自动化：`RevisionGameTests` 包含屋顶修复、已建成建筑修改、非法场地参数提前拒绝、改名与越界修改保留预览/计数、错误方案确认被拒、淘汰预览清除登记信息、深地基核对、改窗户保留深地基、显式覆盖建筑校验仍受安全限制。
- [ ] 自动化：`AgentGameTests.placementWalksClearOfDiagonalCorners` 从与目标方块斜对角相交的位置出发，走开后成功放置并只消耗一个物品。
- [ ] 客户端中预览两层住宅，`inspect_building` 获取外部和各层室内图片；确认构图、遮挡和光照合理。由视觉模型指出问题，用 `revise_building` 修改后再次截图核对。这项不能用 `images: false` 的自动化测试代替。
- [ ] 预览带屋顶缺口的方案；`confirm_preview` 返回 `1007`，预览仍可见；删除缺陷 patch 后确认成功。单独测试 `ignoreValidation: true` 明确覆盖建筑校验。
