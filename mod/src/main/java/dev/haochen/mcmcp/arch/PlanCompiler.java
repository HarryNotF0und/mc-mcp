package dev.haochen.mcmcp.arch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.api.Json;
import dev.haochen.mcmcp.arch.BuildingGrid.Role;
import dev.haochen.mcmcp.arch.BuildingPlan.Floor;
import dev.haochen.mcmcp.arch.BuildingPlan.Opening;
import dev.haochen.mcmcp.arch.BuildingPlan.Side;
import dev.haochen.mcmcp.world.BlockList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link BuildingPlan} into a {@link BuildingGrid} (deterministic, reads the world only
 * for terrain: foundation depth and what to clear), and the grid into world blocks.
 * Problems found while generating (an opening outside its wall, stairs leaving the interior)
 * are collected as validation errors instead of exceptions.
 */
public final class PlanCompiler {
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

	private final BuildingPlan p;
	private final BuildingGrid g;
	private final int W, D, H;
	final List<Issue> issues = new ArrayList<>();
	/** Columns (local x,z) that need foundation/fill down to the ground, with the fill material. */
	private final Map<Long, BlockState> fillColumns = new LinkedHashMap<>();
	/** First local y to fill downwards from, per fill column (below the column's surface block). */
	private final Map<Long, Integer> fillFrom = new LinkedHashMap<>();
	private it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<WorldCell> worldCells;
	int foundationMaxDepth;

	public int foundationDepth() {
		return foundationMaxDepth;
	}
	int roofTop;

	public record Issue(boolean error, String code, String message, List<String> components, List<int[]> local) {
	}

	private PlanCompiler(BuildingPlan plan, ServerLevel level, Long2ObjectMap<BlockState> originals) {
		this.p = plan;
		this.g = new BuildingGrid(plan, level, originals);
		this.W = plan.width;
		this.D = plan.depth;
		this.H = plan.wallTop();
	}

	public static PlanCompiler compile(BuildingPlan plan, ServerLevel level) {
		return compile(plan, level, Long2ObjectMaps.emptyMap());
	}

	/** Revisions compile terrain against the pre-building world, rather than their own foundations. */
	public static PlanCompiler compile(BuildingPlan plan, ServerLevel level, Long2ObjectMap<BlockState> originals) {
		PlanCompiler c = new PlanCompiler(plan, level, originals);
		c.run();
		return c;
	}

	public BuildingGrid grid() {
		return g;
	}

	private void run() {
		floors();
		walls();
		partitions();
		openings();
		stairs();
		roof();
		towers();
		outdoor();
		if (p.furnish) {
			furnish();
		}
		terrain();
		patches();
	}

	// ---------------------------------------------------------------- helpers

	private BlockState style(String key) {
		return p.style.get(key);
	}

