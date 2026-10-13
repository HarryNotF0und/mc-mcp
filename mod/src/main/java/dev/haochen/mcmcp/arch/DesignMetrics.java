package dev.haochen.mcmcp.arch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.arch.BuildingGrid.Cell;
import dev.haochen.mcmcp.arch.BuildingGrid.Role;
import dev.haochen.mcmcp.arch.BuildingPlan.Opening;
import dev.haochen.mcmcp.arch.BuildingPlan.Side;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * Objective, block-level design measurements of a compiled building: facade symmetry, window
 * ratios and alignment between floors, roof symmetry and overhangs, proportions. They back up
 * (but do not replace) visual judgement, and are used to check a plan's {@code requirements}.
 */
public final class DesignMetrics {
	private final BuildingGrid g;
	private final BuildingPlan p;
	private final JsonArray issues = new JsonArray();

	private DesignMetrics(BuildingGrid g) {
		this.g = g;
		this.p = g.plan;
	}

	/** {@code {"metrics": {...}, "issues": [...], "requirements": [...]}}. */
	public static JsonObject measure(BuildingGrid g, JsonObject planJson) {
		DesignMetrics m = new DesignMetrics(g);
		JsonObject metrics = new JsonObject();
		JsonObject facades = new JsonObject();
		for (Side s : Side.values()) {
			facades.add(s.name().toLowerCase(), m.facade(s));
		}
		metrics.add("facades", facades);
		metrics.add("windows", m.windows());
		metrics.add("roof", m.roof());
		metrics.add("proportions", m.proportions());
		JsonObject out = new JsonObject();
		out.add("metrics", metrics);
		out.add("issues", m.issues);
		out.add("requirements", m.requirements(planJson, metrics));
		return out;
	}

	private void issue(String code, String message) {
		JsonObject o = new JsonObject();
		o.addProperty("code", code);
		o.addProperty("message", message);
		issues.add(o);
	}

	private char classify(int x, int y, int z) {
		BlockState s = g.finalState(x, y, z);
		if (s.getBlock() instanceof DoorBlock) {
			return 'D';
		}
		if (s.getBlock() instanceof IronBarsBlock || s.getBlock() instanceof TransparentBlock) {
			return 'G'; // glass panes are IronBarsBlock subclasses; glass blocks are TransparentBlock
		}
		if (s.isAir()) {
			return '.';
		}
		return 'W';
	}

	/** The wall seen from outside: row per y (top first), column per position along the wall (left first). */
	private JsonObject facade(Side side) {
		int W = p.width, D = p.depth, H = p.wallTop();
		int len = side == Side.FRONT || side == Side.BACK ? W : D;
		char[][] grid = new char[H - 1][len];
		for (int y = 1; y < H; y++) {
			for (int i = 0; i < len; i++) {
				// Left-to-right as seen from outside.
				int x, z;
				switch (side) {
					case FRONT -> { x = i; z = D - 1; }
					case BACK -> { x = W - 1 - i; z = 0; }
					case LEFT -> { x = 0; z = i; }
					default -> { x = W - 1; z = D - 1 - i; }
				}
				grid[H - 1 - y][i] = classify(x, y, z);
			}
		}
		int same = 0, total = 0, glass = 0, cells = 0;
		JsonArray rows = new JsonArray();
		for (char[] row : grid) {
			rows.add(new String(row));
			for (int i = 0; i < len; i++) {
				cells++;
				if (row[i] == 'G') glass++;
				if (i < len / 2) {
					total++;
					if (row[i] == row[len - 1 - i]) same++;
				}
			}
		}
		double symmetry = total == 0 ? 1 : (double) same / total;
		JsonObject o = new JsonObject();
		o.addProperty("symmetry", Math.round(symmetry * 100) / 100.0);
		o.addProperty("windowRatio", Math.round(100.0 * glass / Math.max(1, cells)) / 100.0);
		o.add("elevation", rows);
		o.addProperty("legend", "W wall, G glass, D door, . open; top row first, left to right as seen from outside");
		if (side == Side.FRONT && symmetry < 0.8) {
			issue("FACADE_ASYMMETRIC", "the front facade is asymmetric (symmetry " + Math.round(symmetry * 100) + "%)");
		}
		return o;
	}

