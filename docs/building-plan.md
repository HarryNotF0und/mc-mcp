# Building plans (`mcmcp.building/1`)

A building plan describes a whole building as components with stable ids. The mod compiles it
deterministically into blocks, validates it, and places it through the normal job queue (one
undoable change). Use it with `validate_building`, `preview` and `place_blueprint`
(`"building": {...}`), after `analyze_build_site` has suggested an `origin` and `facing`.

## Local coordinates

Everything inside the plan uses the building's own frame:

- **x**: from the **left** wall (`0`) to the **right** wall (`width - 1`), as seen from outside
  looking at the front.
- **z**: from the **back** wall (`0`) to the **front** wall (`depth - 1`).
- **y**: up; `0` is the ground floor's floor layer, so you stand at `y = 1` on the ground floor.

The frame is rotated so the front faces `facing`. `origin` is the world position of the rotated
footprint's minimum corner (smallest x and z) at `y = 0`. `analyze_build_site` returns exactly this
`origin` and `facing`.

Exterior walls are the footprint's edge cells; the interior is `x = 1 .. width-2`,
`z = 1 .. depth-2`. Corners are posts (frame material).

## Fields

| Field | Meaning |
|---|---|
| `format` | `"mcmcp.building/1"` |
| `name` | Name (used in history and descriptions) |
| `origin` | `[x, y, z]` world position, see above |
| `facing` | `north` / `south` / `east` / `west`: the side with the entrance |
| `footprint` | `[width, depth]`, 3–48 each |
| `style` | Materials, see below (all optional) |
| `floors` | `[{ "id", "height" }]`, bottom first, 1–6 floors; `height` 3–12 counts the floor layer (4 gives 3 blocks of headroom) |
| `rooms` | `[{ "id", "floor", "function", "min": [x, z], "max": [x, z] }]`: logical rooms, used for furnishing and reachability checks |
| `partitions` | `[{ "id", "floor", "from": [x, z], "to": [x, z] }]`: straight interior walls (inside the interior) |
| `openings` | doors, windows and arches, see below |
| `stairs` | `[{ "id", "from": floorId, "start": [x, z], "direction", "width": 1-2 }]` |
| `roof` | `{ "id", "type": "gable" \| "hip" \| "flat" \| "none", "ridge": "width" \| "depth", "overhang": 0-2 }` |
| `components` | towers: `{ "id", "type": "tower", "at": [x, z], "radius", "height", "top": "battlement" \| "cone" \| "flat" }` |
| `outdoor` | `{ "id", "type": "path" \| "fence" \| "lanterns", "length", "margin" }` |
| `terrain` | `{ "margin": 0-6, "maxFoundationDepth": 1-32 }` |
| `furnish` | `true` (default): furniture and lights by room function |
| `replaceExisting` | `false` (default): replacing existing built blocks is an error |
| `patches` | `[{ "id", "component", "blocks": [[x, y, z, "block"], ...] }]`: raw block edits in local coordinates, applied last and attributed to `component` |

### Openings

```json
{ "id": "front-door", "type": "door", "floor": "ground", "wall": "front", "at": 5 }
{ "id": "w1", "type": "window", "floor": "upper", "wall": "back", "at": 2, "width": 2, "height": 2, "sill": 1 }
{ "id": "porch", "type": "arch", "floor": "ground", "wall": "left", "at": 3, "width": 3, "height": 3 }
{ "id": "inner-door", "type": "door", "floor": "upper", "partition": "divider", "at": 3 }
```

- `wall`: `front` / `back` / `left` / `right`; `at` is the local **x** of the first column for the
  front and back walls and the local **z** for the left and right walls. Openings must avoid the
  corner posts: `1 <= at` and `at + width - 1 <= length - 2`.
- `partition` instead of `wall`: `at` counts from the partition's start.
- Doors are 1 or 2 wide (double doors) and 2 high, and face into the building. Windows default
  to 2 × 2 with a sill of 1; `sill + height` must fit under the ceiling. Arches default to 3 × 3
  and get rounded top corners.

### Stairs

