package dev.haochen.mcmcp.arch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.arch.BuildingGrid.Cell;
import dev.haochen.mcmcp.arch.BuildingGrid.Role;
import dev.haochen.mcmcp.arch.BuildingPlan.Floor;
import dev.haochen.mcmcp.arch.BuildingPlan.Opening;
import dev.haochen.mcmcp.arch.PlanCompiler.Issue;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.Guard;
import dev.haochen.mcmcp.world.Region;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic architectural checks on a compiled building (local frame + the current world):
 * allowed region, collisions with existing builds, floating parts, incomplete walls, roof gaps
 * and orientation, door and stair orientation, entrances, reachability of every floor and room
 * by walking (doors and stairs, with headroom), and floor coverage.
 */
public final class PlanValidator {
	private final PlanCompiler compiler;
	private final BuildingGrid g;
	private final BuildingPlan p;
	private final List<Issue> issues = new ArrayList<>();
	private final JsonObject reach = new JsonObject();
	/** World positions that belong to this building already (when revising), not collisions. */
	private java.util.Set<Long> own = java.util.Set.of();

	private PlanValidator(PlanCompiler compiler) {
		this.compiler = compiler;
		this.g = compiler.grid();
		this.p = g.plan;
	}

	public static JsonObject validate(PlanCompiler compiler, Guard guard) {
		return validate(compiler, guard, java.util.Set.of());
	}

	/** {@code own}: world positions (as longs) the building occupies already, e.g. when revising it. */
	public static JsonObject validate(PlanCompiler compiler, Guard guard, java.util.Set<Long> own) {
		PlanValidator v = new PlanValidator(compiler);
		v.own = own;
		v.issues.addAll(compiler.issues);
		v.region(guard);
		v.collisions();
		v.floating();
		v.walls();
		v.roof();
		v.orientation();
		v.floorCoverage();
		v.reachability();
		return v.report();
	}

	private void add(boolean error, String code, String message, Set<String> components, List<int[]> local) {
		issues.add(new Issue(error, code, message, new ArrayList<>(components), local));
	}

	private void region(Guard guard) {
		Region bounds = worldBounds();
		try {
			guard.checkRegion(g.level, bounds);
		} catch (RpcException e) {
			add(true, "REGION", e.getMessage(), Set.of("building"), List.of());
		}
	}

	Region worldBounds() {
		BlockPos a = g.world(g.minX(), g.minY(), g.minZ()), b = g.world(g.maxX(), g.maxY(), g.maxZ());
		return Region.of(a, b);
	}

	private void collisions() {
		Set<String> comps = new LinkedHashSet<>();
		List<int[]> where = new ArrayList<>();
		Map<String, Integer> what = new LinkedHashMap<>();
		int count = 0;
		for (var e : g.cells().long2ObjectEntrySet()) {
			BlockPos l = BlockPos.of(e.getLongKey());
			BlockState now = g.worldState(l.getX(), l.getY(), l.getZ());
			if (Terrain.built(now) && now != e.getValue().state() && !own.contains(g.world(l.getX(), l.getY(), l.getZ()).asLong())) {
				count++;
				what.merge(dev.haochen.mcmcp.world.Blocks.id(now), 1, Integer::sum);
				comps.add(e.getValue().component());
				if (where.size() < 10) {
					where.add(new int[]{l.getX(), l.getY(), l.getZ()});
				}
			}
		}
		if (count > 0) {
			add(!p.replaceExisting, "COLLISION", count + " existing built blocks would be replaced " + what
					+ (p.replaceExisting ? " (replaceExisting is set)" : "; move the building or set replaceExisting: true"), comps, where);
		}
	}

	private static boolean solid(BlockState s) {
		return !s.isAir() && s.getFluidState().isEmpty() && !Terrain.vegetation(s);
	}

