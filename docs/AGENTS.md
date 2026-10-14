# Minecraft MCP 工作目录

你在这里通过已配置的 MC-MCP 服务器查看、规划、建造和操作 Minecraft 世界。默认用中文与用户交流，按用户任务完成实际操作，并以工具返回的结果为依据报告进度。

## 启动与信息来源

- 本文件是本工作目录唯一需要读取的操作说明。不要启动时遍历目录、搜索或读取其他 Markdown，也不要为了使用 MCP 到源码仓库读取 AGENTS.md、HANDOFF.md、CLAUDE.md、PLAN、README 或 docs。只有用户明确要求开发源码或阅读某份文档时，才进入对应流程。
- 使用客户端已发现的 Minecraft MCP 工具；服务器通常叫 `minecraft`，实际命名空间可能不同。根据工具描述和实时参数 schema 调用，参数一般使用 camelCase。发现工具不需要读取本地文件。
- 本文列出的工具并非每个部署版本都提供。先核对本次实际暴露的工具和参数，不调用缺失工具，也不为了补齐工具擅自升级服务器。尤其 `inspect_building`、`revise_building`、`get_building`、`find_flat_area`、`block_info`、`refresh_view`，以及 `buildingFile`、`includeFiles`、`keepExisting`、`split`、`orbit_shots.building` 等参数属于按实际能力使用的功能（2026-10-14 之后的 bridge 才有），缺失时执行下面的替代流程。
- 不要例行加载 MCP 文档资源或 `build_assistant` 提示词。本文件已有常用流程。需要完整建筑范例时，可按需读取 JSON 资源 `mc://examples/two-story-house`，无须读取 Markdown。
- 只有任务涉及游戏时，先 `ping`；再用 `get_player_state`、`get_world_info` 确认玩家、位置和维度。后续同一任务复用已获取的状态，在玩家移动、切世界或结果存疑时刷新。不要每轮重复完整初始化。
- MCP 工具未配置时，说明需要配置 MC-MCP bridge；不要擅自安装、编译或修改客户端配置。连接失败时提示先启动带 MC-MCP 模组的 Minecraft 1.21.1 并进入世界。认证失败时提示用户核对游戏实例 `config/mcmcp.json` 中的 token 与 bridge 的 `MCMCP_TOKEN`；不要索取、显示或保存 token。
- 单人游戏暂停会使任务停滞。提示关闭暂停菜单，并用 F3+P 禁用失去焦点时暂停。超时不代表操作没有执行，先查任务状态再决定是否重试。

## 坐标、范围与基本工具

- 世界坐标为 `[x,y,z]`：y 向上，北=-z，南=+z，东=+x，西=-x。`get_player_state.blockPos` 是脚所在方块，不能直接假定为地板层。
- 区域格式为 `{"min":[x,y,z],"max":[x,y,z]}`，两端均包含；每轴长度为 max-min+1。明确使用玩家所在维度，避免工具默认的 `minecraft:overworld` 导致跨维度误操作。
- `get_area_summary` 做有界地形概览；`get_layer_slices` 读取小区域精确方块（每轴最多 32）；少量单点检查用 `get_block`。不扫描整个世界。
- 找大块平地（城堡、村庄、农场，超过 48×48）用 `find_flat_area`：`{"size":[100,200],"radius":200,"maxHeightDiff":4}`。它在允许区域内采样地面（忽略树），返回候选的 min/max、`groundY`（可直接作方案 origin 的 y）、高差、土方量、树和水的比例，以及 `surface` 构成和 `allowedRegions`。默认只搜已加载区块（看 `coverage`），需要时显式 `loadChunks:true`，但大半径很慢。结果是近似值，选定后再用 `analyze_build_site` 或 `get_area_summary` 核对。不要再靠多次 `get_area_summary` 手工拼地形图。
- 放置有朝向的方块前（楼梯、门、活板门、栅栏门、床、旗帜、告示牌、火把、梯子、按钮、拉杆、活塞），用 `block_info {"block":"minecraft:oak_trapdoor"}` 查属性取值和朝向含义，不靠猜。它的说明来自 1.21.1 的方块代码：门和活板门的板子在 facing 的**反方向**一侧；墙上旗帜/告示牌/火把/梯子的 facing 指向背离墙的一侧；立式旗帜/告示牌 rotation 0=南、4=西、8=北、12=东。放完仍要截图确认。
- 重复结构用 `run_script`，长方体用 `fill_region`，零散细节用 `place_blocks`。`fill_region.mode`：solid 填满，hollow 外壳+内部空气，outline 只改外壳。`place_blocks.blocks` 为 `[[x,y,z,"minecraft:stone"],...]`，每次最多 4096 项。清空用 `clear_region`。超过单次上限（默认 262144）的长方体填充/清理，若 schema 有 `split`，传 `split:true`，由服务器按层拆成多个依次执行的任务（每个一步撤销，返回最后一个任务和全部 `jobIds`）；等最后一个任务 done 再核对。
- 蓝图用 `save_blueprint`、`list_blueprints`、`place_blueprint`。蓝图 JSON 为 palette+layers：层从下往上对应 y，层内字符串行对应 z，字符对应 x；旋转为俯视顺时针 0/90/180/270，镜像 none/x/z。`.schem` 和混合方块 pattern 依赖可选 WorldEdit，先确认 `get_world_info` 的能力。