A straight run of `height` steps (the lower floor's height) climbing in `direction`
(`front` / `back` / `left` / `right`, the direction you walk while going up) from `start` on the
lower floor. Example: 4-high floor, start `[1, 6]`, direction `back` → steps at
z = 6, 5, 4, 3 with the last step level with the upper floor. The mod fills under the steps and
opens the upper floor above them for headroom. Leave the landing cell after the last step free.

### Styles

| Key | Default | Must be |
|---|---|---|
| `foundation` | `minecraft:cobblestone` | |
| `floor` | `minecraft:spruce_planks` | |
| `wall` | `minecraft:oak_planks` | |
| `frame` | `minecraft:oak_log` (corner posts, beams at every floor) | |
| `roofStairs` | `minecraft:dark_oak_stairs` | stairs |
| `roofSlab` | `minecraft:dark_oak_slab` | slab |
| `roofBlock` | `minecraft:dark_oak_planks` (tower cones) | |
| `stairs` | `minecraft:spruce_stairs` | stairs |
| `window` | `minecraft:glass_pane` | |
| `door` | `minecraft:spruce_door` | door |
| `ground` | `minecraft:grass_block` (terrace) | |
| `path` | `minecraft:dirt_path` | |
| `light` | `minecraft:lantern` | |
| `fence` / `gate` | `minecraft:oak_fence` / `minecraft:oak_fence_gate` | |
| `tower` | `minecraft:stone_bricks` | |
| `trim` | `minecraft:stone_brick_stairs` (arch corners) | stairs |

### Room functions

`bedroom` (bed), `kitchen` (furnace, crafting table, smoker), `storage` (chest, barrel),
`library` (bookshelves, lectern), `workshop` (crafting and smithing table, anvil), anything else
(just a light). Every room gets a lantern. Furniture never goes next to doors or stairs.

## Terrain

- The foundation fills down under the footprint (and towers) to solid ground, up to
  `maxFoundationDepth` (a warning beyond that).
- A **terrace** `terrain.margin` blocks wide around the building is levelled to the floor
  (ground block on top, dirt below) so doors open onto flat ground.
- Inside the building, and above the terrace, terrain and plants are cleared.

## Validation

`validate_building` (and every preview or placement of a plan) reports:

| Code | Meaning |
|---|---|
| `REGION` | Outside the allowed building regions |
| `COLLISION` | Would replace existing built (non-natural) blocks; set `replaceExisting` to allow |
| `FLOATING` | Blocks not connected (face to face) to the ground or the rest of the building |
| `INCOMPLETE_WALL` | A gap in an exterior wall that is not a declared opening |
| `ROOF_GAP` | A footprint column with no roof above it |
| `ROOF_ORIENTATION` | A roof stair facing away from the ridge |
| `DOOR_ORIENTATION` | A door missing its upper half, facing along the wall, or not set in a wall |
| `STAIRS_ORIENTATION` | A step not facing its stairs' direction |
| `FLOOR_INCOMPLETE` | An upper floor covering less than 75 % of the interior |
| `MISSING_ENTRANCE` | No ground-floor exterior door/arch, or nowhere to stand in front of any |
| `UNREACHABLE_FLOOR` / `MISSING_STAIRS` / `STAIRS_BLOCKED` | A floor that cannot be walked to |
| `UNREACHABLE_ROOM` | A room that cannot be walked to from the entrance |
| `OPENING_BOUNDS`, `STAIRS_BOUNDS`, `PARTITION_BOUNDS`, `PATH_NO_DOOR` | Geometry that does not fit |

Warnings: `FOUNDATION_DEEP`, `ENTRANCE_BLOCKED` (one of several entrances).

Reachability is checked by walking: 1-block steps (jumping needs headroom; stairs and slabs
don't), doors and gates are passable, the start is the ground in front of each entrance.
Every error lists the components involved and world positions, so a fix can target one
component (or a `patch`) instead of redesigning the building.

`place_blueprint` refuses plans with errors (code `1007`) unless `ignoreValidation: true`;
`preview` always works and includes the validation, so a broken design can be looked at.

## Example

[`examples/buildings/two-story-house.json`](../examples/buildings/two-story-house.json): an
11 × 9 two-story house with a hall and kitchen downstairs, a bedroom and a study upstairs
(partition with a door), straight stairs, a gable roof, windows on every side, a path, lanterns
and a fence.

## Requirements (checked by `inspect_building`)

Optional `"requirements"` object; each entry is reported as pass/fail with the measured value:

| Key | Passes when |
|---|---|
| `symmetricFront: true` | front facade mirror symmetry ≥ 0.9 |
| `roofSymmetric: true` | roof columns mirror exactly and the overhang is the same on both sides |
| `alignedWindows: true` | ≥ 75% of windows line up with a window on the floor above/below |
| `minWindowsPerFloor: n` | every floor has at least n windows |
| `maxHeight: n` | total height (with roof) ≤ n blocks |
| `minWindowRatio: r` | every facade's glass/wall ratio ≥ r |

## Revising (`revise_building`)

Edit components by id instead of resending the plan: `{"op":"update","id":"upper-front-left","set":{"width":3}}`,
`{"op":"remove","id":"yard-fence"}`, `{"op":"add","to":"openings","value":{...}}`,
`{"op":"set","field":"footprint","value":[13,9]}`. Only blocks that differ are changed.
