// Round stone tower: diameter 15, 30 blocks tall, spiral staircase up to a crenellated roof deck.
// origin = centre of the ground floor (the block you stand on is y = -1).
// Run: run_script { "origin": [x, y, z], "source": <this file> }

const R = 7;            // radius -> diameter 2 * R + 1 = 15
const H = 30;           // wall height (y = 0 .. H - 1)
const DECK = H - 3;     // roof deck level; parapet above it
const WALL = "minecraft:stone_bricks";
const STEPS_PER_TURN = 16;

// Foundation and shell.
b.cylinder(0, -1, 0, R, 1, WALL);
b.cylinder(0, 0, 0, R - 1, H, "minecraft:air");
b.cylinder(0, 0, 0, R, H, WALL, true);
b.cylinder(0, 0, 0, 1, DECK, "minecraft:polished_andesite"); // central column, 3 wide

// The cardinal direction closest to walking counter-clockwise at angle a (radians, from +x towards +z).
function facingAt(a) {
  let dx = -Math.sin(a), dz = Math.cos(a);
  if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? "east" : "west";
  return dz > 0 ? "south" : "north";
}

// Each step is a wedge between the column and the wall, one block higher than the last.
function wedge(step, y, block) {
  let a0 = (step / STEPS_PER_TURN) * 2 * Math.PI;
  let a1 = ((step + 1) / STEPS_PER_TURN) * 2 * Math.PI;
  for (let a = a0; a < a1; a += 0.02) {
    for (let r = 2; r <= R - 1; r += 0.5) {
      let x = Math.round(r * Math.cos(a)), z = Math.round(r * Math.sin(a));
      if (x * x + z * z <= 2) continue; // keep the column intact
      b.set(x, y, z, block(a));
    }
  }
}

for (let step = 0; step <= DECK; step++) {
  wedge(step, step, a => "minecraft:stone_brick_stairs[facing=" + facingAt((step + 0.5) / STEPS_PER_TURN * 2 * Math.PI) + "]");
}

// Roof deck with an opening above the last few steps for headroom.
const deck = [];
for (let x = -(R - 1); x <= R - 1; x++) for (let z = -(R - 1); z <= R - 1; z++) {
  if (x * x + z * z < (R - 0.5) * (R - 0.5)) deck.push([x, z]);
}
for (let p of deck) {
  let x = p[0], z = p[1];
  if (b.get(x, DECK, z).indexOf("stairs") < 0) b.set(x, DECK, z, "minecraft:spruce_planks");
}
for (let step = DECK - 4; step < DECK; step++) wedge(step, DECK, () => "minecraft:air");
// Re-place the top step, which shares the deck layer.
wedge(DECK, DECK, a => "minecraft:stone_brick_stairs[facing=" + facingAt((DECK + 0.5) / STEPS_PER_TURN * 2 * Math.PI) + "]");

// Crenellations: every other block of the top ring is lowered by one.
for (let i = 0; i < 64; i++) {
  let a = i / 64 * 2 * Math.PI;
  let x = Math.round(R * Math.cos(a)), z = Math.round(R * Math.sin(a));
  if ((Math.floor(i / 4)) % 2 === 1) b.set(x, H - 1, z, "minecraft:air");
}

// Door on the south side, arrow-slit windows every 6 blocks on all four sides.
b.fill(0, 0, R, 0, 1, R, "minecraft:air");
for (let y = 4; y < DECK - 1; y += 6) {
  for (let p of [[R, 0], [-R, 0], [0, -R], [0, R]]) {
    let x = p[0], z = p[1];
    b.set(x, y, z, "minecraft:glass_pane");
    b.set(x, y + 1, z, "minecraft:glass_pane");
  }
}
// Lanterns on the deck.
// (The deck opening is on the west side, so no lantern at [-4, -4].)
for (let p of [[4, 4], [-4, 4], [4, -4]]) { let x = p[0], z = p[1]; b.set(x, DECK + 1, z, "minecraft:lantern"); }

b.log("tower: diameter " + (2 * R + 1) + ", height " + H + ", " + (DECK + 1) + " steps");
