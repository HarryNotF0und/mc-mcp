package dev.haochen.mcmcp.arch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.api.Json;
import dev.haochen.mcmcp.api.Params;
import dev.haochen.mcmcp.world.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds level areas of a given size (up to 256 x 256) in a large search area, for big projects
 * (castles, towns, farms) where {@link SiteAnalyzer} (one house, radius 48) is too small. The
 * terrain is sampled on a grid ({@code step} blocks apart), so results are approximate: check the
 * chosen area with get_area_summary / analyze_build_site before building.
 */
public final class FlatFinder {
	public static final int MAX_RADIUS = 256, MAX_SIZE = 256;

	public record Request(BlockPos center, int radius, int width, int depth, int maxHeightDiff, boolean allowWater,
						  boolean rotate, int top, boolean loadChunks) {
	}

	private record Candidate(int x0, int z0, int wx, int wz, int level, int min, int max, long cut, long fill,
							 double trees, double water, double built, double score) {
	}

	private FlatFinder() {
	}

	public static void validate(Request r) {
		if (r.radius < 8 || r.radius > MAX_RADIUS) {
			throw Params.invalid("radius must be 8 to " + MAX_RADIUS);
		}
		if (r.width < 3 || r.depth < 3 || r.width > MAX_SIZE || r.depth > MAX_SIZE) {
			throw Params.invalid("size must be 3-" + MAX_SIZE + " per side");
		}
		if (r.maxHeightDiff < 0 || r.maxHeightDiff > 64) {
			throw Params.invalid("maxHeightDiff must be 0-64");
		}
		if (r.top < 1 || r.top > 10) {
			throw Params.invalid("top must be 1-10");
		}
	}

