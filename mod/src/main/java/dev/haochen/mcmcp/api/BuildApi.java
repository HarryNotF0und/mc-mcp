package dev.haochen.mcmcp.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.Services;
import dev.haochen.mcmcp.jobs.Job;
import dev.haochen.mcmcp.jobs.PlaceJob;
import dev.haochen.mcmcp.jobs.PlaceJob.Placement;
import dev.haochen.mcmcp.net.RpcDispatcher;
import dev.haochen.mcmcp.world.BlockPattern;
import dev.haochen.mcmcp.world.Guard;
import dev.haochen.mcmcp.world.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * World-modifying methods. Each validates everything up front, queues a {@link PlaceJob}
 * and returns its job id immediately; progress arrives as {@code job.progress} / {@code job.finished}.
 */
public final class BuildApi {
	public static final int MAX_PLACE_BLOCKS = 4096;

	private BuildApi() {
	}

	public static void register(RpcDispatcher dispatcher) {
		dispatcher.onMainThread("world.placeBlocks", BuildApi::placeBlocks);
		dispatcher.onMainThread("world.fillRegion", BuildApi::fillRegion);
		dispatcher.onMainThread("world.clearRegion", BuildApi::clearRegion);
	}

	public static JsonObject placeBlocks(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		ServerLevel level = WorldApi.level(server, params);
		JsonArray blocks = Params.array(params, "blocks");
		if (blocks.isEmpty()) {
			throw Params.invalid("blocks is empty");
		}
		if (blocks.size() > MAX_PLACE_BLOCKS) {
			throw Params.invalid("at most " + MAX_PLACE_BLOCKS + " blocks per call, got " + blocks.size()
					+ "; use fillRegion for boxes or split the list");
		}
		Map<String, BlockState> parsed = new HashMap<>();
		List<Placement> placements = new ArrayList<>(blocks.size());
		Region bounds = null;
		for (int i = 0; i < blocks.size(); i++) {
			JsonElement e = blocks.get(i);
			BlockPos pos;
			String block;
			if (e.isJsonArray() && e.getAsJsonArray().size() == 4) {
				JsonArray a = e.getAsJsonArray();
				JsonArray xyz = new JsonArray();
				xyz.add(a.get(0));
				xyz.add(a.get(1));
				xyz.add(a.get(2));
				pos = Params.pos(xyz, "blocks[" + i + "]");
				if (!a.get(3).isJsonPrimitive() || !a.get(3).getAsJsonPrimitive().isString()) {
					throw Params.invalid("blocks[" + i + "][3] must be a block string");
				}
				block = a.get(3).getAsString();
			} else if (e.isJsonObject()) {
				pos = Params.pos(e.getAsJsonObject(), "pos");
				block = Params.string(e.getAsJsonObject(), "block");
			} else {
				throw Params.invalid("blocks[" + i + "] must be [x, y, z, \"block\"] or {\"pos\":[x,y,z],\"block\":\"...\"}");
			}
			BlockState state = parsed.computeIfAbsent(block, s.guard()::block);
			placements.add(new Placement(pos, state));
			bounds = bounds == null ? new Region(pos, pos) : bounds.include(pos);
		}
		for (Placement p : placements) {
			s.guard().checkRegion(level, new Region(p.pos(), p.pos()));
		}
		Guard.checkChunks(level, bounds, Params.loadChunks(params));
		return submit(s, new PlaceJob("placeBlocks", "place " + placements.size() + " blocks", level,
				placements.size(), placements.iterator(), s.history()));
	}

	public static JsonObject fillRegion(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		ServerLevel level = WorldApi.level(server, params);
		Region region = Params.region(params, "region");
		BlockPattern block = s.guard().pattern(Params.string(params, "block"));
		String mode = Params.string(params, "mode", "solid");
		BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		StateFor stateFor = switch (mode) {
			case "solid", "outline" -> p -> block.at(p.getX(), p.getY(), p.getZ());
			case "hollow" -> p -> region.onShell(p) ? block.at(p.getX(), p.getY(), p.getZ()) : air;
			default -> throw Params.invalid("mode must be solid, hollow or outline");
		};
		String description = "fill " + mode + " " + describe(region) + " with " + Params.string(params, "block");
		return submitAreas(s, level, region, params, "fillRegion", description, mode.equals("outline"), stateFor);
	}

	public static JsonObject clearRegion(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		ServerLevel level = WorldApi.level(server, params);
		Region region = Params.region(params, "region");
		BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		return submitAreas(s, level, region, params, "clearRegion", "clear " + describe(region), false, p -> air);
	}