	/** Every non-air block must connect (face to face) to the untouched ground. */
	private void floating() {
		LongOpenHashSet seen = new LongOpenHashSet();
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
		for (var e : g.cells().long2ObjectEntrySet()) {
			if (e.getValue().state().isAir()) {
				continue;
			}
			BlockPos l = BlockPos.of(e.getLongKey());
			for (Direction d : Direction.values()) {
				BlockPos n = l.relative(d);
				if (!g.has(n.getX(), n.getY(), n.getZ()) && solid(g.worldState(n.getX(), n.getY(), n.getZ()))) {
					seen.add(e.getLongKey());
					queue.enqueue(e.getLongKey());
					break;
				}
			}
		}
		while (!queue.isEmpty()) {
			BlockPos l = BlockPos.of(queue.dequeueLong());
			for (Direction d : Direction.values()) {
				BlockPos n = l.relative(d);
				Cell c = g.get(n.getX(), n.getY(), n.getZ());
				if (c != null && !c.state().isAir() && seen.add(n.asLong())) {
					queue.enqueue(n.asLong());
				}
			}
		}
		Map<String, List<int[]>> floating = new LinkedHashMap<>();
		for (var e : g.cells().long2ObjectEntrySet()) {
			if (!e.getValue().state().isAir() && !seen.contains(e.getLongKey())) {
				BlockPos l = BlockPos.of(e.getLongKey());
				floating.computeIfAbsent(e.getValue().component(), k -> new ArrayList<>()).add(new int[]{l.getX(), l.getY(), l.getZ()});
			}
		}
		floating.forEach((comp, cells) -> add(true, "FLOATING", cells.size() + " block(s) of " + comp
				+ " are not connected to the ground or the rest of the building", Set.of(comp), cells.subList(0, Math.min(10, cells.size()))));
	}

	private static boolean passable(BlockState s) {
		return s.isAir() || s.getBlock() instanceof DoorBlock || s.getBlock() instanceof FenceGateBlock
				|| s.is(BlockTags.WOOL_CARPETS) || s.getFluidState().isEmpty() && s.getBlock().defaultBlockState().is(BlockTags.REPLACEABLE);
	}

	/** Exterior walls between the ground floor and the top beam must be closed except at declared openings. */
	private void walls() {
		int W = p.width, D = p.depth, H = p.wallTop();
		Map<String, List<int[]>> gaps = new LinkedHashMap<>();
		for (int y = 1; y < H; y++) {
			for (int x = 0; x < W; x++) {
				for (int z = 0; z < D; z++) {
					if (!(x == 0 || z == 0 || x == W - 1 || z == D - 1) || g.openingCells.contains(BuildingGrid.key(x, y, z))) {
						continue;
					}
					BlockState s = g.finalState(x, y, z);
					if (s.isAir() || passable(s)) {
						String side = z == D - 1 ? "wall-front" : z == 0 ? "wall-back" : x == 0 ? "wall-left" : "wall-right";
						Cell c = g.get(x, y, z);
						gaps.computeIfAbsent(c != null ? c.component() : side, k -> new ArrayList<>()).add(new int[]{x, y, z});
					}
				}
			}
		}
		gaps.forEach((comp, cells) -> add(true, "INCOMPLETE_WALL", cells.size() + " gap(s) in the exterior wall that are not declared openings",
				Set.of(comp), cells.subList(0, Math.min(10, cells.size()))));
	}