## 建造与修改流程

1. 明确用户的用途、风格、规模和位置；从任务中推断常规细节，只询问会实质改变结果的缺失信息。先查看场地和现有建筑，避免覆盖无关结构。
2. 房屋优先 `analyze_build_site` → 建筑方案 → `validate_building`；选址搜索 radius 为 4–48，常用 24，指定 footprint（每边最多 48）。更大的场地先用 `find_flat_area` 找区域，再在区域内选址。用返回的 origin/facing，不猜地面高度。其他结构可用脚本或蓝图。
   - 方案较大或需要反复修改时，把它保存为用户工作目录里的 `.json` 文件，用 `buildingFile`（绝对路径）代替内联 `building` 调用 `validate_building` / `preview` / `place_blueprint` / `revise_building`；改文件后再调用即可，不必每次重发整个 JSON。`validate_building` 默认只返回 `componentCount`，需要每个组件的范围时传 `details:true`。
3. 脚本先 `run_script` 的 `dryRun:true` 核对方块数和包围盒；随后 `preview` 展示方案。预览是假方块，不修改真实世界，也不需要 undo。它返回 previewId；记录该 ID 和建筑 name。
4. 若有 `inspect_building`，用它检查 validation、metrics 和实际图片；没有则结合 `validate_building` / `preview` 返回的校验报告与 `orbit_shots` 核对外观。`orbit_shots` 需要 `region`：覆盖整个结构（包括塔、屋顶、地基和附属物）的真实世界包围盒，例如 `{"region":{"min":[100,64,200],"max":[114,94,214]}}`；已登记的建筑在 schema 支持时可改传 `{"building":"建筑名"}`，用其编译范围。不能传空参数。检查入口、楼梯（含头顶净空）、房间通达、屋顶、地基和用户需求。
5. 若有 `revise_building`，用组件操作修复；没有则修改保留的 building JSON 或脚本，重新校验并 `preview`。已建成结构可在用户授权范围内用小范围脚本补改，但先读取目标区域、核对补丁范围。脚本补改可能使登记方案与真实世界不同，不能继续声称原方案完全匹配；不要未经检查整栋重放或假定改动已同步登记表。每次修改后重新检查。默认自主修改最多 3 轮；有服务器轮数限制时也遵守限制，只有真实获得继续授权且参数可用才设置 `userApproved:true`。
6. 用户只要求预览时停在预览；需要最终确认且尚未授权时，先完成可审阅的预览，再请用户确认。已经明确授权建造/修改时，完成校验后执行，不反复请求同一授权。`confirm_preview` 使用记录的 previewId；放弃用 `discard_preview`。
7. 等待任务实际完成，再核对真实世界。返回 jobId 或嵌套 job 的 queued/running 时，调用 `job_status`，按进度间隔几秒检查，避免密集轮询。只有 state=done 才报告施工完成；failed/cancelled 可能留下部分方块。
8. 已登记建筑的后续修改，有 `get_building` 时先读取登记状态，保留原 name 和组件 id；有 `revise_building` 时用差分修改。built 建筑只有在用户授权修改后才传 `confirm:true`。building 状态先等任务结束；incomplete 先确认部分结果，再撤销或按授权完整重建。缺少登记工具时用保留的 JSON 方案、实际方块和可用任务/历史工具核对，不编造登记状态，不改名绕过限制。

