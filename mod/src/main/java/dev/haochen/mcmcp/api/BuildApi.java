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
		Guard.checkChunks(level, bounds, Params.bool(params, "loadChunks", false));
		return submit(s, new PlaceJob("placeBlocks", "place " + placements.size() + " blocks", level,
				placements.size(), placements.iterator(), s.history()));
	}

	public static JsonObject fillRegion(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		ServerLevel level = WorldApi.level(server, params);
		Region region = Params.region(params, "region");
		BlockState block = s.guard().block(Params.string(params, "block"));
		String mode = Params.string(params, "mode", "solid");
		BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		Iterator<Placement> source;
		long total;
		switch (mode) {
			case "solid" -> {
				source = positions(region, false, p -> block);
				total = region.volume();
			}
			case "hollow" -> {
				source = positions(region, false, p -> region.onShell(p) ? block : air);
				total = region.volume();
			}
			case "outline" -> {
				source = positions(region, true, p -> block);
				total = shellSize(region);
			}
			default -> throw Params.invalid("mode must be solid, hollow or outline");
		}
		checkWrite(s, level, region, params);
		String description = "fill " + mode + " " + describe(region) + " with " + Params.string(params, "block");
		return submit(s, new PlaceJob("fillRegion", description, level, (int) total, source, s.history()));
	}

	public static JsonObject clearRegion(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		ServerLevel level = WorldApi.level(server, params);
		Region region = Params.region(params, "region");
		checkWrite(s, level, region, params);
		BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		return submit(s, new PlaceJob("clearRegion", "clear " + describe(region), level,
				(int) region.volume(), positions(region, false, p -> air), s.history()));
	}

	private static void checkWrite(Services s, ServerLevel level, Region region, JsonObject params) {
		s.guard().checkVolume(region.volume());
		s.guard().checkRegion(level, region);
		Guard.checkChunks(level, region, Params.bool(params, "loadChunks", false));
	}

	static JsonObject submit(Services s, Job job) {
		s.jobs().submit(job);
		return job.toJson();
	}

	private static String describe(Region r) {
		return r.sizeX() + "x" + r.sizeY() + "x" + r.sizeZ() + " at " + r.min().toShortString();
	}

	private static long shellSize(Region r) {
		long inner = (long) Math.max(0, r.sizeX() - 2) * Math.max(0, r.sizeY() - 2) * Math.max(0, r.sizeZ() - 2);
		return r.volume() - inner;
	}

	private interface StateFor {
		BlockState at(BlockPos pos);
	}

	/** Lazily walks the region bottom-up (y, then z, then x), optionally only its shell. */
	private static Iterator<Placement> positions(Region region, boolean shellOnly, StateFor stateFor) {
		return new Iterator<>() {
			private int x = region.min().getX(), y = region.min().getY(), z = region.min().getZ();
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
				if (++x > region.max().getX()) {
					x = region.min().getX();
					if (++z > region.max().getZ()) {
						z = region.min().getZ();
						if (++y > region.max().getY()) {
							exhausted = true;
						}
					}
				}
			}

			private void skipInterior() {
				if (!shellOnly) {
					return;
				}
				while (!exhausted && !region.onShell(new BlockPos(x, y, z))) {
					// Jump straight to the far wall of this row.
					x = region.max().getX();
				}
			}
		};
	}
}
