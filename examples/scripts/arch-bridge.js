// Stone arch bridge spanning 31 blocks along the x axis, 5 wide, with railings and lamp posts.
// origin = centre of the span at the height of the arch's feet (e.g. the water surface).
// Run: run_script { "origin": [x, y, z], "source": <this file> }

const HALF = 15;        // half span: the bridge runs from x = -HALF to x = HALF
const RISE = 7;         // height of the arch's crown above its feet
const DECK = RISE + 1;  // deck level
const W = 2;            // half width: deck from z = -W to z = W
const ARCH = "minecraft:stone_bricks";

// Height of the arch's underside at x: an ellipse through (-HALF, 0), (0, RISE), (HALF, 0).
function archY(x) {
  let t = x / HALF;
  return Math.round(RISE * Math.sqrt(Math.max(0, 1 - t * t)));
}

for (let x = -HALF; x <= HALF; x++) {
  let y0 = archY(x);
  for (let z = -W; z <= W; z++) {
    // Arch ring: the underside plus one block above it.
    b.set(x, y0, z, ARCH);
    b.set(x, Math.min(y0 + 1, DECK - 1), z, ARCH);
    // Spandrel fill between the ring and the deck on the outer faces only; hollow inside.
    if (Math.abs(z) === W) {
      for (let y = y0 + 1; y < DECK; y++) b.set(x, y, z, "minecraft:stone_bricks");
    }
    b.set(x, DECK, z, "minecraft:smooth_stone_slab[type=double]");
  }
  // Railings.
  b.set(x, DECK + 1, -W, "minecraft:stone_brick_wall");
  b.set(x, DECK + 1, W, "minecraft:stone_brick_wall");
}

// Abutments down to the feet and a little beyond the span, plus approach steps.
for (let side of [-1, 1]) {
  let xEnd = side * HALF;
  b.fill(xEnd, -3, -W, xEnd + side * 2, DECK, W, ARCH);
  for (let s = 0; s < DECK; s++) {
    let x = xEnd + side * (3 + s);
    let facing = side < 0 ? "east" : "west"; // climbing towards the bridge
    for (let z = -W; z <= W; z++) {
      b.set(x, DECK - 1 - s, z, "minecraft:stone_brick_stairs[facing=" + facing + "]");
    }
    if (DECK - 2 - s >= -3) b.fill(x, -3, -W, x, DECK - 2 - s, W, ARCH); // solid ramp underneath
  }
  // Lamp posts at the four corners of the span.
  for (let z of [-W, W]) {
    b.set(xEnd, DECK + 1, z, "minecraft:stone_bricks");
    b.set(xEnd, DECK + 2, z, "minecraft:stone_brick_wall");
    b.set(xEnd, DECK + 3, z, "minecraft:lantern");
  }
}

b.log("bridge: span " + (2 * HALF + 1) + ", deck at y+" + DECK);
