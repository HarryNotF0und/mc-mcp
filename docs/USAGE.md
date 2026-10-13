# MC-MCP: let an AI build and play in Minecraft

MC-MCP connects AI assistants that speak the [Model Context Protocol](https://modelcontextprotocol.io)
(Claude Code, Claude Desktop, ...) to a running Minecraft world. It has two parts:

- **The mod** (`mod/`, Fabric, Minecraft 1.21.1) runs inside the game and executes requests.
- **The bridge** (`bridge/`, a small Rust program) is the MCP server the AI talks to. It forwards
  each tool call to the mod over a local WebSocket.

```
Claude ──MCP (stdio)──▶ mc-mcp-bridge ──WebSocket + token──▶ MC-MCP mod (in Minecraft)
```

---

## What it can do

### See the world
- **Player state**: where you are, which way you face, what you are looking at, health, items in hand.
- **Area summary**: the most common blocks, a surface height map, water/lava, nearby entities.
- **Exact blocks**: any region up to 32 × 32 × 32 as a compact text grid (palette + layers).
- **Screenshots** (single-player): from any camera position, without the HUD, in daylight on request;
  **orbit shots** photograph a build from four sides so the AI can check its own work.

### Plan buildings like an architect
- **Site analysis** (`analyze_build_site`): scans the terrain around you (ground height through
  trees, water, existing builds, roads) and recommends where a building of a given size fits best,
  with the floor level, how much earth to cut and fill, and which side the entrance should face.
- **Building plans**: describe a building as components (floors, rooms, interior walls, doors,
  windows, arches, stairs, roof, towers, path, fence) in a structured JSON format. The mod turns it
  into blocks, with a foundation down to solid ground and a level terrace on uneven land.
- **Validation** (`validate_building`): checks the design before anything is built: every floor
  and room can be walked to, entrances and stairs work, walls and roof are closed, nothing
  floats, doors and stairs face the right way, no existing building is overwritten, and it stays
  in the allowed area. Problems name the component to fix.
- **Look and revise** (`inspect_building`, `revise_building`, `get_building`): the AI photographs
  the preview (or the finished house) from automatically chosen viewpoints (front, back, sides, above,
  inside each floor), gets measurements (facade symmetry, window ratio and alignment, roof overhang
  and symmetry, your stated requirements), and fixes just the parts that are wrong: only the changed
  blocks are updated. Changing a finished building needs your confirmation, and after 3 rounds the AI
  stops and asks you.

### Build ("god mode")
- **Place blocks / fill boxes** (solid, hollow, outline) / **clear** regions, with exact block states
  such as `minecraft:oak_stairs[facing=north]`.
- **Build scripts**: one JavaScript program can build a whole tower, bridge or house:
  `b.set`, `b.fill`, `b.line`, `b.cylinder`, `b.sphere`, `b.get`. The scripts run in a sandbox with
  a time limit and no access to your computer.
- **Blueprints**: export any region, then place it elsewhere, rotated 90/180/270° and/or mirrored,
  with stairs, logs and doors turned the right way. Saved by name on the server.
- **Preview**: show a script or blueprint as *fake blocks* only you see, look at it, then confirm or
  discard it. The world is not touched until you confirm.
- **Undo / redo**: every build step is one undoable change (up to 50), including chest contents.
- **Big jobs without lag**: large edits are spread over many ticks (2000 blocks per tick by default);
  a 200,000-block fill takes about 5 seconds with no noticeable lag.
- **WorldEdit (optional)**: if WorldEdit is installed, use its **patterns**
  (`60%stone_bricks,25%mossy_stone_bricks,15%cracked_stone_bricks`) and place or save **`.schem`
  schematics**, including ones downloaded from the internet. Undo and the safety limits still apply.

### Play survival ("embodied mode")
A fake player (the **agent**) joins the game and acts like a real player, without god-mode powers:
- **walks** with pathfinding (jumps, drops, and digs through blocks when allowed),
- **mines** with the best tool it has, taking as long as a player would,
- **picks up** items, **places** blocks from its inventory, **crafts** with the game's recipes
  (needs a crafting table for 3 × 3 recipes), **equips** tools and armor,
- **gathers** "10 cobblestone" or "5 oak logs" by itself, and **builds a blueprint by hand**,
  telling you exactly which materials are missing.

It takes damage and can die. Every failure comes with a reason ("no path", "stuck", "died",
"needs a better tool", ...).

### Safety
- The mod only listens on `127.0.0.1` and requires a random token.
- God-mode writes are limited to **allowed regions** (default: 256 blocks around 0, 0 in the
  overworld), a **maximum size** per operation (262,144 blocks), and a **block blacklist**
  (default: command blocks and TNT).
- Writes never trigger block updates outside the edited area (no flowing water, falling sand or
  redstone surprises), which keeps undo exact.

---

## Requirements

- Minecraft **1.21.1** with **Fabric Loader 0.19.5+** and **Fabric API** (0.116.x for 1.21.1).
- Optional: **WorldEdit for Fabric 7.3.x** (for patterns and `.schem` files).
- To build from source: **JDK 21** and **Rust** (stable).
- An MCP client: **Claude Code** or **Claude Desktop**.

---

## Installation

### 1. Build

```bash
cd mod
./gradlew build
```

The mod jar is `mod/build/libs/mcmcp-0.1.0.jar`. Its libraries (Java-WebSocket, Rhino) are bundled.

```bash
cd bridge
cargo build --release
```

The bridge is `bridge/target/release/mc-mcp-bridge` (`mc-mcp-bridge.exe` on Windows).

### 2. Install the mod

1. Install Fabric Loader for 1.21.1 (the Fabric installer creates a launcher profile).
2. Copy `mcmcp-0.1.0.jar` and the **Fabric API** jar (and optionally **WorldEdit**) into your
   `mods` folder (`%APPDATA%\.minecraft\mods` on Windows, `~/.minecraft/mods` on Linux,
   `~/Library/Application Support/minecraft/mods` on macOS).
3. Start the game and open a world. The log shows `MC-MCP listening on ws://127.0.0.1:25580`.

The first start creates **`config/mcmcp.json`** in the game folder, with a random `token`.

> **Tip:** In single-player the game pauses when its window loses focus, and paused worlds don't
> run jobs. Press **F3 + P** in game to turn off "pause on lost focus" while you work with the AI.

### 3. Connect your AI

Use the `token` from `config/mcmcp.json`.

**Claude Code**, in your project folder:

```bash
claude mcp add minecraft -e MCMCP_TOKEN=<token> -- /path/to/mc-mcp/bridge/target/release/mc-mcp-bridge
```

**Claude Desktop**: add this to `claude_desktop_config.json` (Settings → Developer → Edit Config):

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "C:\\path\\to\\mc-mcp\\bridge\\target\\release\\mc-mcp-bridge.exe",
      "env": { "MCMCP_TOKEN": "<token>" }
    }
  }
}
```

The bridge can start before the game: it connects on the first tool call and reconnects when you
leave and re-enter a world.

| Bridge variable | Default | Meaning |
|---|---|---|
| `MCMCP_TOKEN` | (required) | Token from `config/mcmcp.json` |
| `MCMCP_URL` | `ws://127.0.0.1:25580` | Where the mod listens |
| `MCMCP_TIMEOUT_MS` | `30000` | Timeout for a single request |
| `MCMCP_CONNECT_TIMEOUT_MS` | `3000` | Timeout for connecting |
| `MCMCP_JOB_WAIT_MS` | `20000` | How long build tools wait for their job (agent tools wait at least 60 s) |
| `MCMCP_LOG` | `info` | Log level (logs go to stderr) |

