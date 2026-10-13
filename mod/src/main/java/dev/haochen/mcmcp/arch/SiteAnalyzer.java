package dev.haochen.mcmcp.arch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.api.Json;
import dev.haochen.mcmcp.api.Params;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.Guard;
import dev.haochen.mcmcp.world.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Terrain analysis for choosing where to build: per-column ground height and surface type over a
 * bounded square, a compact character map, slope statistics, and ranked placements for a
 * footprint with the floor level, cut/fill volumes and the most accessible entrance side.
 * Placements are returned in building-plan terms ({@code origin}, {@code facing}). Server thread.
 */
public final class SiteAnalyzer {
	public static final int MAX_RADIUS = 48;
	private static final int SCAN_DEPTH = 40;
	private static final int[][] NEXT = {{1, 0}, {0, 1}};
	private static final int[][] AROUND = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
	private static final Direction[] NORTH_SOUTH = {Direction.SOUTH, Direction.NORTH};
	private static final Direction[] EAST_WEST = {Direction.EAST, Direction.WEST};
	private static final Direction[] ALL_SIDES = {Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST};

	private final ServerLevel level;
	private final Guard guard;
	private final int x0, z0, size;
	private final Terrain.Column[] cols;

	private SiteAnalyzer(ServerLevel level, Guard guard, BlockPos center, int radius) {
		this.level = level;
		this.guard = guard;
		this.x0 = center.getX() - radius;
		this.z0 = center.getZ() - radius;
		this.size = 2 * radius + 1;
		this.cols = new Terrain.Column[size * size];
		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				int x = x0 + dx, z = z0 + dz;
				cols[dz * size + dx] = level.hasChunkAt(new BlockPos(x, 0, z)) ? Terrain.column(level, x, z, SCAN_DEPTH) : null;
			}
		}
	}

	public record Request(BlockPos center, int radius, int width, int depth, int margin, @Nullable Direction preferFacing, int top) {
	}

	/** Validate before the caller attempts any chunk access or generation. */
	public static void validate(Request r) {
		if (r.radius < 4 || r.radius > MAX_RADIUS) {
			throw new RpcException(RpcException.INVALID_PARAMS, "radius must be 4 to " + MAX_RADIUS);
		}
		if (r.width < 3 || r.depth < 3 || r.width > 48 || r.depth > 48 || r.width > 2 * r.radius || r.depth > 2 * r.radius) {
			throw Params.invalid("footprint must be 3-48 per side and fit inside the search area (2 * radius)");
		}
		if (r.margin < 0 || r.margin > 6) {
			throw Params.invalid("margin must be 0-6");
		}
		if (r.top < 1 || r.top > 10) {
			throw Params.invalid("top must be 1-10");
		}
		if (r.preferFacing != null && !r.preferFacing.getAxis().isHorizontal()) {
			throw Params.invalid("facing must be north, south, east or west");
		}
	}

	public static JsonObject analyze(ServerLevel level, Guard guard, Request r) {
		validate(r);
		SiteAnalyzer a = new SiteAnalyzer(level, guard, r.center, r.radius);
		JsonObject out = new JsonObject();
		out.add("area", Region.of(new BlockPos(a.x0, r.center.getY(), a.z0),
				new BlockPos(a.x0 + a.size - 1, r.center.getY(), a.z0 + a.size - 1)).toJson());
		out.add("summary", a.summary());
		out.add("map", a.map());
		out.add("candidates", a.candidates(r));
		return out;
	}

	private Terrain.Column col(int dx, int dz) {
		if (dx < 0 || dz < 0 || dx >= size || dz >= size) {
			return null;
		}
		return cols[dz * size + dx];
	}

	private JsonObject summary() {
		int ground = 0, water = 0, lava = 0, tree = 0, built = 0, road = 0, unloaded = 0, flat = 0;
		int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE, maxStep = 0;
		long stepSum = 0;
		int steps = 0;
		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				Terrain.Column c = col(dx, dz);
				if (c == null) {
					unloaded++;
					continue;
				}
				switch (c.surface()) {
					case GROUND -> ground++;
					case WATER -> water++;
					case LAVA -> lava++;
					case TREE -> tree++;
					case BUILT -> built++;
					default -> {
					}
				}
				if (c.road()) {
					road++;
				}
				minH = Math.min(minH, c.groundY());
				maxH = Math.max(maxH, c.groundY());
				int local = 0;
				for (int[] d : NEXT) {
					Terrain.Column n = col(dx + d[0], dz + d[1]);
					if (n != null) {
						int step = Math.abs(n.groundY() - c.groundY());
						maxStep = Math.max(maxStep, step);
						local = Math.max(local, step);
						stepSum += step;
						steps++;
					}
				}
				if (c.surface() == Terrain.Surface.GROUND && local <= 1) {
					flat++;
				}
			}
		}
		int total = size * size - unloaded;
		JsonObject o = new JsonObject();
		o.addProperty("columns", total);
		o.addProperty("groundMin", minH == Integer.MAX_VALUE ? null : minH);
		o.addProperty("groundMax", maxH == Integer.MIN_VALUE ? null : maxH);
		o.addProperty("meanSlope", steps == 0 ? 0 : Math.round(100.0 * stepSum / steps) / 100.0);
		o.addProperty("maxStep", maxStep);
		o.addProperty("buildableFraction", total == 0 ? 0 : Math.round(100.0 * flat / total) / 100.0);
		JsonObject surfaces = new JsonObject();
		surfaces.addProperty("ground", ground);
		surfaces.addProperty("water", water);
		surfaces.addProperty("lava", lava);
		surfaces.addProperty("tree", tree);
		surfaces.addProperty("built", built);
		surfaces.addProperty("road", road);
		if (unloaded > 0) {
			surfaces.addProperty("unloaded", unloaded);
		}
		o.add("surfaces", surfaces);
		return o;
	}

	/**
	 * Rows are z (north at the top), characters are x (west on the left), sampled every
	 * {@code step} blocks: {@code .} flat ground, {@code ^} slope >= 2, {@code ~} water, {@code !} lava,
	 * {@code T} tree, {@code #} built, {@code =} road, {@code ?} unloaded. Heights are in "heights".
	 */
	private JsonObject map() {
		int step = Math.max(1, (size + 47) / 48);
		JsonArray rows = new JsonArray(), heights = new JsonArray();
		for (int dz = 0; dz < size; dz += step) {
			StringBuilder row = new StringBuilder();
			JsonArray h = new JsonArray();
			for (int dx = 0; dx < size; dx += step) {
				Terrain.Column c = col(dx, dz);
				if (c == null) {
					row.append('?');
					h.add((Number) null);
					continue;
				}
				h.add(c.groundY());
				char ch = switch (c.surface()) {
					case WATER -> '~';
					case LAVA -> '!';
					case TREE -> 'T';
					case BUILT -> c.road() ? '=' : '#';
					case VOID -> '?';
					default -> {
						if (c.road()) {
							yield '=';
						}
						int slope = 0;
						for (int[] d : AROUND) {
							Terrain.Column n = col(dx + d[0] * step, dz + d[1] * step);
							if (n != null) {
								slope = Math.max(slope, Math.abs(n.groundY() - c.groundY()));
							}
						}
						yield slope >= 2 ? '^' : '.';
					}
				};
				row.append(ch);
			}
			rows.add(row.toString());
			heights.add(h);
		}
		JsonObject o = new JsonObject();
		o.addProperty("step", step);
		JsonArray origin = new JsonArray();
		origin.add(x0);
		origin.add(z0);
		o.add("originXZ", origin);
		o.addProperty("legend", ". flat  ^ steep  ~ water  ! lava  T tree  # built  = road  ? unloaded; rows = z (north first), columns = x (west first)");
		o.add("rows", rows);
		o.add("heights", heights);
		return o;
	}

	private record Candidate(int wx0, int wz0, int wx, int wz, int floorY, Direction facing, double score, SiteStats stats) {
	}

	private record SiteStats(int floorY, int min, int max, int cut, int fill, int water, int lava, int tree, int built,
			Direction[] sides, double[] accessCosts, double distance) {
		JsonObject toJson() {
			JsonObject s = new JsonObject();
			s.addProperty("floorY", floorY); s.addProperty("groundMin", min); s.addProperty("groundMax", max);
			s.addProperty("cut", cut); s.addProperty("fill", fill); s.addProperty("maxFoundationDepth", Math.max(0, floorY - min));
			if (water > 0) s.addProperty("waterColumns", water);
			if (lava > 0) s.addProperty("lavaColumns", lava);
			if (tree > 0) s.addProperty("treeColumns", tree);
			if (built > 0) s.addProperty("builtColumns", built);
			JsonObject access = new JsonObject();
			for (int i = 0; i < sides.length; i++) access.addProperty(sides[i].getName(), Math.round(accessCosts[i] * 10) / 10.0);
			s.add("entranceAccessCost", access); s.addProperty("distanceFromCenter", Math.round(distance));
			return s;
		}
	}

	private JsonArray candidates(Request r) {
		List<Candidate> all = new ArrayList<>();
		int[] heights = new int[r.width * r.depth];
		int step = Math.max(1, (r.width + r.depth) / 8);
		// Orientation A: width along x (front faces north or south); B: width along z (front east/west).
		for (int orient = 0; orient < (r.width == r.depth ? 1 : 2); orient++) {
			int wx = orient == 0 ? r.width : r.depth, wz = orient == 0 ? r.depth : r.width;
			for (int dz = r.margin; dz + wz + r.margin <= size; dz += step) {
				for (int dx = r.margin; dx + wx + r.margin <= size; dx += step) {
					Candidate c = evaluate(r, dx, dz, wx, wz, orient, heights);
					if (c != null) {
						all.add(c);
					}
				}
			}
		}
		all.sort((a, b) -> Double.compare(a.score, b.score));
		JsonArray out = new JsonArray();
		List<Candidate> chosen = new ArrayList<>();
		for (Candidate c : all) {
			boolean overlaps = false;
			for (Candidate o : chosen) {
				if (c.wx0 - r.margin < o.wx0 + o.wx + r.margin && o.wx0 - r.margin < c.wx0 + c.wx + r.margin
						&& c.wz0 - r.margin < o.wz0 + o.wz + r.margin && o.wz0 - r.margin < c.wz0 + c.wz + r.margin) {
					overlaps = true;
					break;
				}
			}
			if (overlaps) {
				continue;
			}
			chosen.add(c);
			JsonObject j = new JsonObject();
			j.add("origin", Json.blockPos(new BlockPos(x0 + c.wx0, c.floorY, z0 + c.wz0)));
			j.addProperty("facing", c.facing.getName());
			JsonArray fp = new JsonArray();
			fp.add(r.width);
			fp.add(r.depth);
			j.add("footprint", fp);
			JsonArray worldSize = new JsonArray();
			worldSize.add(c.wx);
			worldSize.add(c.wz);
			j.add("worldSizeXZ", worldSize);
			j.addProperty("score", Math.round(c.score * 10) / 10.0);
			j.add("stats", c.stats.toJson());
			out.add(j);
			if (chosen.size() >= r.top) {
				break;
			}
		}
		return out;
	}

	private @Nullable Candidate evaluate(Request r, int dx0, int dz0, int wx, int wz, int orient, int[] hs) {
		int n = 0, water = 0, lava = 0, tree = 0, built = 0;
		for (int dz = dz0; dz < dz0 + wz; dz++) {
			for (int dx = dx0; dx < dx0 + wx; dx++) {
				Terrain.Column c = col(dx, dz);
				if (c == null) {
					return null; // unloaded
				}
				hs[n++] = c.groundY();
				switch (c.surface()) {
					case WATER -> water++;
					case LAVA -> lava++;
					case TREE -> tree++;
					case BUILT -> built++;
					default -> {
					}
				}
			}
		}
		Arrays.sort(hs);
		int floorY = hs[n / 2];
		int cut = 0, fill = 0;
		for (int h : hs) {
			cut += Math.max(0, h - floorY);
			fill += Math.max(0, floorY - 1 - h);
		}
		// Allowed region: the footprint from the foundation to a generous roof height.
		Region box = Region.of(new BlockPos(x0 + dx0 - r.margin, hs[0], z0 + dz0 - r.margin),
				new BlockPos(x0 + dx0 + wx - 1 + r.margin, floorY + 24, z0 + dz0 + wz - 1 + r.margin));
		try {
			guard.checkRegion(level, box);
		} catch (RpcException e) {
			return null;
		}
		// Entrance side: the strip just outside each wall, as flat, dry and road-like as possible.
		Direction[] sides = r.width == r.depth ? ALL_SIDES : orient == 0 ? NORTH_SOUTH : EAST_WEST;
		Direction best = null;
		double bestAccess = Double.MAX_VALUE;
		double[] access = new double[sides.length];
		double preferredAccess = Double.MAX_VALUE;
		for (int i = 0; i < sides.length; i++) {
			Direction side = sides[i];
			double a = accessCost(dx0, dz0, wx, wz, side, floorY, Math.max(1, r.margin));
			access[i] = a;
			if (a < bestAccess) {
				best = side;
				bestAccess = a;
			}
			if (side == r.preferFacing) {
				preferredAccess = a;
			}
		}
		// Honour the requested facing unless its side is clearly worse.
		if (r.preferFacing != null && preferredAccess <= bestAccess + 2) {
			best = r.preferFacing;
			bestAccess = preferredAccess;
		}
		int cx = dx0 + wx / 2 - size / 2, cz = dz0 + wz / 2 - size / 2;
		double dist = Math.sqrt(cx * cx + cz * cz);
		double score = cut + 0.6 * fill + 40 * water + 1000 * lava + 100 * built + 2 * tree + 0.3 * dist + 2 * bestAccess
				+ (r.preferFacing != null && best != r.preferFacing ? 5 : 0);
		SiteStats s = new SiteStats(floorY, hs[0], hs[n - 1], cut, fill, water, lava, tree, built, sides, access, dist);
		return new Candidate(dx0, dz0, wx, wz, floorY, best, score, s);
	}

	/** Mean height difference to the floor, plus water/built penalties and a road bonus, along one side. */
	private double accessCost(int dx0, int dz0, int wx, int wz, Direction side, int floorY, int depth) {
		double cost = 0;
		int cells = 0;
		for (int d = 1; d <= depth; d++) {
			int len = side.getAxis() == Direction.Axis.Z ? wx : wz;
			for (int i = 0; i < len; i++) {
				int dx, dz;
				switch (side) {
					case SOUTH -> { dx = dx0 + i; dz = dz0 + wz - 1 + d; }
					case NORTH -> { dx = dx0 + i; dz = dz0 - d; }
					case EAST -> { dx = dx0 + wx - 1 + d; dz = dz0 + i; }
					default -> { dx = dx0 - d; dz = dz0 + i; }
				}
				Terrain.Column c = col(dx, dz);
				if (c == null) {
					cost += 10;
				} else {
					cost += Math.abs(c.groundY() - floorY);
					if (c.surface() == Terrain.Surface.WATER || c.surface() == Terrain.Surface.LAVA) cost += 6;
					if (c.surface() == Terrain.Surface.BUILT && !c.road()) cost += 6;
					if (c.road()) cost -= 2;
				}
				cells++;
			}
		}
		return cells == 0 ? 0 : cost / cells * 3;
	}
}
