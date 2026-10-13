package dev.haochen.mcmcp.arch;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.arch.BuildingPlan.Side;
import dev.haochen.mcmcp.view.ViewHook.Shot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Camera positions for inspecting a building: the four sides (named relative to the building,
 * not the compass), an elevated three-quarter view, and one interior view per floor. Exterior
 * cameras are moved closer or higher until trees and terrain outside the building stop blocking
 * the view (checked with server-side ray casts against 9 points on the facade). Server thread.
 */
public final class ViewPlanner {
	public record View(Shot shot, String name, int blockedRays, int rays) {
	}

	private final BuildingGrid g;
	private final BuildingPlan p;
	private final AABB body;
	private final int fov;
	/** The building as it will be (its own blocks, including cleared terrain), by world position. */
	private final it.unimi.dsi.fastutil.longs.Long2ObjectMap<PlanCompiler.WorldCell> built;

	private ViewPlanner(BuildingGrid g, int fov, it.unimi.dsi.fastutil.longs.Long2ObjectMap<PlanCompiler.WorldCell> built) {
		this.g = g;
		this.built = built;
		this.p = g.plan;
		this.fov = fov;
		int top = p.wallTop();
		for (var e : g.cells().long2ObjectEntrySet()) {
			if (e.getValue().role() == BuildingGrid.Role.ROOF && !e.getValue().state().isAir()) {
				top = Math.max(top, BlockPos.of(e.getLongKey()).getY());
			}
		}
		BlockPos a = g.world(0, 0, 0), b = g.world(p.width - 1, top, p.depth - 1);
		this.body = new AABB(Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()),
				Math.max(a.getX(), b.getX()) + 1, b.getY() + 1, Math.max(a.getZ(), b.getZ()) + 1);
	}

	public static List<View> plan(BuildingGrid g, it.unimi.dsi.fastutil.longs.Long2ObjectMap<PlanCompiler.WorldCell> built,
								  List<String> names, int fov) {
		ViewPlanner v = new ViewPlanner(g, fov, built);
		List<View> out = new ArrayList<>();
		for (String n : names) {
			switch (n) {
				case "front" -> out.add(v.side(Side.FRONT, n));
				case "back" -> out.add(v.side(Side.BACK, n));
				case "left" -> out.add(v.side(Side.LEFT, n));
				case "right" -> out.add(v.side(Side.RIGHT, n));
				case "elevated" -> out.add(v.elevated());
				case "interior" -> out.addAll(v.interiors());
				default -> throw dev.haochen.mcmcp.api.Params.invalid("views must be from: front, back, left, right, elevated, interior");
			}
		}
		return out;
	}

	private View side(Side side, String name) {
		Direction out = g.worldDirection(side.out);
		Vec3 c = body.getCenter();
		double halfDepth = out.getAxis() == Direction.Axis.X ? body.getXsize() / 2 : body.getZsize() / 2;
		double facadeWidth = out.getAxis() == Direction.Axis.X ? body.getZsize() : body.getXsize();
		double height = body.getYsize();
		Vec3 face = c.add(out.getStepX() * halfDepth, 0, out.getStepZ() * halfDepth);
		Vec3 target = new Vec3(face.x, body.minY + Math.min(height / 2, p.wallTop() * 0.6 + 1), face.z);
		double fit = Math.max(facadeWidth * 0.85, height) / 2 / Math.tan(Math.toRadians(fov / 2.0)) + 2;
		double min = p.terrainMargin + 2.5;
		List<Vec3> samples = new ArrayList<>();
		Direction along = out.getClockWise();
		for (double u : new double[]{-0.35, 0, 0.35}) {
			for (double v : new double[]{0.2, 0.5, 0.8}) {
				samples.add(new Vec3(face.x + along.getStepX() * u * facadeWidth + out.getStepX() * 0.6,
						body.minY + v * Math.min(height, p.wallTop() + 3),
						face.z + along.getStepZ() * u * facadeWidth + out.getStepZ() * 0.6));
			}
		}
		Vec3 best = null;
		int bestBlocked = Integer.MAX_VALUE;
		for (double d = fit; d >= min - 0.01; d -= Math.max(1, (fit - min) / 6)) {
			for (double up : new double[]{2, 5, 9}) {
				Vec3 cam = face.add(out.getStepX() * d, target.y - face.y + up, out.getStepZ() * d);
				int blocked = blocked(cam, samples);
				if (blocked < bestBlocked) {
					best = cam;
					bestBlocked = blocked;
				}
				if (blocked == 0) {
					break;
				}
			}
			if (bestBlocked == 0) {
				break;
			}
		}
		return view(best, target, name + " (" + out.getName() + " side)", bestBlocked, samples.size());
	}

	private View elevated() {
		Direction front = g.worldDirection(Side.FRONT.out), left = g.worldDirection(Side.LEFT.out);
		Vec3 c = body.getCenter();
		double size = Math.max(Math.max(body.getXsize(), body.getZsize()), body.getYsize());
		double d = size / 2 / Math.tan(Math.toRadians(fov / 2.0)) * 1.3 + 3;
		Vec3 dir = new Vec3(front.getStepX() + left.getStepX(), 0, front.getStepZ() + left.getStepZ()).normalize();
		Vec3 cam = c.add(dir.scale(d)).add(0, size * 0.75, 0);
		List<Vec3> samples = List.of(c, new Vec3(c.x, body.maxY - 0.5, c.z), new Vec3(body.minX, body.minY + 1, body.minZ),
				new Vec3(body.maxX, body.minY + 1, body.maxZ));
		return view(cam, c, "elevated (front-left, above)", blocked(cam, samples), samples.size());
	}

	private List<View> interiors() {
		List<View> out = new ArrayList<>();
		for (BuildingPlan.Floor f : p.floors) {
			BuildingPlan.Room room = p.rooms.stream().filter(r -> r.floor() == f).findFirst().orElse(null);
			int minX = room != null ? room.minX() : 1, maxX = room != null ? room.maxX() : p.width - 2;
			int minZ = room != null ? room.minZ() : 1, maxZ = room != null ? room.maxZ() : p.depth - 2;
			int y = f.feet();
			int[][] corners = {{maxX, maxZ, minX, minZ}, {minX, maxZ, maxX, minZ}, {maxX, minZ, minX, maxZ}, {minX, minZ, maxX, maxZ}};
			int[] pick = corners[0];
			for (int[] cand : corners) {
				if (g.finalState(cand[0], y, cand[1]).isAir() && g.finalState(cand[0], y + 1, cand[1]).isAir()) {
					pick = cand;
					break;
				}
			}
			BlockPos camCell = g.world(pick[0], y, pick[1]), lookCell = g.world(pick[2], y, pick[3]);
			Vec3 cam = Vec3.atBottomCenterOf(camCell).add(0, 1.62, 0);
			Vec3 look = Vec3.atBottomCenterOf(lookCell).add(0, 0.6, 0);
			out.add(view(cam, look, "interior " + f.id() + (room != null ? " (" + room.id() + ")" : ""), 0, 0));
		}
		return out;
	}

	private View view(Vec3 cam, Vec3 look, String label, int blocked, int rays) {
		Vec3 d = look.subtract(cam);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		return new View(new Shot(cam.x, cam.y, cam.z, yaw, pitch, label), label, blocked, rays);
	}

	/**
	 * Rays from the camera to the samples that hit something outside the building first. Marches
	 * through the building as it will look (preview or built), so trees the building clears away
	 * don't count, and anything inside the building's box counts as "seeing the building".
	 */
	private int blocked(Vec3 cam, List<Vec3> samples) {
		AABB inflated = body.inflate(1);
		int n = 0;
		for (Vec3 s : samples) {
			Vec3 d = s.subtract(cam);
			double len = d.length();
			Vec3 step = d.scale(0.25 / len);
			Vec3 at = cam;
			long last = Long.MIN_VALUE;
			for (double t = 0; t < len; t += 0.25, at = at.add(step)) {
				BlockPos pos = BlockPos.containing(at);
				if (pos.asLong() == last) {
					continue;
				}
				last = pos.asLong();
				if (inflated.contains(at)) {
					break; // reached the building
				}
				PlanCompiler.WorldCell c = built.get(pos.asLong());
				var state = c != null ? c.state() : g.level.getBlockState(pos);
				if (!state.isAir() && state.getFluidState().isEmpty() && !state.is(net.minecraft.tags.BlockTags.REPLACEABLE)) {
					n++;
					break;
				}
			}
		}
		if (inflated.contains(cam)) {
			n += samples.size(); // never put an exterior camera inside the building
		}
		return n;
	}

	public static JsonObject describe(View v) {
		JsonObject o = new JsonObject();
		o.addProperty("view", v.name());
		if (v.rays() > 0) {
			o.addProperty("blockedSightLines", v.blockedRays() + "/" + v.rays());
		}
		return o;
	}
}