### 4. Check it works

Ask the AI: *"Ping Minecraft and tell me where I am."* It should call `ping` and
`get_player_state`.

---

## Using it

Just describe what you want. Some examples:

- *"Build a 7 × 5 × 7 stone-brick house with a door right in front of me."*
- *"Write a script for a round tower, 15 blocks across and 30 tall, with a spiral staircase. Preview
  it first, show me photos from all sides, then build it."*
- *"That roof looks wrong. Undo it and try again."*
- *"Save the house as a blueprint called `house` and build three copies along the river, each
  rotated 90°."*
- *"Fill the area with a mix of mossy and cracked stone bricks."* (needs WorldEdit)
- *"Place the `castle.schem` from my schematics folder on the hill."* (needs WorldEdit)
- *"Spawn an agent. Without building tools, chop a tree, make a crafting table and a wooden
  pickaxe, then mine 10 cobblestone."*
- *"Have the agent build the `house` blueprint by hand and tell me what materials it still needs."*

The bridge also offers a **`build_assistant`** prompt (a recommended workflow) and a resource
**`mc://docs/script-api`** (the scripting reference). Claude reads these on its own when it needs them.

### Typical building workflow

1. **Look**: `get_player_state` → `get_area_summary` → `get_layer_slices` for detail.
2. **Plan** exact coordinates (x east, y up, z south; region corners are inclusive).
3. **Try**: `run_script` with `dryRun: true`, or `preview` and `orbit_shots`.
4. **Build**: `run_script` / `fill_region` / `place_blocks` / `place_blueprint`, or `confirm_preview`.
5. **Check**: `orbit_shots` / `screenshot`, and `undo` if needed.