	/**
	 * One job, or with {@code split: true} for regions above maxVolumePerOp, one job per part (each its
	 * own undo step, run in order). The result is the last job, which finishes last, plus all job ids.
	 */
	private static JsonObject submitAreas(Services s, ServerLevel level, Region region, JsonObject params, String kind,
			String description, boolean outline, StateFor stateFor) {
		long volume = outline ? shellCount(region, region) : region.volume();
		boolean split = Params.bool(params, "split", false) && volume > s.guard().maxVolume();
		if (split) {
			s.guard().checkSplitVolume(volume);
		} else {
			s.guard().checkVolume(volume);
		}
		s.guard().checkRegion(level, region);
		Guard.checkChunks(level, region, Params.loadChunks(params));
		if (!split) {
			return submit(s, new PlaceJob(kind, description, level, (int) volume,
					positions(region, outline ? region : null, stateFor), s.history()));
		}
		List<Region> parts = region.split(s.guard().maxVolume());
		JsonArray ids = new JsonArray();
		JsonObject last = null;
		for (int i = 0; i < parts.size(); i++) {
			Region part = parts.get(i);
			long count = outline ? shellCount(region, part) : part.volume();
			if (count == 0) {
				continue;
			}
			last = submit(s, new PlaceJob(kind, description + " (part " + (i + 1) + "/" + parts.size() + ": " + describe(part) + ")",
					level, (int) count, positions(part, outline ? region : null, stateFor), s.history()));
			ids.add(last.get("jobId"));
		}
		last.add("jobIds", ids);
		last.addProperty("note", "split into " + ids.size() + " jobs (one undo step each, run in order); this is the last one");
		return last;
	}

	static JsonObject submit(Services s, Job job) {
		s.jobs().submit(job);
		JsonObject o = job.toJson();
		if (McMcpMod.server() != null && McMcpMod.server().isPaused()) {
			o.addProperty("warning", PAUSED_WARNING);
		}
		return o;
	}

	public static final String PAUSED_WARNING = "the game is paused (pause menu open or window unfocused), so jobs wait; "
			+ "close the menu, or press F3+P in game to keep running when unfocused";

	private static String describe(Region r) {
		return r.sizeX() + "x" + r.sizeY() + "x" + r.sizeZ() + " at " + r.min().toShortString();
	}

	/** Blocks of {@code area} on the outer shell of {@code shell}. */
	private static long shellCount(Region shell, Region area) {
		int ix0 = Math.max(area.min().getX(), shell.min().getX() + 1), ix1 = Math.min(area.max().getX(), shell.max().getX() - 1);
		int iy0 = Math.max(area.min().getY(), shell.min().getY() + 1), iy1 = Math.min(area.max().getY(), shell.max().getY() - 1);
		int iz0 = Math.max(area.min().getZ(), shell.min().getZ() + 1), iz1 = Math.min(area.max().getZ(), shell.max().getZ() - 1);
		long inner = (long) Math.max(0, ix1 - ix0 + 1) * Math.max(0, iy1 - iy0 + 1) * Math.max(0, iz1 - iz0 + 1);
		return area.volume() - inner;
	}

	private interface StateFor {
		BlockState at(BlockPos pos);
	}

	/** Lazily walks {@code area} bottom-up (y, then z, then x); with a {@code shell}, only positions on its outer shell. */
	private static Iterator<Placement> positions(Region area, Region shell, StateFor stateFor) {
		return new Iterator<>() {
			private int x = area.min().getX(), y = area.min().getY(), z = area.min().getZ();
			private boolean exhausted;

			{
				skipInterior();
			}

			@Override
			public boolean hasNext() {
				return !exhausted;
			}

			@Override
			public Placement next() {
				if (exhausted) {
					throw new NoSuchElementException();
				}
				BlockPos pos = new BlockPos(x, y, z);
				advance();
				skipInterior();
				return new Placement(pos, stateFor.at(pos));
			}

			private void advance() {
				if (++x > area.max().getX()) {
					x = area.min().getX();
					if (++z > area.max().getZ()) {
						z = area.min().getZ();
						if (++y > area.max().getY()) {
							exhausted = true;
						}
					}
				}
			}

			private void skipInterior() {
				if (shell == null) {
					return;
				}
				while (!exhausted && !shell.onShell(new BlockPos(x, y, z))) {
					// Inside the shell: jump to its far wall in this row, or on to the next row.
					int far = Math.min(area.max().getX(), shell.max().getX());
					if (x < far) {
						x = far;
					} else {
						advance();
					}
				}
			}
		};
	}
}
