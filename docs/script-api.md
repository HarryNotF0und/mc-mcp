# Build script API

Build scripts are JavaScript (ES6, Mozilla Rhino) run by `run_script` / `build.runScript`.
A script does not modify the world directly: it describes blocks with the global object `b`,
and after it finishes the result is placed as **one undoable change**. With `dryRun: true`
nothing is placed and you get statistics instead.

## Language: what works in this engine

Rhino supports most of ES5 and part of ES6. Stick to this subset:

- **Use `let` or `var` inside loops, never `const`.** In Rhino a `const` in a loop body keeps the
  value from the first iteration; scripts that do this are rejected before they run.
  `const` at the top level or inside a function body (outside loops) is fine.
- `for (let v of array)` works; `for (const v of ...)` and destructuring in the loop header
  (`for (let [x, z] of ...)`) do not. Write `for (let p of pts) { let x = p[0], z = p[1]; ... }`.
- Works: arrow functions, template literals, default parameters, `let [a, b] = ...`,
  `const {x, y} = ...`, `Map`, `Set`, `Array.prototype.includes`, `Math`, `JSON`.
- Does not work: `class`, spread (`...args`), `async`/`await`.
- Closures created in a loop share the loop variable (`let` is not per-iteration).

## Coordinates

- Every `b` coordinate is **relative to `b.origin`** (the `origin` passed to the call).
  `b.set(0, 0, 0, ...)` places at the origin itself.
- `y` is up. North is `-z`, south is `+z`, west is `-x`, east is `+x`.
- Non-integer coordinates are floored (`1.7` -> `1`, `-0.5` -> `-1`).
- Blocks use game syntax: `"minecraft:stone_bricks"`, `"minecraft:oak_stairs[facing=north,half=top]"`.
  Unspecified properties take their default values.
- If the server has WorldEdit installed, every block argument can also be a WorldEdit pattern,
  e.g. `"60%minecraft:stone_bricks,25%minecraft:mossy_stone_bricks,15%minecraft:cracked_stone_bricks"`
  for weathered walls. `get_world_info` reports `worldEdit` when it is available.

## Functions

| Call | Effect |
|---|---|
| `b.set(x, y, z, block)` | Set one block. Setting a position again overrides it. |
| `b.get(x, y, z)` | The block at that position as a string, e.g. `"minecraft:grass_block[snowy=false]"`. Returns what the script has set there so far, otherwise what is in the world. |
| `b.fill(x1, y1, z1, x2, y2, z2, block, mode?)` | Fill a box, corners inclusive. `mode`: `"solid"` (default), `"hollow"` (shell + air inside), `"outline"` (shell only). |
| `b.line(x1, y1, z1, x2, y2, z2, block)` | A straight line of blocks between two points, both ends included. |
| `b.cylinder(x, y, z, radius, height, block, hollow?)` | Vertical cylinder. `(x, y, z)` is the centre of the bottom layer; it extends `height` layers up. `hollow = true` gives a 1-block wall. |
| `b.sphere(x, y, z, radius, block, hollow?)` | Sphere centred on `(x, y, z)`. `hollow = true` gives a 1-block shell. |
| `b.log(...values)` | Add a line to the output returned to you (at most 200 lines). |
| `b.origin` | `{x, y, z}`: the world coordinates of the origin (read-only). |

Circles and spheres contain the points whose distance from the centre is less than
`radius + 0.5`, so a whole-number radius `r` gives a diameter of `2r + 1`
(radius 7 -> diameter 15).

## Limits

- Time limit `scriptTimeoutMs` (default 5000 ms), counted while the script runs. Exceeding it
  stops the script (it cannot be caught with `try/catch`) and reports the line it was on.
- At most `maxVolumePerOp` (default 262144) distinct blocks per script.
- No access to Java, files, network or the host: only standard JavaScript (`Math`, `Array`,
  `JSON`, ...) and `b`.
- Reading with `b.get` in a chunk that is not loaded fails unless the call passes `loadChunks: true`.
- The result goes through the same checks as other writes: allowed regions (1001), volume (1002),
  blacklisted blocks (1003).

Errors come back as code `1006` with `data.line` and `data.message` (and any logs so far).

## Placement semantics

As with every write: no block updates (no falling sand, no water flow, no redstone), and blocks
outside the result never change. Fences, walls, panes and stairs adapt to the blocks around
them. Doors, beds and tall plants need both halves set explicitly. Leaves you place should be
`[persistent=true]` or they may decay.

## Examples

A ring of stone bricks with a torch on top of every fourth block:

```js
for (let a = 0; a < 360; a += 10) {
  const x = Math.round(8 * Math.cos(a * Math.PI / 180));
  const z = Math.round(8 * Math.sin(a * Math.PI / 180));
  b.set(x, 0, z, "minecraft:stone_bricks");
  if (a % 40 === 0) b.set(x, 1, z, "minecraft:torch");
}
```

A floor that follows the terrain (reads the world):

```js
for (let x = 0; x < 10; x++) for (let z = 0; z < 10; z++) {
  let y = 10;
  while (y > -10 && b.get(x, y, z) === "minecraft:air") y--;
  b.set(x, y, z, "minecraft:oak_planks");
}
```

Stairs facing outwards around a square: `facing` is the direction the player faces when walking
up, so stairs on the north side of a roof use `facing=south`.

More complete examples are in `examples/scripts/` (`tower.js`, `arch-bridge.js`, `cottage.js`).
