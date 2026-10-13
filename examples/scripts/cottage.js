// Small timber cottage, 9 x 7, with a gable (pitched) roof of oak stairs, a door and windows.
// origin = north-west corner of the floor (the floor itself is at y = 0).
// Run: run_script { "origin": [x, y, z], "source": <this file> }

const LX = 9, LZ = 7;   // footprint: x = 0 .. LX - 1, z = 0 .. LZ - 1
const WALL_H = 4;       // wall layers above the floor: y = 1 .. WALL_H
const PLANKS = "minecraft:oak_planks";

// Floor, cleared interior, walls with log corners.
b.fill(0, 0, 0, LX - 1, 0, LZ - 1, "minecraft:cobblestone");
b.fill(-1, 1, -1, LX, WALL_H + 5, LZ, "minecraft:air");          // clear the site incl. roof overhang
b.fill(0, 1, 0, LX - 1, WALL_H, LZ - 1, PLANKS);
b.fill(1, 1, 1, LX - 2, WALL_H, LZ - 2, "minecraft:air");         // walls only, no ceiling
for (let p of [[0, 0], [LX - 1, 0], [0, LZ - 1], [LX - 1, LZ - 1]]) {
  let x = p[0], z = p[1];
  b.fill(x, 1, z, x, WALL_H, z, "minecraft:oak_log");
}
// Ceiling beams along the top of the long walls.
b.fill(0, WALL_H, 0, LX - 1, WALL_H, 0, "minecraft:oak_log[axis=x]");
b.fill(0, WALL_H, LZ - 1, LX - 1, WALL_H, LZ - 1, "minecraft:oak_log[axis=x]");

// Door in the middle of the south wall (both halves).
const DX = Math.floor(LX / 2);
b.set(DX, 1, LZ - 1, "minecraft:oak_door[facing=north,half=lower,hinge=left]");
b.set(DX, 2, LZ - 1, "minecraft:oak_door[facing=north,half=upper,hinge=left]");
// Windows.
for (let x of [2, LX - 3]) {
  b.set(x, 2, 0, "minecraft:glass_pane");
  b.set(x, 2, LZ - 1, "minecraft:glass_pane");
}
b.set(0, 2, Math.floor(LZ / 2), "minecraft:glass_pane");
b.set(LX - 1, 2, Math.floor(LZ / 2), "minecraft:glass_pane");

// Gable roof: ridge along x. Each layer moves both slopes one block inwards and one up,
// with a one-block overhang on every side.
const z0 = -1, z1 = LZ;            // outermost roof rows (overhang)
for (let i = 0; ; i++) {
  let y = WALL_H + 1 + i, north = z0 + i, south = z1 - i;
  if (north > south) break;
  for (let x = -1; x <= LX; x++) {
    if (north === south) {
      b.set(x, y, north, "minecraft:oak_slab[type=bottom]");      // ridge
    } else {
      // Stairs face the direction you walk to go up the slope: towards the ridge.
      b.set(x, y, north, "minecraft:oak_stairs[facing=south]");
      b.set(x, y, south, "minecraft:oak_stairs[facing=north]");
    }
  }
  // Gable walls fill the triangle under the roof at both ends.
  for (let z = north + 1; z < south; z++) {
    if (z >= 0 && z < LZ) {
      b.set(0, y, z, PLANKS);
      b.set(LX - 1, y, z, PLANKS);
    }
  }
}

// A lantern inside.
b.set(1, 1, 1, "minecraft:lantern[hanging=false]");
b.log("cottage: " + LX + " x " + LZ);