恢复已有任务时，按需使用当前可用的 `get_building`、`job_status`、`list_history` 与方块读取工具获取服务器现状，不以本地笔记推断世界状态。

### 预览生命周期与残影

- 不假定多个预览同时有效。本次使用中，新预览替换了旧预览；每次成功 `preview` 后以最新返回的 previewId 为准，旧 ID 视为可能失效。确认或丢弃都只使用当前有效 ID，不复用历史 ID。
- `discard_preview` 返回 `no active preview with id ...` 时，旧预览可能已被替换、确认或清除；这不是施工失败。停止重试旧 ID，核对最新预览记录，不能误丢弃新预览。
- 确认建造后若截图仍有白色半透明残影，先等施工任务完成，用 `get_block` / `get_layer_slices` 检查残影对应的真实方块，再重新截图。残影不等于真实方块残留，也不能未经核对就归为客户端问题。
- 若真实方块正常而残影仍在，有 `refresh_view` 时对该区域调用一次（把真实方块重发给客户端，不改世界），再重新截图；没有时提示用户用 F3+A 重新加载客户端区块；必要时退出并重新进入世界核对。不要用 clear_region、undo 或重复建造来消除未经证实的视觉残影。仍可复现时报告“疑似预览显示残留”，保留位置和图片，说明尚未定位原因。

### 截图能力与核对范围

- `screenshot` 不传相机参数时拍玩家当前视角。当前 bridge 提供 `pos` / `lookAt`（以本次 schema 为准），例如 `{"pos":[100,66,200],"lookAt":[104,66,204]}`，可用来拍室内；不猜字段名。
- 客户端只持有玩家渲染距离内的区块：相机放得再近，远处的建筑也画不出来，只有天空和雾。目标太远时 `screenshot` / `orbit_shots` / `inspect_building` 会返回 `warning`（说明距离和应靠近的位置）。看到这个警告就请用户把玩家移到建筑附近再拍，不要根据这些图片判断外观。截图期间地形雾已关闭；若图片仍发白，多半是距离问题。
- 若当前工具没有相机参数，不承诺能自动拍室内：可请用户把玩家移到指定房间并调整视角后调用 screenshot；不能移动玩家时用方块切片检查内部，并明确报告“已做结构核对，未做室内视觉检查”。外部 orbit_shots 不等同于室内视觉验收。
- 普通方块读取返回真实世界，不读取客户端预览假方块。预览阶段的结构判断依赖方案校验和预览图片；建成后再用方块读取核对实际结果。没有图片时说明预览外观尚未确认，不用真实世界切片冒充预览检查。

## 建筑方案速查

`validate_building` / `preview` / `place_blueprint` 接收 `building` 对象；它的核心字段如下：

| 字段 | 格式与含义 |
| --- | --- |
| format / name | `"mcmcp.building/1"` / 唯一且稳定的建筑名 |
| origin / facing | 世界 `[x,y,z]` / north、south、east、west |
| footprint | `[width,depth]`，各 3–48 |
| floors | `[{"id":"ground","height":4},...]`，从底层起，1–6 层，height 3–12 包含地板层 |
| rooms | `[{"id":"hall","floor":"ground","function":"living","min":[1,1],"max":[7,5]}]` |
| partitions | `[{"id":"divider","floor":"ground","from":[4,1],"to":[4,5]}]`，直线内墙 |
| openings | `[{"id":"opening-entry","type":"door","floor":"ground","wall":"front","at":4}]`；type 可为 window/arch；at 是起始格；可指定 width、height、sill；内墙用 partition 替代 wall，见下例。door 只能 1–2 宽、2 高（原版门）；城门等更宽更高的入口用 `arch`（任意宽高，算作入口），门扇/吊闸再用 patches 补 |
| stairs | `[{"id":"steps","from":"ground","start":[1,6],"direction":"back","width":1}]`，方向 front/back/left/right |
| roof | `{"id":"roof","type":"gable","ridge":"width","overhang":1}`；type 可为 hip/flat/none，ridge 可为 depth，overhang 0–2 |
| style | 方块材质映射：foundation、floor、wall、frame、roofStairs、roofSlab、roofBlock、stairs、window、door、ground、path、light、fence、gate、tower、trim |
| terrain | `{"margin":2,"maxFoundationDepth":12}`，margin 0–6，深度 1–32 |
| outdoor | 组件数组：`{"id":"path","type":"path","length":4}`；也支持 fence（margin）和 lanterns |
| components | 塔组件数组：`{"id":"tower","type":"tower","at":[x,z],"radius":r,"height":h,"top":"battlement"}`；top 也支持 cone/flat |
| furnish / replaceExisting | 自动按房间功能放家具（默认 true）/覆盖现有人工方块（默认 false） |
| patches | `[{"id":"fix","component":"roof","blocks":[[x,y,z,"minecraft:stone"]]}]`，局部坐标补丁，最后应用 |
| requirements | 可选：symmetricFront、roofSymmetric、alignedWindows、minWindowsPerFloor、maxHeight、minWindowRatio |