	private JsonObject windows() {
		JsonObject perFloor = new JsonObject();
		int aligned = 0, upper = 0;
		for (BuildingPlan.Floor f : p.floors) {
			int n = 0, area = 0;
			for (Opening o : p.openings) {
				if (o.type().equals("window") && o.wall() != null && o.floor() == f) {
					n++;
					area += o.width() * o.height();
					if (f.index() > 0) {
						upper++;
						boolean ok = false;
						for (Opening b : p.openings) {
							if (b.wall() == o.wall() && b.floor().index() == f.index() - 1 && !b.type().equals("arch")
									&& b.at() <= o.at() + o.width() - 1 && o.at() <= b.at() + b.width() - 1) {
								ok = true;
								break;
							}
						}
						if (ok) aligned++;
					}
				}
			}
			JsonObject fl = new JsonObject();
			fl.addProperty("count", n);
			fl.addProperty("area", area);
			perFloor.add(f.id(), fl);
		}
		JsonObject o = new JsonObject();
		o.add("perFloor", perFloor);
		double alignment = upper == 0 ? 1 : (double) aligned / upper;
		o.addProperty("alignment", Math.round(alignment * 100) / 100.0);
		if (alignment < 0.75) {
			issue("WINDOWS_MISALIGNED", Math.round(alignment * 100) + "% of upper-floor windows sit above a window or door on the floor below");
		}
		return o;
	}

	private JsonObject roof() {
		JsonObject o = new JsonObject();
		o.addProperty("type", p.roof.type());
		if (p.roof.type().equals("none")) {
			return o;
		}
		int W = p.width, D = p.depth;
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		Map<Long, Integer> top = new java.util.HashMap<>();
		for (var e : g.cells().long2ObjectEntrySet()) {
			Cell c = e.getValue();
			if (c.role() != Role.ROOF || c.state().isAir()) {
				continue;
			}
			net.minecraft.core.BlockPos l = net.minecraft.core.BlockPos.of(e.getLongKey());
			minX = Math.min(minX, l.getX());
			maxX = Math.max(maxX, l.getX());
			minZ = Math.min(minZ, l.getZ());
			maxZ = Math.max(maxZ, l.getZ());
			top.merge(((long) l.getX() << 32) | (l.getZ() & 0xffffffffL), l.getY(), Math::max);
		}
		if (top.isEmpty()) {
			issue("ROOF_MISSING", "the roof has no blocks");
			return o;
		}
		JsonObject overhang = new JsonObject();
		overhang.addProperty("left", -minX);
		overhang.addProperty("right", maxX - (W - 1));
		overhang.addProperty("back", -minZ);
		overhang.addProperty("front", maxZ - (D - 1));
		o.add("overhang", overhang);
		boolean even = -minX == maxX - (W - 1) && -minZ == maxZ - (D - 1);
		if (!even) {
			issue("ROOF_OVERHANG_UNEVEN", "the roof overhangs unevenly: " + overhang);
		}
		// Symmetry of roof heights across the ridge (and across both axes for hip/flat roofs).
		int same = 0, total = 0;
		for (int x = 0; x < W; x++) {
			for (int z = 0; z < D; z++) {
				Integer h = top.get(((long) x << 32) | (z & 0xffffffffL));
				int mx = x, mz = z;
				if (p.roof.type().equals("gable")) {
					if (p.roof.ridgeAlongX()) mz = D - 1 - z; else mx = W - 1 - x;
				} else {
					mx = W - 1 - x;
				}
				Integer hm = top.get(((long) mx << 32) | (mz & 0xffffffffL));
				total++;
				if (java.util.Objects.equals(h, hm)) same++;
			}
		}
		double symmetry = total == 0 ? 1 : (double) same / total;
		o.addProperty("symmetry", Math.round(symmetry * 100) / 100.0);
		int ridge = top.values().stream().max(Integer::compare).orElse(p.wallTop());
		o.addProperty("ridgeHeight", ridge - p.wallTop());
		if (same < total) {
			issue("ROOF_ASYMMETRIC", "the roof is asymmetric: " + (total - same) + " of " + total
					+ " columns differ in height from their mirror image across the ridge");
		}
		return o;
	}