---

## Tool reference (39 tools)

**Basics**: `ping`, `get_world_info` (versions, players, time, tick time, whether paused, WorldEdit).

**Seeing**

| Tool | Purpose |
|---|---|
| `get_player_state` | Position, facing, game mode, health, items in hand, the block in the crosshair |
| `get_block` | One block's state (and block entity data) |
| `get_area_summary` | Block counts, surface heightmap, fluids, entities for up to 262,144 blocks |
| `get_layer_slices` | Every block of a region (≤ 32 per side) as palette + layers |
| `screenshot` | A picture from any position/direction (single-player) |
| `orbit_shots` | Pictures of a region from several sides (single-player) |

**Planning**

| Tool | Purpose |
|---|---|
| `analyze_build_site` | Terrain summary, character map, ranked building sites with `origin`/`facing` |
| `validate_building` | Compile and check a building plan; per-component errors and reachability |
| `inspect_building` | Pictures from planned viewpoints + validation, design metrics, requirements, world verification |
| `revise_building` | Change components of a registered building; only differing blocks change (confirm for built ones) |
| `get_building` | Plan and status of a registered building, or the list of them |

**Building**

| Tool | Purpose |
|---|---|
| `place_blocks` | Up to 4096 individual blocks `[x, y, z, "block"]` |
| `fill_region` | Fill a box: `solid`, `hollow`, `outline`; block or WorldEdit pattern |
| `clear_region` | Set a box to air |
| `run_script` | Run a build script (`source` or `sourceFile`), `dryRun` for statistics |
| `place_blueprint` | Place a blueprint (`blueprint` JSON, saved `name`, WorldEdit `schematic`) with rotation/mirror, or a building plan (`building`) |
| `save_blueprint` | Export a region as JSON or (with WorldEdit) `.schem` |
| `list_blueprints` | Saved blueprints and schematic files |
| `preview` / `confirm_preview` / `discard_preview` | Fake-block previews (scripts, blueprints, building plans) |
| `job_status` / `cancel_job` | Progress of long operations; cancel (finished part stays undoable) |
| `undo` / `redo` / `list_history` | History of changes |

**Survival agent**

| Tool | Purpose |
|---|---|
| `agent_spawn` / `agent_despawn` / `agent_state` | Manage the agent (inventory is saved between sessions) |
| `agent_goto` | Walk somewhere (`dig: true` lets it dig through) |
| `agent_mine` | Break a block with the best tool |
| `agent_collect` | Pick up dropped items nearby |
| `agent_place_block` | Place a block from the inventory |
| `agent_craft` | Craft from the inventory |
| `agent_equip` | Hold an item or wear armor |
| `agent_gather` | Get N of an item by itself |
| `agent_build` | Build a blueprint by hand from the inventory |