局部 x 从正面看的左到右，z 从后到前，y=0 为首层地板；内部范围 x=1..width-2、z=1..depth-2。origin 是旋转后占地的世界 x/z 最小角，不是入口。opening.wall 为 front/back/left/right；前后墙 at 对应局部 x，左右墙对应局部 z，避开角柱。stairs 步数等于下层 height，终点后一格作为平台须留空。多层建筑必须有可通行楼梯。
- **楼梯净空**：玩家上一级台阶时身体还在下一级上方就已抬升，所以每一级（以及第一级前面的地面）上方要有三格空，最上一级两格。编译器会自动在上层楼板开出对应的洞（满层高时沿楼梯方向 3 格），校验用 `STAIRS_HEADROOM` 报告挡住净空的方块（patches、家具、装饰）。旧版本（2026-10-14 之前）建的楼梯可能只开了 2 格，玩家会撞头。自己用脚本做楼梯时同样按"每级上方三格"开洞，并在建成后让玩家实走或截图确认。

### 开口、ID 与材质

- **at 是第一格，不是中心格。** 开口占 at..at+width-1；`at:7,width:3` 占 7..9，中心为 8。若中心要在 7，宽度 3 应设 at=6。奇数宽度的居中公式为 `at=center-(width-1)/2`；偶数宽度中心落在半格，先明确要占哪些格，不随意取整。前墙和后墙都按局部坐标递增，不能因从外面观看方向不同而把 at 当中心或反转它。
- 隔墙上的 at 是从 partition.from 沿 from→to 方向的格数偏移，0 为起点，width 向同一方向延伸。先把希望占据的位置换算成偏移，再检查 `at+width-1` 是否仍在隔墙范围内。
- 隔墙开口的完整字段示例（假设 ground 楼层已存在）：

```json
{
  "partitions": [
    {"id":"partition-hall","floor":"ground","from":[5,1],"to":[5,9]}
  ],
  "openings": [
    {"id":"opening-hall-arch","type":"arch","floor":"ground","partition":"partition-hall","at":3,"width":3,"height":3}
  ]
}
```

此例开口沿隔墙占局部 z=4..6，中心 z=5；`partition` 引用隔墙 id，不再传 wall。

- 所有 floor、room、partition、opening、stairs、roof、tower、outdoor、patch 的 id 使用互不重复的命名。可用 `room-entry`、`opening-entry`、`partition-hall` 等前缀，避免房间和门都叫 entry。自动家具使用房间 id 标识其组件，房间 id 即使暂未触发冲突也要预留，不能让其他组件复用；不要通过关闭家具掩盖命名冲突。
- `style.trim`、`style.stairs`、`style.roofStairs` 必须是楼梯方块，如 `minecraft:stone_brick_stairs`；roofSlab 必须是台阶，door 必须是门。若服务器报 `class_2510 block` 等内部类名错误，结合出错的材质字段解释成“trim 必须使用楼梯方块”，不要把内部类名作为用户说明。

### 塔楼与内饰效果