	private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> prop, T value) {
		return s.hasProperty(prop) ? s.setValue(prop, value) : s;
	}

	private static BlockState stair(BlockState s, Direction facing, Half half) {
		return with(with(s, StairBlock.FACING, facing), StairBlock.HALF, half);
	}

	private static BlockState axis(BlockState s, Direction.Axis axis) {
		return with(s, RotatedPillarBlock.AXIS, axis);
	}

	private void issue(boolean error, String code, String message, String component, int[]... local) {
		issues.add(new Issue(error, code, message, List.of(component), List.of(local)));
	}

	private boolean onPerimeter(int x, int z) {
		return x == 0 || z == 0 || x == W - 1 || z == D - 1;
	}

	private static Direction dir(Side s) {
		return s.out;
	}

	/** Cell coordinates of the i-th column of an opening on an exterior wall. */
	private int[] wallCell(Side side, int along) {
		return switch (side) {
			case FRONT -> new int[]{along, D - 1};
			case BACK -> new int[]{along, 0};
			case LEFT -> new int[]{0, along};
			case RIGHT -> new int[]{W - 1, along};
		};
	}

	private static int[] at3(int[] xz, int y) {
		return new int[]{xz[0], y, xz[1]};
	}

	private int wallLength(Side side) {
		return side == Side.FRONT || side == Side.BACK ? W : D;
	}

	// ---------------------------------------------------------------- components

	private void floors() {
		for (Floor f : p.floors) {
			for (int x = 1; x < W - 1; x++) {
				for (int z = 1; z < D - 1; z++) {
					g.set(x, f.base(), z, style("floor"), f.id(), Role.FLOOR);
				}
			}
		}
		// Ceiling of the top floor (the attic floor under the roof).
		for (int x = 1; x < W - 1; x++) {
			for (int z = 1; z < D - 1; z++) {
				g.set(x, H, z, style("floor"), "ceiling", Role.FLOOR);
			}
		}
	}

	private void walls() {
		List<Integer> beams = new ArrayList<>();
		for (Floor f : p.floors) {
			beams.add(f.base());
		}
		beams.add(H);
		for (int y = 0; y <= H; y++) {
			for (int x = 0; x < W; x++) {
				for (int z = 0; z < D; z++) {
					if (!onPerimeter(x, z)) {
						continue;
					}
					boolean corner = (x == 0 || x == W - 1) && (z == 0 || z == D - 1);
					String comp = corner ? "frame" : z == D - 1 ? "wall-front" : z == 0 ? "wall-back" : x == 0 ? "wall-left" : "wall-right";
					if (corner) {
						g.set(x, y, z, axis(style("frame"), Direction.Axis.Y), comp, Role.FRAME);
					} else if (beams.contains(y)) {
						Direction.Axis along = z == 0 || z == D - 1 ? Direction.Axis.X : Direction.Axis.Z;
						g.set(x, y, z, axis(style("frame"), along), comp, Role.FRAME);
					} else {
						g.set(x, y, z, style("wall"), comp, Role.WALL);
					}
				}
			}
		}
	}

	private void partitions() {
		for (BuildingPlan.Partition pt : p.partitions) {
			if (pt.x1() < 1 || pt.z1() < 1 || pt.x2() > W - 2 || pt.z2() > D - 2) {
				issue(true, "PARTITION_BOUNDS", "partition " + pt.id() + " must lie inside the interior (x 1.." + (W - 2) + ", z 1.." + (D - 2) + ")",
						pt.id(), new int[]{pt.x1(), pt.floor().feet(), pt.z1()});
				continue;
			}
			for (int x = pt.x1(); x <= pt.x2(); x++) {
				for (int z = pt.z1(); z <= pt.z2(); z++) {
					for (int y = pt.floor().base() + 1; y < pt.floor().base() + pt.floor().height(); y++) {
						g.set(x, y, z, style("wall"), pt.id(), Role.WALL);
					}
				}
			}
		}
	}

	private void openings() {
		for (Opening o : p.openings) {
			int y0 = o.floor().base() + 1 + o.sill();
			if (o.partition() != null) {
				BuildingPlan.Partition pt = o.partition();
				int length = pt.alongX() ? pt.x2() - pt.x1() + 1 : pt.z2() - pt.z1() + 1;
				if (o.at() < 0 || o.at() + o.width() > length || pt.floor() != o.floor()) {
					issue(true, "OPENING_BOUNDS", "opening " + o.id() + " does not fit in partition " + pt.id() + " (length " + length + ", same floor)",
							o.id(), new int[]{pt.x1(), y0, pt.z1()});
					continue;
				}
				for (int i = 0; i < o.width(); i++) {
					int x = pt.alongX() ? pt.x1() + o.at() + i : pt.x1();
					int z = pt.alongX() ? pt.z1() : pt.z1() + o.at() + i;
					Direction through = pt.alongX() ? Direction.SOUTH : Direction.EAST;
					fillOpening(o, x, z, y0, through, i);
				}
				continue;
			}
			int len = wallLength(o.wall());
			if (o.at() < 1 || o.at() + o.width() - 1 > len - 2) {
				issue(true, "OPENING_BOUNDS", "opening " + o.id() + " on the " + o.wall().name().toLowerCase()
						+ " wall must have at >= 1 and at + width - 1 <= " + (len - 2) + " (corners are posts)", o.id(),
						at3(wallCell(o.wall(), Math.max(0, Math.min(len - 1, o.at()))), y0));
				continue;
			}
			for (int i = 0; i < o.width(); i++) {
				int[] c = wallCell(o.wall(), o.at() + i);
				fillOpening(o, c[0], c[1], y0, o.wall().out.getOpposite(), i);
			}
		}
	}

	/** {@code inward}: for exterior walls the direction into the building; doors face it. */
	private void fillOpening(Opening o, int x, int z, int y0, Direction inward, int column) {
		for (int dy = 0; dy < o.height(); dy++) {
			int y = y0 + dy;
			g.openingCells.add(BuildingGrid.key(x, y, z));
			BlockState s = switch (o.type()) {
				case "door" -> {
					BlockState d = with(style("door"), DoorBlock.FACING, inward);
					d = with(d, DoorBlock.HALF, dy == 0 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
					// Double doors: hinges on the outer sides so the pair opens from the middle.
					yield with(d, DoorBlock.HINGE, o.width() == 2 && column == 1 ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT);
				}
				case "window" -> style("window");
				default -> {
					// Arch: open, with upside-down stairs rounding the two top corners.
					boolean top = dy == o.height() - 1 && o.width() >= 3 && o.height() >= 3;
					if (top && (column == 0 || column == o.width() - 1)) {
						Direction along = inward.getClockWise();
						yield stair(style("trim"), column == 0 ? along.getOpposite() : along, Half.TOP);
					}
					yield AIR;
				}
			};
			g.set(x, y, z, s, o.id(), Role.OPENING);
		}
	}

	private void stairs() {
		for (BuildingPlan.Stairs s : p.stairs) {
			Direction d = dir(s.direction());
			Direction lateral = d.getClockWise();
			int h = s.from().height();
			boolean ok = true;
			for (int k = 0; k < h && ok; k++) {
				for (int w = 0; w < s.width(); w++) {
					int x = s.x() + d.getStepX() * k + lateral.getStepX() * w;
					int z = s.z() + d.getStepZ() * k + lateral.getStepZ() * w;
					if (x < 1 || z < 1 || x > W - 2 || z > D - 2) {
						issue(true, "STAIRS_BOUNDS", "stairs " + s.id() + " run " + h + " steps " + s.direction().name().toLowerCase()
								+ " from " + s.x() + "," + s.z() + " and leave the interior at " + x + "," + z, s.id(),
								new int[]{x, s.from().base() + 1 + k, z});
						ok = false;
						break;
					}
				}
			}
			if (!ok) {
				continue;
			}
			for (int k = 0; k < h; k++) {
				for (int w = 0; w < s.width(); w++) {
					int x = s.x() + d.getStepX() * k + lateral.getStepX() * w;
					int z = s.z() + d.getStepZ() * k + lateral.getStepZ() * w;
					int y = s.from().base() + 1 + k;
					g.set(x, y, z, stair(style("stairs"), d, Half.BOTTOM), s.id(), Role.STAIRS);
					for (int sy = s.from().base() + 1; sy < y; sy++) {
						g.set(x, sy, z, style("floor"), s.id(), Role.STAIRS); // solid underneath
					}
					// Headroom: open the upper floor above the steps that pass under it.
					if (k >= h - 3 && k <= h - 2) {
						g.set(x, s.to().base(), z, AIR, s.id(), Role.CLEAR);
					}
				}
			}
		}
	}

	private void roof() {
		BuildingPlan.Roof r = p.roof;
		BlockState stairs = style("roofStairs"), slab = with(style("roofSlab"), SlabBlock.TYPE, SlabType.BOTTOM);
		int o = r.overhang();
		roofTop = H;
		switch (r.type()) {
			case "flat" -> {
				for (int x = -o; x < W + o; x++) {
					for (int z = -o; z < D + o; z++) {
						boolean edge = x == 0 || z == 0 || x == W - 1 || z == D - 1;
						boolean inside = x >= 0 && z >= 0 && x < W && z < D;
						if (inside && edge) {
							g.set(x, H + 1, z, style("wall"), r.id(), Role.ROOF); // parapet
						} else {
							g.set(x, H + 1, z, slab, r.id(), Role.ROOF);
						}
					}
				}
				roofTop = H + 1;
			}
			case "gable" -> {
				boolean alongX = r.ridgeAlongX();
				int across = alongX ? D : W, length = alongX ? W : D;
				for (int i = 0; ; i++) {
					int a = -o + i, b = across - 1 + o - i, y = H + 1 + i;
					if (a > b) {
						break;
					}
					roofTop = y;
					for (int l = -o; l < length + o; l++) {
						if (a == b) {
							put(alongX, l, y, a, slab, r.id(), Role.ROOF);
						} else {
							put(alongX, l, y, a, stair(stairs, alongX ? Direction.SOUTH : Direction.EAST, Half.BOTTOM), r.id(), Role.ROOF);
							put(alongX, l, y, b, stair(stairs, alongX ? Direction.NORTH : Direction.WEST, Half.BOTTOM), r.id(), Role.ROOF);
						}
					}
					// Gable ends: fill the triangle under the roof rows.
					for (int c = Math.max(0, a + 1); c <= Math.min(across - 1, b - 1); c++) {
						put(alongX, 0, y, c, style("wall"), "gable", Role.WALL);
						put(alongX, length - 1, y, c, style("wall"), "gable", Role.WALL);
					}
					if (a == b || a + 1 == b) {
						break;
					}
				}
			}
			case "hip" -> {
				for (int i = 0; ; i++) {
					int xa = -o + i, xb = W - 1 + o - i, za = -o + i, zb = D - 1 + o - i, y = H + 1 + i;
					if (xa > xb || za > zb) {
						break;
					}
					roofTop = y;
					if (xa == xb || za == zb || xa + 1 == xb && za + 1 == zb) {
						for (int x = xa; x <= xb; x++) {
							for (int z = za; z <= zb; z++) {
								g.set(x, y, z, slab, r.id(), Role.ROOF);
							}
						}
						break;
					}
					for (int x = xa; x <= xb; x++) {
						g.set(x, y, za, stair(stairs, Direction.SOUTH, Half.BOTTOM), r.id(), Role.ROOF);
						g.set(x, y, zb, stair(stairs, Direction.NORTH, Half.BOTTOM), r.id(), Role.ROOF);
					}
					for (int z = za + 1; z < zb; z++) {
						g.set(xa, y, z, stair(stairs, Direction.EAST, Half.BOTTOM), r.id(), Role.ROOF);
						g.set(xb, y, z, stair(stairs, Direction.WEST, Half.BOTTOM), r.id(), Role.ROOF);
					}
				}
			}
			default -> {
			}
		}
	}

	/** Gable helper: {@code l} runs along the ridge, {@code c} across it. */
	private void put(boolean alongX, int l, int y, int c, BlockState s, String comp, Role role) {
		if (alongX) {
			g.set(l, y, c, s, comp, role);
		} else {
			g.set(c, y, l, s, comp, role);
		}
	}

	private void towers() {
		for (BuildingPlan.Tower t : p.towers) {
			double limit = (t.radius() + 0.5) * (t.radius() + 0.5);
			for (int dx = -t.radius(); dx <= t.radius(); dx++) {
				for (int dz = -t.radius(); dz <= t.radius(); dz++) {
					if (dx * dx + dz * dz >= limit) {
						continue;
					}
					int x = t.x() + dx, z = t.z() + dz;
					boolean ring = (dx + 1) * (dx + 1) + dz * dz >= limit || (dx - 1) * (dx - 1) + dz * dz >= limit
							|| dx * dx + (dz + 1) * (dz + 1) >= limit || dx * dx + (dz - 1) * (dz - 1) >= limit;
					fillColumns.putIfAbsent(BuildingGrid.key(x, 0, z), style("foundation"));
					fillFrom.putIfAbsent(BuildingGrid.key(x, 0, z), -2);
					g.set(x, -1, z, style("foundation"), t.id(), Role.FOUNDATION);
					for (int y = 0; y < t.height(); y++) {
						if (ring) {
							g.set(x, y, z, style("tower"), t.id(), Role.TOWER);
						} else if (!inFootprint(x, z)) {
							g.setIfAbsent(x, y, z, AIR, t.id(), Role.CLEAR);
						}
					}
					int top = t.height();
					switch (t.top()) {
						case "battlement" -> {
							if (ring && Math.floorMod(dx + dz, 2) == 0) {
								g.set(x, top, z, style("tower"), t.id(), Role.TOWER);
							}
						}
						case "flat" -> g.set(x, top - 1, z, style("tower"), t.id(), Role.TOWER);
						default -> {
							// Cone: stacked shrinking discs.
							for (int layer = 0; layer <= t.radius(); layer++) {
								double r = t.radius() - layer + 0.5;
								if (dx * dx + dz * dz < r * r) {
									g.set(x, top + layer, z, style("roofBlock"), t.id(), Role.ROOF);
								}
							}
						}
					}
					roofTop = Math.max(roofTop, top + t.radius());
				}
			}
		}
	}

	/** First exterior door on the ground floor (front wall preferred), or null. */
	Opening mainDoor() {
		Opening best = null;
		for (Opening o : p.openings) {
			if (o.type().equals("door") && o.wall() != null && o.floor().index() == 0
					&& (best == null || o.wall() == Side.FRONT && best.wall() != Side.FRONT)) {
				best = o;
			}
		}
		return best;
	}

	private void outdoor() {
		Opening door = mainDoor();
		for (BuildingPlan.Outdoor od : p.outdoor) {
			switch (od.type()) {
				case "path" -> {
					if (door == null) {
						issue(true, "PATH_NO_DOOR", "outdoor path " + od.id() + " needs an exterior ground-floor door", od.id());
						continue;
					}
					Direction out = door.wall().out;
					for (int i = 0; i < door.width(); i++) {
						int[] c = wallCell(door.wall(), door.at() + i);
						for (int k = 1; k <= od.length(); k++) {
							int x = c[0] + out.getStepX() * k, z = c[1] + out.getStepZ() * k;
							g.set(x, 0, z, style("path"), od.id(), Role.OUTDOOR);
							fillColumns.putIfAbsent(BuildingGrid.key(x, 0, z), DIRT);
							fillFrom.putIfAbsent(BuildingGrid.key(x, 0, z), -1);
							for (int y = 1; y <= 3; y++) {
								g.setIfAbsent(x, y, z, AIR, od.id(), Role.CLEAR);
							}
						}
					}
				}
				case "fence" -> {
					int m = od.margin();
					for (int x = -m; x < W + m; x++) {
						for (int z = -m; z < D + m; z++) {
							if (x != -m && z != -m && x != W - 1 + m && z != D - 1 + m) {
								continue;
							}
							g.set(x, 1, z, style("fence"), od.id(), Role.OUTDOOR);
						}
					}
					if (door != null) {
						Direction out = door.wall().out;
						for (int i = 0; i < door.width(); i++) {
							int[] c = wallCell(door.wall(), door.at() + i);
							int x = c[0] + out.getStepX() * m, z = c[1] + out.getStepZ() * m;
							g.set(x, 1, z, with(style("gate"), BlockStateProperties.HORIZONTAL_FACING, out), od.id(), Role.OUTDOOR);
						}
					}
				}
				default -> {
					// Lanterns on the ground either side of the main door.
					if (door == null) {
						continue;
					}
					Direction out = door.wall().out;
					int[] first = wallCell(door.wall(), door.at() - 1), last = wallCell(door.wall(), door.at() + door.width());
					for (int[] c : new int[][]{first, last}) {
						g.set(c[0] + out.getStepX(), 1, c[1] + out.getStepZ(),
								with(style("light"), BlockStateProperties.HANGING, false), od.id(), Role.OUTDOOR);
					}
				}
			}
		}
	}

	private boolean inFootprint(int x, int z) {
		return x >= 0 && z >= 0 && x < W && z < D;
	}

	private boolean nearDoor(int x, int y, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BuildingGrid.Cell c = g.get(x + dx, y, z + dz);
				if (c != null && (c.role() == Role.OPENING || c.role() == Role.STAIRS)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean free(int x, int y, int z) {
		BuildingGrid.Cell c = g.get(x, y, z);
		return (c == null || c.state().isAir()) && !nearDoor(x, y, z)
				&& (g.get(x, y - 1, z) == null || g.get(x, y - 1, z).role() == Role.FLOOR);
	}

	private void furnish() {
		for (BuildingPlan.Room room : p.rooms) {
			int y = room.floor().feet();
			List<int[]> placed = new ArrayList<>();
			// Light in a corner.
			int[][] corners = {{room.maxX(), room.maxZ()}, {room.minX(), room.maxZ()}, {room.maxX(), room.minZ()}, {room.minX(), room.minZ()}};
			for (int[] c : corners) {
				if (free(c[0], y, c[1])) {
					g.set(c[0], y, c[1], with(style("light"), BlockStateProperties.HANGING, false), room.id(), Role.FURNITURE);
					placed.add(c);
					break;
				}
			}
			// Function-specific furniture along the back of the room.
			List<BlockState> items = switch (room.function()) {
				case "kitchen" -> List.of(with(Blocks.FURNACE.defaultBlockState(), BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH),
						Blocks.CRAFTING_TABLE.defaultBlockState(),
						with(Blocks.SMOKER.defaultBlockState(), BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
				case "storage" -> List.of(with(Blocks.CHEST.defaultBlockState(), BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH),
						Blocks.BARREL.defaultBlockState());
				case "library" -> List.of(Blocks.BOOKSHELF.defaultBlockState(), Blocks.BOOKSHELF.defaultBlockState(),
						Blocks.LECTERN.defaultBlockState());
				case "workshop" -> List.of(Blocks.CRAFTING_TABLE.defaultBlockState(), Blocks.SMITHING_TABLE.defaultBlockState(),
						Blocks.ANVIL.defaultBlockState());
				default -> List.of();
			};
			int x = room.minX();
			for (BlockState item : items) {
				while (x <= room.maxX() && !free(x, y, room.minZ())) {
					x++;
				}
				if (x > room.maxX()) {
					break;
				}
				g.set(x, y, room.minZ(), item, room.id(), Role.FURNITURE);
				x++;
			}
			if (room.function().equals("bedroom")) {
				for (int bx = room.minX(); bx <= room.maxX(); bx++) {
					if (room.minZ() + 1 <= room.maxZ() && free(bx, y, room.minZ()) && free(bx, y, room.minZ() + 1)) {
						BlockState bed = with(Blocks.RED_BED.defaultBlockState(), BedBlock.FACING, Direction.NORTH);
						g.set(bx, y, room.minZ(), with(bed, BedBlock.PART, BedPart.HEAD), room.id(), Role.FURNITURE);
						g.set(bx, y, room.minZ() + 1, with(bed, BedBlock.PART, BedPart.FOOT), room.id(), Role.FURNITURE);
						break;
					}
				}
			}
		}
	}

	/**
	 * Foundations down to the ground, a flat terrace of {@code terrain.margin} blocks around the
	 * building, and clearing of terrain and plants inside the building and above the terrace.
	 */
	private void terrain() {
		int m = p.terrainMargin;
		for (int x = -m; x < W + m; x++) {
			for (int z = -m; z < D + m; z++) {
				boolean inside = inFootprint(x, z);
				long key = BuildingGrid.key(x, 0, z);
				if (inside) {
					// y = 0 is the floor layer / wall base; the foundation starts below it.
					g.setIfAbsent(x, -1, z, style("foundation"), "foundation", Role.FOUNDATION);
					fillColumns.put(key, style("foundation"));
					fillFrom.put(key, -2);
				} else {
					// The terrace surface is flush with the floor layer, so doors need no step.
					g.setIfAbsent(x, 0, z, style("ground"), "terrain", Role.TERRAIN);
					fillColumns.putIfAbsent(key, DIRT);
					fillFrom.putIfAbsent(key, -1);
				}
				// Clear terrain and plants above floor level: up to the roof inside, up to the
				// surface around (cut the hillside so the terrace is level).
				int top = Math.max(inside ? roofTop : 4, columnTop(x, z));
				top = Math.min(top, roofTop + 24);
				for (int y = inside ? 0 : 1; y <= top; y++) {
					if (!g.has(x, y, z) && (inside || !g.terrainState(x, y, z).isAir())) {
						g.set(x, y, z, AIR, inside ? "interior" : "terrain", inside ? Role.CLEAR : Role.TERRAIN);
					}
				}
			}
		}
		for (var e : fillColumns.entrySet()) {
			BlockPos k = BlockPos.of(e.getKey());
			int x = k.getX(), z = k.getZ();
			boolean inside = inFootprint(x, z) || e.getValue() == style("foundation");
			int depth = 0;
			for (int y = fillFrom.getOrDefault(e.getKey(), -2); ; y--) {
				BlockState w = g.terrainState(x, y, z);
				boolean solid = !w.isAir() && w.getFluidState().isEmpty() && !Terrain.vegetation(w)
						&& !w.getCollisionShape(g.level, g.world(x, y, z)).isEmpty();
				if (solid) {
					break;
				}
				if (++depth > p.maxFoundationDepth) {
					issue(false, "FOUNDATION_DEEP", "the ground at local " + x + "," + z + " is more than " + p.maxFoundationDepth
							+ " blocks below the floor; the foundation stops there", inside ? "foundation" : "terrain", new int[]{x, y, z});
					break;
				}
				g.setIfAbsent(x, y, z, e.getValue(), inside ? "foundation" : "terrain", inside ? Role.FOUNDATION : Role.TERRAIN);
				foundationMaxDepth = Math.max(foundationMaxDepth, depth);
			}
		}
	}

	/** Local y of the highest non-air world block in a column (bounded), or -1. */
	private int columnTop(int x, int z) {
		for (int y = roofTop + 24; y >= 0; y--) {
			if (!g.terrainState(x, y, z).isAir()) {
				return y;
			}
		}
		return -1;
	}

	private void patches() {
		for (BuildingPlan.Patch patch : p.patches) {
			for (int i = 0; i < patch.positions().size(); i++) {
				int[] pos = patch.positions().get(i);
				BuildingGrid.Cell old = g.get(pos[0], pos[1], pos[2]);
				Role role = old != null ? old.role() : Role.PATCH;
				g.set(pos[0], pos[1], pos[2], patch.states().get(i), patch.component(), role);
			}
		}
	}

	// ---------------------------------------------------------------- output

	/** A compiled block in world coordinates. */
	public record WorldCell(BlockState state, String component) {
	}

	/** Every compiled block by world position (as {@code BlockPos.asLong}), with its component. */
	public it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<WorldCell> worldCells() {
		if (worldCells != null) return worldCells;
		var out = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<WorldCell>();
		for (var e : g.cells().long2ObjectEntrySet()) {
			BlockPos l = BlockPos.of(e.getLongKey());
			out.put(g.world(l.getX(), l.getY(), l.getZ()).asLong(),
					new WorldCell(e.getValue().state().rotate(g.rotation), e.getValue().component()));
		}
		worldCells = out;
		return worldCells;
	}

	/** World blocks (rotated states) for placement. */
	public BlockList toBlockList() {
		BlockList out = new BlockList();
		for (var e : g.cells().long2ObjectEntrySet()) {
			BlockPos l = BlockPos.of(e.getLongKey());
			out.put(g.world(l.getX(), l.getY(), l.getZ()), e.getValue().state().rotate(g.rotation));
		}
		return out;
	}

	/** Per-component block counts and world bounds. */
	public JsonArray components() {
		Map<String, int[]> info = new LinkedHashMap<>(); // count, minX, minY, minZ, maxX, maxY, maxZ (world)
		Map<String, String> roles = new LinkedHashMap<>();
		for (var e : g.cells().long2ObjectEntrySet()) {
			BuildingGrid.Cell c = e.getValue();
			if (c.state().isAir()) {
				continue;
			}
			BlockPos l = BlockPos.of(e.getLongKey());
			BlockPos w = g.world(l.getX(), l.getY(), l.getZ());
			int[] a = info.computeIfAbsent(c.component(), k -> new int[]{0, w.getX(), w.getY(), w.getZ(), w.getX(), w.getY(), w.getZ()});
			a[0]++;
			a[1] = Math.min(a[1], w.getX());
			a[2] = Math.min(a[2], w.getY());
			a[3] = Math.min(a[3], w.getZ());
			a[4] = Math.max(a[4], w.getX());
			a[5] = Math.max(a[5], w.getY());
			a[6] = Math.max(a[6], w.getZ());
			roles.putIfAbsent(c.component(), c.role().name().toLowerCase());
		}
		JsonArray out = new JsonArray();
		info.forEach((id, a) -> {
			JsonObject o = new JsonObject();
			o.addProperty("id", id);
			o.addProperty("role", roles.get(id));
			o.addProperty("blocks", a[0]);
			o.add("bounds", dev.haochen.mcmcp.world.Region.of(new BlockPos(a[1], a[2], a[3]), new BlockPos(a[4], a[5], a[6])).toJson());
			out.add(o);
		});
		return out;
	}

	static JsonArray worldPositions(BuildingGrid g, List<int[]> local, int limit) {
		JsonArray a = new JsonArray();
		for (int[] l : local.subList(0, Math.min(limit, local.size()))) {
			a.add(Json.blockPos(g.world(l[0], l[1], l[2])));
		}
		return a;
	}
}