Full details of every request and response: [`protocol/spec.md`](../protocol/spec.md).

---

## Designing a building

1. *"Find a spot near me for an 11 × 9 house"* → `analyze_build_site {"footprint": [11, 9]}` returns
   candidates such as `{"origin": [14, 65, 8], "facing": "east", "stats": {"cut": 16, "fill": 34, ...}}`.
2. The AI writes a plan (format: [`docs/building-plan.md`](building-plan.md), also served as
   `mc://docs/building-plan`), usually starting from the example `mc://examples/two-story-house`
   with the candidate's `origin` and `facing`:

   ```json
   {
     "format": "mcmcp.building/1", "name": "house", "origin": [14, 65, 8], "facing": "east",
     "footprint": [11, 9],
     "floors": [{"id": "ground", "height": 4}, {"id": "upper", "height": 4}],
     "openings": [{"id": "front-door", "type": "door", "floor": "ground", "wall": "front", "at": 5}],
     "stairs": [{"id": "main-stairs", "from": "ground", "start": [1, 6], "direction": "back"}],
     "roof": {"type": "gable"}
   }
   ```

   Local coordinates: x from the left wall to the right wall (seen from the front), z from the back
   to the front, y up from the ground floor. The mod handles the rotation.
3. `validate_building {"building": ...}` until `ok` is true. Errors look like
   `{"code": "UNREACHABLE_ROOM", "components": ["study"], ...}`, and the fix goes into that component.
4. `preview {"building": ...}`, then `inspect_building {"name": ...}` to see it from every side and
   inside, `revise_building {"name": ..., "ops": [...]}` for anything wrong (up to 3 rounds), then
   `confirm_preview` (one undoable change). Add `"requirements": {"symmetricFront": true,
   "roofSymmetric": true, "minWindowsPerFloor": 2}` to the plan to have them checked every time.

`confirm_preview` rejects building plans with validation errors (`1007`) and keeps the preview
available to repair with `revise_building`. An explicit `{"previewId": 1, "ignoreValidation": true}`
overrides architectural validation; region and size limits still apply. Failed revisions leave
the design and counters unchanged, and revisions must keep the building's name.

## Build scripts in short

```js
// origin = centre of the base; coordinates are relative to it
b.cylinder(0, 0, 0, 7, 30, "minecraft:stone_bricks", true);   // hollow tower, 15 wide, 30 tall
b.fill(-1, 0, 7, 1, 2, 7, "minecraft:air");                    // doorway on the south side
const R = 7;                                                   // const is fine outside loops
for (let i = 0; i < 64; i += 2) {                             // battlements: use let inside loops
  let a = i / 64 * 2 * Math.PI;
  b.set(Math.round(R * Math.cos(a)), 30, Math.round(R * Math.sin(a)), "minecraft:stone_bricks");
}
b.log("tower done");
```

- Coordinates are relative to `origin`, `y` is up, north is `-z`, east is `+x`.
- Use `let` (not `const`) inside loops. The engine (Rhino) handles `const` in loops incorrectly, so
  such scripts are rejected with the line number. No `class` or `...` spread syntax.
- 5-second limit, at most 262,144 blocks, no access to Java or files.

Full reference with examples: [`docs/script-api.md`](script-api.md). Ready-made scripts:
[`examples/scripts/`](../examples/scripts) (tower, arch bridge, cottage) and a blueprint in
[`examples/blueprints/`](../examples/blueprints).

---

## Configuration: `config/mcmcp.json`

```json
{
  "host": "127.0.0.1",
  "port": 25580,
  "token": "generated on first start",
  "allowedRegions": [
    { "dimension": "minecraft:overworld", "min": [-256, -64, -256], "max": [256, 320, 256] }
  ],
  "maxVolumePerOp": 262144,
  "blocksPerTick": 2000,
  "blockBlacklist": ["minecraft:command_block", "minecraft:tnt"],
  "scriptTimeoutMs": 5000,
  "historySize": 50
}
```