	private void roof() {
		String type = p.roof.type();
		if (type.equals("none")) {
			return;
		}
		int W = p.width, D = p.depth, H = p.wallTop();
		List<int[]> gaps = new ArrayList<>();
		for (int x = 0; x < W; x++) {
			for (int z = 0; z < D; z++) {
				boolean covered = false;
				for (int y = H + 1; y <= H + Math.max(W, D) + 2; y++) {
					Cell c = g.get(x, y, z);
					if (c != null && c.role() == Role.ROOF && !c.state().isAir()) {
						covered = true;
						break;
					}
				}
				if (!covered) {
					gaps.add(new int[]{x, H + 1, z});
				}
			}
		}
		if (!gaps.isEmpty()) {
			add(true, "ROOF_GAP", gaps.size() + " column(s) of the footprint have no roof block above them", Set.of(p.roof.id()),
					gaps.subList(0, Math.min(10, gaps.size())));
		}
		// Roof stairs must face (rise towards) the ridge / centre.
		double cx = (W - 1) / 2.0, cz = (D - 1) / 2.0;
		List<int[]> wrong = new ArrayList<>();
		Set<String> comps = new LinkedHashSet<>();
		for (var e : g.cells().long2ObjectEntrySet()) {
			Cell c = e.getValue();
			if (c.role() != Role.ROOF || !(c.state().getBlock() instanceof StairBlock) || c.state().getValue(StairBlock.HALF) != Half.BOTTOM) {
				continue;
			}
			BlockPos l = BlockPos.of(e.getLongKey());
			Direction f = c.state().getValue(StairBlock.FACING);
			double dx = cx - l.getX(), dz = cz - l.getZ();
			if (type.equals("gable")) {
				if (p.roof.ridgeAlongX()) dx = 0; else dz = 0;
			}
			if (f.getStepX() * dx + f.getStepZ() * dz <= 0 && Math.abs(dx) + Math.abs(dz) > 0.6) {
				wrong.add(new int[]{l.getX(), l.getY(), l.getZ()});
				comps.add(c.component());
			}
		}
		if (!wrong.isEmpty()) {
			add(true, "ROOF_ORIENTATION", wrong.size() + " roof stair(s) face away from the ridge (the high side must point to the ridge)",
					comps, wrong.subList(0, Math.min(10, wrong.size())));
		}
	}