- `tower` 组件不会保证穿透主屋顶后形成足够高的塔身。放在主屋内部时，本次使用出现过被屋顶遮挡、只露短截而像烟囱的效果。现在塔高出周围屋顶不足 4 格时校验给出警告 `TOWER_HIDDEN`，并写明需要的高度；按它调整 height 或改到占地外。设计钟楼时核对塔顶世界高度与所在位置的屋脊高度、可见塔身比例；用侧面和高处截图验收，不把成功放置视为外观达标。
- 若组件无法满足效果，改为外置塔、调整高度/位置，或用独立脚本设计塔楼；检查与屋顶、楼板的交界，确认最终施工顺序不会再遮住塔身。脚本补建后重新核对真实方块与外观。
- 自动家具只是基础摆设，可能每房间仅少量家具，不代表完整内饰。用户要求完整装修时逐房间安排桌椅、储物、照明和装饰，必要时使用 patches 或小范围脚本补充，保留门口、楼梯、走道和头部空间；若不需要自动家具可显式 furnish=false。

有 `revise_building` 时，`ops` 用以下形式；不要把顶层 field 写成路径：

```json
[
  {"op":"update","id":"roof","set":{"overhang":1}},
  {"op":"remove","id":"yard-fence"},
  {"op":"add","to":"openings","value":{"id":"new-window","type":"window","floor":"ground","wall":"back","at":2,"width":2}},
  {"op":"set","field":"style","merge":true,"value":{"wall":"minecraft:white_terracotta"}}
]
```

这些是格式示例，不是预先验证过的完整设计。以当前世界中的 `validate_building`、可用的建筑检查工具和实际截图结果修正设计。

## 脚本速查

`run_script` 使用 `source`（内联 JavaScript）或 `sourceFile`（bridge 所在机器可读取的脚本路径；新版 bridge 也接受别名 `file`），以及必填世界 `origin`。文件方式优先传绝对路径，相对路径基于 bridge 进程工作目录，不能假定与 agent 工作目录相同。`preview` 若 schema 提供相同字段，也可以用 source/sourceFile + origin。

- 公共函数放进单独的 `.js` 文件，用 `includeFiles:["C:/minecraft-work/lib/walls.js", ...]` 在脚本之前按顺序加载（同一作用域）；不需要再用 shell 拼接文件。库文件里的错误会写明文件名和行号。
- 不想覆盖已有方块时传 `keepExisting:true`：只在空气或可替换方块（草、花、雪层）处写入，其余跳过，结果里 `skippedExisting` 是跳过的数量。脚本中可用 `b.keepExisting(true/false)` 局部切换，例如只对装饰开启。

```json
{"origin":[100,64,200],"source":"b.set(0,0,0,'minecraft:stone_bricks');","dryRun":true}
```

```json
{"origin":[100,64,200],"sourceFile":"C:/minecraft-work/build.js","dryRun":true}
```

文件路径仅为格式示例，需要换成实际存在且 bridge 可访问的 `.js` 文件。收到 `pass source or sourceFile` 时检查字段；旧版 bridge 不认 `file`，改用 `sourceFile`。

脚本坐标相对 origin，非整数向下取整。若脚本已经写的是世界绝对坐标，把 `origin` 设为 `[0,0,0]`：例如 `b.set(100,64,200,...)` 就作用于世界 `[100,64,200]`。不要同时用世界坐标和非零 origin，导致二次偏移。origin 为零并不绕过任何区域或体积保护。脚本仅描述改动，结束后才提交为一次可撤销任务。

```javascript
b.set(x,y,z,block);
b.get(x,y,z); // 返回脚本已设置的状态，否则读取世界
b.fill(x1,y1,z1,x2,y2,z2,block,mode); // 两端包含
b.line(x1,y1,z1,x2,y2,z2,block);
b.cylinder(x,y,z,radius,height,block,hollow); // 底层中心，竖直向上
b.sphere(x,y,z,radius,block,hollow); // 球心
b.log("message");
b.keepExisting(true); // 之后只写入空气/可替换方块（需新版）
```

