package dev.haochen.mcmcp.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpConfig;
import dev.haochen.mcmcp.api.Params;
import dev.haochen.mcmcp.net.RpcException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import dev.haochen.mcmcp.worldedit.WorldEditBridge;

/** Safety checks from {@code config/mcmcp.json}: region allowlist, volume cap, block blacklist. */
public final class Guard {
	private final McMcpConfig config;

	public Guard(McMcpConfig config) {
		this.config = config;
	}

	/** Resolves a dimension id such as {@code minecraft:overworld}. */
	public static ServerLevel level(MinecraftServer server, String dimension) {
		ResourceLocation id = ResourceLocation.tryParse(dimension);
		ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
		if (level == null) {
			throw Params.invalid("unknown dimension: " + dimension);
		}
		return level;
	}

	/** Every block of {@code region} must lie inside one allowed region of this dimension and inside build height. */
	public void checkRegion(ServerLevel level, Region region) {
		if (region.min().getY() < level.getMinBuildHeight() || region.max().getY() >= level.getMaxBuildHeight()) {
			throw Params.invalid("y must be within build height [" + level.getMinBuildHeight() + ", "
					+ (level.getMaxBuildHeight() - 1) + "]");
		}
		String dimension = level.dimension().location().toString();
		JsonArray allowed = new JsonArray();
		for (McMcpConfig.Region r : config.allowedRegions) {
			if (!r.dimension().equals(dimension)) {
				continue;
			}
			Region box = Region.of(new BlockPos(r.min()[0], r.min()[1], r.min()[2]), new BlockPos(r.max()[0], r.max()[1], r.max()[2]));
			if (box.contains(region)) {
				return;
			}
			allowed.add(box.toJson());
		}
		JsonObject data = new JsonObject();
		data.add("requested", region.toJson());
		data.add("allowed", allowed);
		throw new RpcException(RpcException.OUTSIDE_ALLOWED_REGION,
				"region is outside the allowed regions of " + dimension, data);
	}

	public void checkVolume(long volume) {
		if (volume > config.maxVolumePerOp) {
			JsonObject data = new JsonObject();
			data.addProperty("volume", volume);
			data.addProperty("max", config.maxVolumePerOp);
			throw new RpcException(RpcException.VOLUME_TOO_LARGE,
					"operation covers " + volume + " blocks, the limit is " + config.maxVolumePerOp, data);
		}
	}

	/** Parses a block string and rejects blacklisted blocks (also error {@code 1003}). */
	public BlockState block(String text) {
		return checkAllowed(Blocks.parse(text));
	}

	/** Error {@code 1003} if the state's block is blacklisted. */
	public BlockState checkAllowed(BlockState state) {
		String id = Blocks.id(state);
		if (config.blockBlacklist.contains(id)) {
			JsonObject data = new JsonObject();
			data.addProperty("block", id);
			throw new RpcException(RpcException.UNKNOWN_BLOCK, "block " + id + " is blacklisted", data);
		}
		return state;
	}

	/**
	 * A block state, or with WorldEdit installed any WorldEdit pattern ({@code 50%stone,50%andesite},
	 * {@code ##wool}, ...). Every block a pattern produces is checked against the blacklist.
	 */
	public BlockPattern pattern(String text) {
		BlockState state;
		try {
			state = Blocks.parse(text);
		} catch (RpcException notABlock) {
			WorldEditBridge we = WorldEditBridge.get();
			if (we == null) {
				if (text.matches(".*[%,#^*].*")) {
					throw new RpcException(notABlock.code(), notABlock.getMessage()
							+ " (patterns like 50%stone,50%andesite need WorldEdit installed)", notABlock.data());
				}
				throw notABlock;
			}
			BlockPattern p = we.parsePattern(text);
			return (x, y, z) -> checkAllowed(p.at(x, y, z));
		}
		return BlockPattern.of(checkAllowed(state));
	}

	/**
	 * Fails with {@code 1004} unless every chunk overlapping {@code region} is loaded.
	 * With {@code load = true}, missing chunks are loaded (and generated if needed) instead.
	 */
	public static void checkChunks(ServerLevel level, Region region, boolean load) {
		int minCx = region.min().getX() >> 4, maxCx = region.max().getX() >> 4;
		int minCz = region.min().getZ() >> 4, maxCz = region.max().getZ() >> 4;
		int missing = 0;
		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cz = minCz; cz <= maxCz; cz++) {
				if (!level.hasChunk(cx, cz)) {
					if (load) {
						level.getChunk(cx, cz);
					} else {
						missing++;
					}
				}
			}
		}
		if (missing > 0) {
			JsonObject data = new JsonObject();
			data.addProperty("unloadedChunks", missing);
			throw new RpcException(RpcException.CHUNK_NOT_LOADED,
					missing + " chunk(s) in the region are not loaded; move a player closer or pass loadChunks=true", data);
		}
	}
}