	/** @param allowed the allowed regions of this dimension; every result lies inside one of them. */
	public static JsonObject find(ServerLevel level, List<Region> allowed, Request r) {
		// About 12 samples across the short side, and at most ~129 x 129 samples in all.
		int step = Math.max(Math.max(1, (2 * r.radius) / 128), Math.min(8, Math.min(r.width, r.depth) / 12));
		int x0 = r.center.getX() - r.radius, z0 = r.center.getZ() - r.radius;
		int n = (2 * r.radius) / step + 1;
		int[] ground = new int[n * n];
		byte[] kind = new byte[n * n]; // 0 unloaded, 1 ground, 2 tree, 3 water/lava, 4 built, 5 void
		int loadedChunks = 0, sampled = 0;
		java.util.Set<Long> chunks = new java.util.HashSet<>();
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				int x = x0 + i * step, z = z0 + j * step;
				if (!level.hasChunk(x >> 4, z >> 4)) {
					if (!r.loadChunks || !inside(allowed, x, z)) {
						continue;
					}
				}
				if (chunks.add(BlockPos.asLong(x >> 4, 0, z >> 4))) {
					loadedChunks++;
				}
				Terrain.Column c = Terrain.column(level, x, z, 96);
				ground[i * n + j] = c.groundY();
				kind[i * n + j] = switch (c.surface()) {
					case GROUND -> 1;
					case TREE -> 2;
					case WATER, LAVA -> 3;
					case BUILT -> 4;
					case VOID -> 5;
				};
				sampled++;
			}
		}

		List<Candidate> all = new ArrayList<>();
		int[][] sizes = r.rotate && r.width != r.depth ? new int[][]{{r.width, r.depth}, {r.depth, r.width}} : new int[][]{{r.width, r.depth}};
		int[] hs = new int[n * n];
		for (int[] size : sizes) {
			int cw = Math.max(1, (size[0] - 1) / step + 1), cd = Math.max(1, (size[1] - 1) / step + 1);
			for (int i = 0; i + cw <= n; i++) {
				for (int j = 0; j + cd <= n; j++) {
					int wx0 = x0 + i * step, wz0 = z0 + j * step;
					Region box = new Region(new BlockPos(wx0, 0, wz0), new BlockPos(wx0 + size[0] - 1, 0, wz0 + size[1] - 1));
					if (!insideXZ(allowed, box)) {
						continue;
					}
					Candidate c = evaluate(ground, kind, hs, n, i, j, cw, cd, wx0, wz0, size, step, r);
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
			if (chosen.stream().anyMatch(o -> c.x0 < o.x0 + o.wx && o.x0 < c.x0 + c.wx && c.z0 < o.z0 + o.wz && o.z0 < c.z0 + c.wz)) {
				continue;
			}
			chosen.add(c);
			out.add(toJson(c, r));
			if (chosen.size() >= r.top) {
				break;
			}
		}
		JsonObject o = new JsonObject();
		int[] kinds = new int[6];
		for (byte k : kind) {
			kinds[k]++;
		}
		JsonObject surface = new JsonObject();
		String[] names = {"unloaded", "ground", "trees", "water", "built", "void"};
		for (int k = 0; k < 6; k++) {
			if (kinds[k] > 0) {
				surface.addProperty(names[k], kinds[k]);
			}
		}
		o.add("surface", surface);
		o.addProperty("sampleStep", step);
		o.addProperty("sampledColumns", sampled);
		o.addProperty("chunks", loadedChunks);
		long total = (long) n * n;
		o.addProperty("coverage", Math.round(100.0 * sampled / total) / 100.0);
		if (sampled < total) {
			o.addProperty("note", "only loaded chunks were searched (" + Math.round(100.0 * sampled / total)
					+ "% of the area); pass loadChunks: true to load the rest (slow for large radii), or search nearer the player");
		}
		o.add("candidates", out);
		if (out.isEmpty()) {
			o.addProperty("hint", "nothing level enough: raise maxHeightDiff (terrain will be cut/filled), allow water, "
					+ "shrink the size, or search elsewhere");
		}
		return o;
	}

	private static Candidate evaluate(int[] ground, byte[] kind, int[] hs, int n, int i0, int j0, int cw, int cd,
									  int wx0, int wz0, int[] size, int step, Request r) {
		int count = 0, trees = 0, water = 0, built = 0;
		for (int i = i0; i < i0 + cw; i++) {
			for (int j = j0; j < j0 + cd; j++) {
				byte k = kind[i * n + j];
				if (k == 0 || k == 5) {
					return null; // unloaded or void
				}
				if (k == 3) {
					if (!r.allowWater) {
						return null;
					}
					water++;
				}
				if (k == 4) {
					built++;
				}
				if (k == 2) {
					trees++;
				}
				hs[count++] = ground[i * n + j];
			}
		}
		if (built > count * 0.02) {
			return null; // someone's builds are in the way
		}
		Arrays.sort(hs, 0, count);
		int min = hs[0], max = hs[count - 1];
		// Trim the extreme 2% (single rocks, holes) before judging flatness.
		int lo = hs[count / 50], hi = hs[count - 1 - count / 50];
		if (hi - lo > r.maxHeightDiff) {
			return null;
		}
		int levelY = hs[count / 2];
		long cut = 0, fill = 0;
		for (int t = 0; t < count; t++) {
			int d = hs[t] - levelY;
			if (d > 0) {
				cut += d;
			} else {
				fill -= d;
			}
		}
		double area = (double) size[0] * size[1] / count; // blocks per sample
		cut = Math.round(cut * area);
		fill = Math.round(fill * area);
		double score = (hi - lo) * 10.0 + (cut + fill) / (double) (size[0] * size[1]) * 20.0 + 5.0 * trees / count
				+ 30.0 * water / count;
		return new Candidate(wx0, wz0, size[0], size[1], levelY, min, max, cut, fill, (double) trees / count,
				(double) water / count, (double) built / count, score);
	}

	private static JsonObject toJson(Candidate c, Request r) {
		JsonObject j = new JsonObject();
		j.add("min", Json.blockPos(new BlockPos(c.x0, c.level, c.z0)));
		j.add("max", Json.blockPos(new BlockPos(c.x0 + c.wx - 1, c.level, c.z0 + c.wz - 1)));
		JsonArray size = new JsonArray();
		size.add(c.wx);
		size.add(c.wz);
		j.add("sizeXZ", size);
		j.addProperty("rotated", c.wx != r.width);
		j.addProperty("groundY", c.level);
		j.addProperty("groundMin", c.min);
		j.addProperty("groundMax", c.max);
		j.addProperty("cut", c.cut);
		j.addProperty("fill", c.fill);
		j.addProperty("trees", Math.round(c.trees * 100) / 100.0);
		j.addProperty("water", Math.round(c.water * 100) / 100.0);
		j.addProperty("score", Math.round(c.score * 10) / 10.0);
		return j;
	}

	private static boolean inside(List<Region> allowed, int x, int z) {
		for (Region a : allowed) {
			if (x >= a.min().getX() && x <= a.max().getX() && z >= a.min().getZ() && z <= a.max().getZ()) {
				return true;
			}
		}
		return false;
	}

	private static boolean insideXZ(List<Region> allowed, Region box) {
		for (Region a : allowed) {
			if (box.min().getX() >= a.min().getX() && box.max().getX() <= a.max().getX()
					&& box.min().getZ() >= a.min().getZ() && box.max().getZ() <= a.max().getZ()) {
				return true;
			}
		}
		return false;
	}
}