	/** Doors: both halves, facing through the wall; staircase steps: facing the climb direction. */
	private void orientation() {
		List<int[]> doors = new ArrayList<>(), steps = new ArrayList<>();
		Set<String> doorComps = new LinkedHashSet<>(), stairComps = new LinkedHashSet<>();
		Map<String, Direction> stairDir = new LinkedHashMap<>();
		for (BuildingPlan.Stairs s : p.stairs) {
			stairDir.put(s.id(), s.direction().out);
		}
		for (var e : g.cells().long2ObjectEntrySet()) {
			Cell c = e.getValue();
			BlockPos l = BlockPos.of(e.getLongKey());
			BlockState s = c.state();
			if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
				Direction f = s.getValue(DoorBlock.FACING);
				BlockState upper = g.finalState(l.getX(), l.getY() + 1, l.getZ());
				BlockPos front = l.relative(f), back = l.relative(f.getOpposite());
				BlockPos left = l.relative(f.getClockWise()), right = l.relative(f.getCounterClockWise());
				boolean halves = upper.getBlock() == s.getBlock() && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
						&& upper.getValue(DoorBlock.FACING) == f;
				boolean through = passable(g.finalState(front.getX(), front.getY(), front.getZ()))
						&& passable(g.finalState(back.getX(), back.getY(), back.getZ()));
				boolean framed = !passable(g.finalState(left.getX(), left.getY(), left.getZ()))
						|| !passable(g.finalState(right.getX(), right.getY(), right.getZ()));
				if (!halves || !through || !framed) {
					doors.add(new int[]{l.getX(), l.getY(), l.getZ()});
					doorComps.add(c.component() + (!halves ? " (missing upper half)" : !through ? " (blocked or facing along the wall)" : " (not set in a wall)"));
				}
			}
			if (c.role() == Role.STAIRS && s.getBlock() instanceof StairBlock && stairDir.containsKey(c.component())
					&& s.getValue(StairBlock.FACING) != stairDir.get(c.component())) {
				steps.add(new int[]{l.getX(), l.getY(), l.getZ()});
				stairComps.add(c.component());
			}
		}
		if (!doors.isEmpty()) {
			add(true, "DOOR_ORIENTATION", doors.size() + " door(s) are malformed", doorComps, doors);
		}
		if (!steps.isEmpty()) {
			add(true, "STAIRS_ORIENTATION", steps.size() + " step(s) do not face the climbing direction", stairComps, steps);
		}
	}

	/** Upper floors must actually be floors (not mostly holes). */
	private void floorCoverage() {
		int W = p.width, D = p.depth;
		for (Floor f : p.floors) {
			int interior = (W - 2) * (D - 2), covered = 0;
			for (int x = 1; x < W - 1; x++) {
				for (int z = 1; z < D - 1; z++) {
					BlockState s = g.finalState(x, f.base(), z);
					if (!s.isAir() && !passable(s)) {
						covered++;
					}
				}
			}
			if (covered < interior * 0.75) {
				add(true, "FLOOR_INCOMPLETE", "floor " + f.id() + " covers only " + covered + " of " + interior + " interior cells at y=" + f.base(),
						Set.of(f.id()), List.of(new int[]{1, f.base(), 1}));
			}
		}
	}

	// ---------------------------------------------------------------- walking

	private boolean canStand(int x, int y, int z) {
		BlockState feet = g.finalState(x, y, z), head = g.finalState(x, y + 1, z), below = g.finalState(x, y - 1, z);
		return passable(feet) && passable(head) && !passable(below) && below.getFluidState().isEmpty()
				&& !(below.getBlock() instanceof net.minecraft.world.level.block.FenceBlock)
				&& !(below.getBlock() instanceof net.minecraft.world.level.block.WallBlock);
	}

	/** Stairs and bottom slabs are climbed by walking; anything else needs a jump. */
	private static boolean halfStep(BlockState s) {
		return s.getBlock() instanceof StairBlock
				|| s.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
				&& s.getValue(net.minecraft.world.level.block.SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
	}

	private void reachability() {
		int m = p.terrainMargin + 2;
		int minX = -m, maxX = p.width - 1 + m, minZ = -m, maxZ = p.depth - 1 + m;
		int minY = -2, maxY = p.wallTop() + 2;
		List<Opening> entrances = new ArrayList<>();
		for (Opening o : p.openings) {
			if (o.type().equals("door") || o.type().equals("arch")) {
				if (o.wall() != null && o.floor().index() == 0) {
					entrances.add(o);
				}
			}
		}
		if (entrances.isEmpty()) {
			add(true, "MISSING_ENTRANCE", "no exterior door or arch on the ground floor", Set.of("building"), List.of());
			return;
		}
		LongOpenHashSet seen = new LongOpenHashSet();
		LongArrayFIFOQueue q = new LongArrayFIFOQueue();
		List<String> blocked = new ArrayList<>();
		for (Opening o : entrances) {
			Direction out = o.wall().out;
			int[] c = switch (o.wall()) {
				case FRONT -> new int[]{o.at(), p.depth - 1};
				case BACK -> new int[]{o.at(), 0};
				case LEFT -> new int[]{0, o.at()};
				case RIGHT -> new int[]{p.width - 1, o.at()};
			};
			int x = c[0] + out.getStepX(), z = c[1] + out.getStepZ(), y = o.floor().feet() + o.sill();
			boolean found = false;
			for (int dy : new int[]{0, -1, 1}) {
				if (canStand(x, y + dy, z)) {
					long k = BlockPos.asLong(x, y + dy, z);
					if (seen.add(k)) {
						q.enqueue(k);
					}
					found = true;
					break;
				}
			}
			if (!found) {
				blocked.add(o.id());
			}
		}
		if (blocked.size() == entrances.size()) {
			add(true, "MISSING_ENTRANCE", "no entrance can be reached from outside: nowhere to stand in front of " + blocked,
					new LinkedHashSet<>(blocked), List.of());
			return;
		}
		for (String b : blocked) {
			add(false, "ENTRANCE_BLOCKED", "nowhere to stand outside " + b, Set.of(b), List.of());
		}
		while (!q.isEmpty()) {
			BlockPos cur = BlockPos.of(q.dequeueLong());
			for (Direction d : Direction.Plane.HORIZONTAL) {
				for (int dy : new int[]{0, 1, -1}) {
					int x = cur.getX() + d.getStepX(), y = cur.getY() + dy, z = cur.getZ() + d.getStepZ();
					if (x < minX || x > maxX || z < minZ || z > maxZ || y < minY || y > maxY) {
						continue;
					}
					if (dy == 1 && !halfStep(g.finalState(x, y - 1, z)) && !passable(g.finalState(cur.getX(), cur.getY() + 2, cur.getZ()))) {
						continue; // a full block needs a jump, and there is no room above to jump
					}
					if (canStand(x, y, z)) {
						long k = BlockPos.asLong(x, y, z);
						if (seen.add(k)) {
							q.enqueue(k);
						}
					}
				}
			}
		}
		JsonObject floors = new JsonObject();
		for (Floor f : p.floors) {
			boolean ok = false;
			for (int x = 1; x < p.width - 1 && !ok; x++) {
				for (int z = 1; z < p.depth - 1 && !ok; z++) {
					ok = seen.contains(BlockPos.asLong(x, f.feet(), z));
				}
			}
			floors.addProperty(f.id(), ok);
			if (!ok) {
				boolean hasStairs = p.stairs.stream().anyMatch(s -> s.to() == f);
				if (f.index() == 0) {
					add(true, "UNREACHABLE_FLOOR", "the ground floor cannot be walked into from the entrance", Set.of(f.id()), List.of());
				} else if (!hasStairs) {
					add(true, "MISSING_STAIRS", "floor " + f.id() + " has no stairs leading up to it", Set.of(f.id()), List.of());
				} else {
					Set<String> comps = new LinkedHashSet<>();
					p.stairs.stream().filter(s -> s.to() == f).forEach(s -> comps.add(s.id()));
					add(true, "STAIRS_BLOCKED", "floor " + f.id() + " has stairs but cannot be reached by walking up them (blocked start, no headroom or no landing)",
							comps, List.of());
				}
			}
		}
		JsonObject rooms = new JsonObject();
		for (BuildingPlan.Room r : p.rooms) {
			boolean ok = false;
			for (int x = r.minX(); x <= r.maxX() && !ok; x++) {
				for (int z = r.minZ(); z <= r.maxZ() && !ok; z++) {
					ok = seen.contains(BlockPos.asLong(x, r.floor().feet(), z));
				}
			}
			rooms.addProperty(r.id(), ok);
			if (!ok) {
				add(true, "UNREACHABLE_ROOM", "room " + r.id() + " (" + r.function() + ") cannot be walked to from the entrance; add a door in its walls",
						Set.of(r.id()), List.of(new int[]{r.minX(), r.floor().feet(), r.minZ()}));
			}
		}
		reach.add("floors", floors);
		reach.add("rooms", rooms);
	}

	private JsonObject report() {
		JsonArray errors = new JsonArray(), warnings = new JsonArray();
		for (Issue i : issues) {
			JsonObject o = new JsonObject();
			o.addProperty("code", i.code());
			o.addProperty("message", i.message());
			JsonArray comps = new JsonArray();
			i.components().forEach(comps::add);
			o.add("components", comps);
			if (!i.local().isEmpty()) {
				o.add("positions", PlanCompiler.worldPositions(g, i.local(), 10));
				JsonArray local = new JsonArray();
				for (int[] l : i.local().subList(0, Math.min(10, i.local().size()))) {
					JsonArray a = new JsonArray();
					a.add(l[0]);
					a.add(l[1]);
					a.add(l[2]);
					local.add(a);
				}
				o.add("localPositions", local);
			}
			(i.error() ? errors : warnings).add(o);
		}
		JsonObject out = new JsonObject();
		out.addProperty("ok", errors.isEmpty());
		out.add("errors", errors);
		out.add("warnings", warnings);
		out.add("reachable", reach);
		return out;
	}
}