- 引擎是 Rhino。新版 mod 会把 `const` 当作 `let` 执行，所以循环内的 const、`for (const [x, z] of pts)`、展开 `[...a]`、剩余参数、箭头函数、模板字符串都可以用；旧版 mod 仍会拒绝循环内的 const，遇到"use let instead of const"就改用 let。不支持 `class`、async/await、生成器。不要依赖循环闭包的逐轮变量隔离（需要时把值作为函数参数传入）。
- 默认脚本 5 秒、最多 262144 个不同位置。脚本无 Java、文件、网络或操作系统访问能力。优先 inline source；仅在确需复用时写用户工作目录里的 `.js`/`.json`，不要读取源码仓库来完成建造。
- 方块格式 `minecraft:oak_stairs[facing=north,half=bottom]`。原始脚本/方块放置需显式设置门、床和高植物的两半；树叶使用 persistent=true。不要把 b.origin 当数组，它是只读 `{x,y,z}`。
- 不确定朝向时先 `block_info`。普通下半楼梯（half=bottom）的 facing 是走上台阶时前进的方向，高的整块半边在 facing 一侧；做椅子时这相当于靠背所在方向，座位朝向其反方向。例如椅子靠背在北、坐面朝南，使用 facing=north。不要把 facing 当成椅子面向的方向；half=top 或内外转角 shape 的形状需另行核对。

## 任务、撤销与限制

- `cancel_job` 取消任务后，已经放置的方块仍在。等取消生效后，检查 `list_history` 确认最近的改动确实属于本次任务，再 `undo`。不要撤销其他人的改动。`redo` 重做最近撤销；新改动会清空 redo。历史默认最多 50 步，不保证跨重启保留。
- 保留服务器的 allowedRegions、体积上限和方块黑名单。默认主世界 x/z 约 ±256，单次上限 262144，但以实际配置和错误返回为准。玩家站在允许区域外（例如 x≈-350）时不能在玩家身边建造：1001 的消息和 `data.nearestAllowed` 会给出同尺寸区域最近能放的位置，向用户说明并建议在那里建，或请用户自己扩大 `allowedRegions`。
- 新版默认自动加载读写涉及的未加载区块（配置 `loadChunksByDefault`，只在允许区域内），一般不必再传 `loadChunks:true`；旧版仍需要。不要自动扩大区域、禁用保护或使用 `ignoreValidation:true` / `replaceExisting:true` 绕过问题；用户明确授权相应效果后才使用。
- 世界放置抑制邻居更新：不要承诺自动流体、落沙或红石正常激活。当前没有红石网络检查/触发/测试工具。只完成放置不能证明电路能工作。
- 截图工具仅单人客户端可用；客户端渲染距离外的结构不会完整显示，结果里的 `warning` 会说明。服务器 loadChunks=true 也不保证客户端已渲染。拿不到图片或收到距离警告时不得声称已经看过。
- 生存玩法用 `agent_spawn`、`agent_state`、`agent_goto`、`agent_mine`、`agent_collect`、`agent_place_block`、`agent_craft`、`agent_equip`、`agent_gather`、`agent_build`、`agent_despawn`。材料来自库存，会消耗，有伤害和死亡；不要用创造式工具替用户绕过生存要求。无路径或材料不足先查状态，按具体原因处理，不无限重试。

## 错误处理与报告

- 1001 超出允许区域：按消息里的最近可用位置（`data.nearestAllowed`）调整，或说明需用户修改配置。
- 1002 操作过大：`fill_region` / `clear_region` 传 `split:true`；其他操作缩小或合理分批。说明多批对应多次历史，不用分批绕过区域保护。
- 1003 方块未知/禁用：修正方块名、状态或选择允许材质。
- 1004 区块未加载：新版只在显式 `loadChunks:false` 时出现；旧版在任务需要时使用 loadChunks=true。加载可能生成区块，限制范围。
- 1005 任务不存在：刷新近期任务列表，不重复提交未知结果的写操作。
- 1006 脚本错误：按返回的文件名（includeFiles 中的库）和行号修正，再 dryRun。
- 1007 建筑校验失败：按组件/坐标修复；失败后预览通常仍保留，不直接重新建造。
- -32602 参数错误：按实时工具 schema 修正。

完成时简洁报告：实际位置/维度、建筑名、真实任务结果、校验和视觉检查结果、仍未解决的问题。分别说明计划、预览和已完成施工；不要把工具调用成功当作游戏行为验收成功。按需保存 JSON 方案、JS 脚本或图片，不例行生成或读取 Markdown 日志。