	private JsonObject proportions() {
		JsonObject o = new JsonObject();
		o.addProperty("width", p.width);
		o.addProperty("depth", p.depth);
		o.addProperty("wallHeight", p.wallTop());
		int top = p.wallTop();
		for (var e : g.cells().long2ObjectEntrySet()) {
			if (!e.getValue().state().isAir() && e.getValue().role() == Role.ROOF) {
				top = Math.max(top, net.minecraft.core.BlockPos.of(e.getLongKey()).getY());
			}
		}
		o.addProperty("totalHeight", top + 1);
		o.addProperty("heightToWidth", Math.round(100.0 * (top + 1) / Math.max(p.width, p.depth)) / 100.0);
		return o;
	}

	/** Evaluates {@code requirements} from the plan against the metrics. */
	private JsonArray requirements(JsonObject planJson, JsonObject metrics) {
		JsonArray out = new JsonArray();
		if (!planJson.has("requirements") || !planJson.get("requirements").isJsonObject()) {
			return out;
		}
		JsonObject req = planJson.getAsJsonObject("requirements");
		for (var e : req.entrySet()) {
			JsonObject r = new JsonObject();
			r.addProperty("name", e.getKey());
			boolean pass;
			String detail;
			switch (e.getKey()) {
				case "symmetricFront" -> {
					double s = metrics.getAsJsonObject("facades").getAsJsonObject("front").get("symmetry").getAsDouble();
					pass = !e.getValue().getAsBoolean() || s >= 0.9;
					detail = "front symmetry " + s;
				}
				case "roofSymmetric" -> {
					JsonObject roof = metrics.getAsJsonObject("roof");
					double s = roof.has("symmetry") ? roof.get("symmetry").getAsDouble() : 1;
					boolean even = !roof.has("overhang") || issuesHave("ROOF_OVERHANG_UNEVEN") == false;
					pass = !e.getValue().getAsBoolean() || s >= 0.999 && even;
					detail = "roof symmetry " + s + (even ? "" : ", uneven overhang");
				}
				case "alignedWindows" -> {
					double a = metrics.getAsJsonObject("windows").get("alignment").getAsDouble();
					pass = !e.getValue().getAsBoolean() || a >= 0.75;
					detail = "window alignment " + a;
				}
				case "minWindowsPerFloor" -> {
					int min = Integer.MAX_VALUE;
					for (var f : metrics.getAsJsonObject("windows").getAsJsonObject("perFloor").entrySet()) {
						min = Math.min(min, f.getValue().getAsJsonObject().get("count").getAsInt());
					}
					pass = min >= e.getValue().getAsInt();
					detail = "fewest windows on a floor: " + min;
				}
				case "maxHeight" -> {
					int h = metrics.getAsJsonObject("proportions").get("totalHeight").getAsInt();
					pass = h <= e.getValue().getAsInt();
					detail = "total height " + h;
				}
				case "minWindowRatio" -> {
					double min = Double.MAX_VALUE;
					for (var f : metrics.getAsJsonObject("facades").entrySet()) {
						min = Math.min(min, f.getValue().getAsJsonObject().get("windowRatio").getAsDouble());
					}
					pass = min >= e.getValue().getAsDouble();
					detail = "lowest facade window ratio " + min;
				}
				default -> {
					pass = false;
					detail = "unknown requirement (known: symmetricFront, roofSymmetric, alignedWindows, minWindowsPerFloor, maxHeight, minWindowRatio)";
				}
			}
			r.addProperty("pass", pass);
			r.addProperty("detail", detail);
			out.add(r);
		}
		return out;
	}

	private boolean issuesHave(String code) {
		for (var i : issues) {
			if (i.getAsJsonObject().get("code").getAsString().equals(code)) {
				return true;
			}
		}
		return false;
	}
}