| Key | Meaning |
|---|---|
| `host`, `port` | Where the mod listens. Keep `127.0.0.1` unless you know what you are doing. |
| `token` | Shared secret for the bridge. Delete it to generate a new one. |
| `allowedRegions` | Boxes (per dimension, corners inclusive) where god-mode tools may build. An empty list allows nothing. The agent is not limited by this, since it can only do what a survival player can. |
| `maxVolumePerOp` | Largest fill, script result, blueprint or area summary (blocks). |
| `blocksPerTick` | Placement speed. Lower it if the server lags. |
| `blockBlacklist` | Blocks that may not be placed, also inside patterns and scripts. |
| `scriptTimeoutMs` | Time limit for build scripts. |
| `historySize` | Number of undo steps kept (history is lost when the server stops). |

The file is re-read every time a world or server starts.

Folders in the game directory: blueprints are saved in `mcmcp/blueprints/`, and WorldEdit
schematics are in `config/worldedit/schematics/`.

---

## Dedicated servers

Put the mod (and Fabric API) in the server's `mods` folder; the config is in the server's
`config/`. Everything except **screenshots** works (those need the game client in the same
process). The mod only listens on `127.0.0.1`, so run the bridge on the server machine, or use an
SSH tunnel (`ssh -L 25580:127.0.0.1:25580 server`) and keep `MCMCP_URL` at its default.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| "cannot connect to Minecraft" | The game is not running, no world is open, or the port differs. Check the log for `MC-MCP listening`. |
| "Minecraft rejected the token" | `MCMCP_TOKEN` must equal `token` in `config/mcmcp.json` of the game you are playing. |
| A job stays `running` with a "game is paused" warning | Close the pause menu, or press **F3 + P** so the game keeps running unfocused. |
| Error `1001` (outside allowed region) | Build closer to the allowed area, or extend `allowedRegions`. |
| Error `1002` (too large) | Split the job, or raise `maxVolumePerOp`. |
| Error `1003` (unknown/blacklisted block) | Check the block id and properties; patterns need WorldEdit. |
| Error `1004` (chunk not loaded) | Retry with `loadChunks: true`, or move closer. |
| Error `1006` (script error) | The message has the line number; for `const` errors use `let`. |
| Screenshots show nothing far away | Only chunks within your render distance are drawn. |
| "screenshots need the game client" | You are on a dedicated server; use `get_layer_slices` instead. |
| The agent "could not reach" something | It cannot swim or build bridges; move it closer with `agent_goto` (`dig: true`), or pick another target. |
| "port already in use" in the log | Another world or server is using 25580; change `port` (and `MCMCP_URL`). |

---

## Limitations

- Minecraft 1.21.1 (Fabric) only.
- Undo history is kept in memory only; it is cleared when the world/server stops.
- Blueprints and schematics carry blocks but not block entity contents (chest items, sign text).
- Script engine: Rhino (ES5 plus part of ES6, see above). It has no memory limit.
- The agent does not swim, fight, eat, smelt, or build bridges/pillars to cross gaps.
- Previews are sent to players online at the time; players who join later see the real world.

---

## For developers

```bash
cd mod && ./gradlew build          # build the mod
cd mod && ./gradlew runClient      # start the game with the mod (and WorldEdit) in dev
cd mod && ./gradlew runGametest    # 32 in-game tests (building, undo, scripts, blueprints,
                                   # previews, WorldEdit, the survival agent, site analysis
                                   # and building plans)
cd bridge && cargo test            # 18 bridge tests (against a fake mod)
```

Design notes are in [`docs/decisions.md`](decisions.md), the manual test checklist is in
[`docs/manual-test.md`](manual-test.md), and the project plan is in [`PLAN.md`](../PLAN.md).
