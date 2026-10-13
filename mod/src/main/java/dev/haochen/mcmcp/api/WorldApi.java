package dev.haochen.mcmcp.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.net.RpcDispatcher;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.Blocks;
import dev.haochen.mcmcp.world.Guard;
import dev.haochen.mcmcp.world.LayerFormat;
import dev.haochen.mcmcp.world.Region;
import dev.haochen.mcmcp.worldedit.WorldEditBridge;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only world queries. All run on the server thread. */
public final class WorldApi {
	public static final String DEFAULT_DIMENSION = "minecraft:overworld";
	private static final int MAX_SLICE_SIDE = 32;
	private static final int MAX_ENTITIES = 50;
	private static final int MAX_FLUID_SAMPLES = 32;
	private static final int HEIGHTMAP_CELLS = 32;

	private WorldApi() {
	}

	public static void register(RpcDispatcher dispatcher) {
		dispatcher.onMainThread("world.getInfo", (server, params) -> getInfo(server));
		dispatcher.onMainThread("world.getPlayerState", WorldApi::getPlayerState);
		dispatcher.onMainThread("world.getBlock", WorldApi::getBlock);
		dispatcher.onMainThread("world.getAreaSummary", WorldApi::getAreaSummary);
		dispatcher.onMainThread("world.getLayerSlices", WorldApi::getLayerSlices);
	}

	public static ServerLevel level(MinecraftServer server, JsonObject params) {
		return Guard.level(server, Params.string(params, "dimension", DEFAULT_DIMENSION));
	}

	private static JsonObject getInfo(MinecraftServer server) {
		JsonObject result = new JsonObject();
		result.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().getName());
		result.addProperty("modVersion", McMcpMod.version());
		result.addProperty("dedicated", server.isDedicatedServer());

		JsonArray dimensions = new JsonArray();
		for (ResourceKey<Level> key : server.levelKeys()) {
			dimensions.add(key.location().toString());
		}
		result.add("dimensions", dimensions);

		JsonArray players = new JsonArray();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			JsonObject p = new JsonObject();
			p.addProperty("name", player.getGameProfile().getName());
			p.addProperty("uuid", player.getStringUUID());
			p.addProperty("dimension", player.level().dimension().location().toString());
			p.add("pos", Json.blockPos(player.blockPosition()));
			if (player instanceof dev.haochen.mcmcp.agent.AgentPlayer) {
				p.addProperty("agent", true);
			}
			players.add(p);
		}
		result.add("players", players);

		ServerLevel overworld = server.overworld();
		result.addProperty("gameTime", overworld.getGameTime());
		result.addProperty("dayTime", overworld.getDayTime());
		result.addProperty("difficulty", server.getWorldData().getDifficulty().getKey());
		result.addProperty("mspt", Math.round(server.getAverageTickTimeNanos() / 1e4) / 100.0);
		// Single-player pauses when the pause menu is open (or the window loses focus); jobs then wait.
		result.addProperty("paused", server.isPaused());
		WorldEditBridge we = WorldEditBridge.get();
		if (we != null) {
			result.addProperty("worldEdit", we.version());
		}
		return result;
	}

	private static JsonObject getPlayerState(MinecraftServer server, JsonObject params) {
		ServerPlayer player;
		if (Params.has(params, "name")) {
			player = server.getPlayerList().getPlayerByName(Params.string(params, "name"));
			if (player == null) {
				throw Params.invalid("player not online: " + Params.string(params, "name"));
			}
		} else {
			List<ServerPlayer> players = server.getPlayerList().getPlayers();
			if (players.isEmpty()) {
				throw Params.invalid("no players online");
			}
			player = players.get(0);
		}

		JsonObject o = new JsonObject();
		o.addProperty("name", player.getGameProfile().getName());
		o.addProperty("dimension", player.level().dimension().location().toString());
		JsonArray pos = new JsonArray();
		pos.add(round2(player.getX()));
		pos.add(round2(player.getY()));
		pos.add(round2(player.getZ()));
		o.add("pos", pos);
		o.add("blockPos", Json.blockPos(player.blockPosition()));
		o.addProperty("yaw", round2(player.getYRot()));
		o.addProperty("pitch", round2(player.getXRot()));
		o.addProperty("facing", player.getDirection().getName());
		o.addProperty("gameMode", player.gameMode.getGameModeForPlayer().getName());
		o.addProperty("health", player.getHealth());
		o.addProperty("food", player.getFoodData().getFoodLevel());
		o.add("mainHand", item(player.getMainHandItem()));
		o.add("offHand", item(player.getOffhandItem()));
		HitResult hit = player.pick(64, 1.0f, false);
		if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
			JsonObject look = new JsonObject();
			look.add("pos", Json.blockPos(bhr.getBlockPos()));
			look.addProperty("face", bhr.getDirection().getName());
			look.addProperty("block", Blocks.toString(player.level().getBlockState(bhr.getBlockPos())));
			o.add("lookingAt", look);
		}
		return o;
	}

	private static JsonObject getBlock(MinecraftServer server, JsonObject params) {
		ServerLevel level = level(server, params);
		BlockPos pos = Params.pos(params, "pos");
		Guard.checkChunks(level, new Region(pos, pos), Params.bool(params, "loadChunks", false));
		BlockState state = level.getBlockState(pos);
		JsonObject o = new JsonObject();
		o.add("pos", Json.blockPos(pos));
		o.addProperty("block", Blocks.toString(state));
		BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
		if (be != null) {
			o.addProperty("nbt", be.saveWithoutMetadata(level.registryAccess()).toString());
		}
		return o;
	}

	private static JsonObject getLayerSlices(MinecraftServer server, JsonObject params) {
		ServerLevel level = level(server, params);
		Region region = Params.region(params, "region");
		if (region.sizeX() > MAX_SLICE_SIDE || region.sizeY() > MAX_SLICE_SIDE || region.sizeZ() > MAX_SLICE_SIDE) {
			JsonObject data = new JsonObject();
			data.addProperty("maxSide", MAX_SLICE_SIDE);
			throw new RpcException(RpcException.VOLUME_TOO_LARGE,
					"layer slices are limited to 32 x 32 x 32; split the region or use getAreaSummary", data);
		}
		checkReadable(level, region);
		Guard.checkChunks(level, region, Params.bool(params, "loadChunks", false));
		return LayerFormat.encode(level, region);
	}

	private static JsonObject getAreaSummary(MinecraftServer server, JsonObject params) {
		ServerLevel level = level(server, params);
		Region region = Params.region(params, "region");
		int topN = Math.max(1, Params.integer(params, "top", 10));
		McMcpMod.services().guard().checkVolume(region.volume());
		checkReadable(level, region);
		Guard.checkChunks(level, region, Params.bool(params, "loadChunks", false));

		Map<Block, Integer> counts = new HashMap<>();
		int air = 0;
		FluidStats water = new FluidStats(), lava = new FluidStats();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = region.min().getY(); y <= region.max().getY(); y++) {
			for (int z = region.min().getZ(); z <= region.max().getZ(); z++) {
				for (int x = region.min().getX(); x <= region.max().getX(); x++) {
					pos.set(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (state.isAir()) {
						air++;
						continue;
					}
					counts.merge(state.getBlock(), 1, Integer::sum);
					FluidState fluid = state.getFluidState();
					if (!fluid.isEmpty()) {
						(fluid.is(FluidTags.LAVA) ? lava : water).add(pos, fluid.isSource());
					}
				}
			}
		}

		JsonObject o = new JsonObject();
		o.add("region", region.toJson());
		o.addProperty("volume", region.volume());
		o.addProperty("air", air);
		List<Map.Entry<Block, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort((a, b) -> b.getValue() - a.getValue());
		JsonArray blocks = new JsonArray();
		int other = 0;
		for (int i = 0; i < sorted.size(); i++) {
			if (i < topN) {
				JsonObject b = new JsonObject();
				b.addProperty("block", BuiltInRegistries.BLOCK.getKey(sorted.get(i).getKey()).toString());
				b.addProperty("count", sorted.get(i).getValue());
				blocks.add(b);
			} else {
				other += sorted.get(i).getValue();
			}
		}
		o.add("blocks", blocks);
		if (other > 0) {
			o.addProperty("otherBlocks", other);
		}
		o.add("heightmap", heightmap(level, region));
		JsonObject fluids = new JsonObject();
		fluids.add("water", water.toJson());
		fluids.add("lava", lava.toJson());
		o.add("fluids", fluids);
		o.add("entities", entities(level, region));
		return o;
	}

	/** Surface height (y of the highest non-air block) on a grid of at most 32 x 32 cells. */
	private static JsonObject heightmap(ServerLevel level, Region region) {
		int step = Math.max(1, (Math.max(region.sizeX(), region.sizeZ()) + HEIGHTMAP_CELLS - 1) / HEIGHTMAP_CELLS);
		JsonArray rows = new JsonArray();
		for (int z = region.min().getZ(); z <= region.max().getZ(); z += step) {
			JsonArray row = new JsonArray();
			for (int x = region.min().getX(); x <= region.max().getX(); x += step) {
				row.add(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
			}
			rows.add(row);
		}
		JsonObject o = new JsonObject();
		o.addProperty("step", step);
		JsonArray origin = new JsonArray();
		origin.add(region.min().getX());
		origin.add(region.min().getZ());
		o.add("originXZ", origin);
		o.addProperty("layout", "rows[j][k] = surface y at x = originXZ[0] + k*step, z = originXZ[1] + j*step");
		o.add("rows", rows);
		return o;
	}

	private static JsonObject entities(ServerLevel level, Region region) {
		AABB box = new AABB(region.min().getX(), region.min().getY(), region.min().getZ(),
				region.max().getX() + 1, region.max().getY() + 1, region.max().getZ() + 1);
		List<Entity> found = level.getEntities((Entity) null, box, e -> true);
		JsonArray list = new JsonArray();
		for (Entity e : found.subList(0, Math.min(found.size(), MAX_ENTITIES))) {
			JsonObject j = new JsonObject();
			j.addProperty("type", EntityType.getKey(e.getType()).toString());
			if (e instanceof ServerPlayer || e.hasCustomName()) {
				j.addProperty("name", e.getName().getString());
			}
			j.add("pos", Json.blockPos(e.blockPosition()));
			list.add(j);
		}
		JsonObject o = new JsonObject();
		o.addProperty("total", found.size());
		o.add("list", list);
		return o;
	}

	/** Reads are not limited by the allowlist, but must stay inside build height. */
	private static void checkReadable(ServerLevel level, Region region) {
		if (region.min().getY() < level.getMinBuildHeight() || region.max().getY() >= level.getMaxBuildHeight()) {
			throw Params.invalid("y must be within build height [" + level.getMinBuildHeight() + ", "
					+ (level.getMaxBuildHeight() - 1) + "]");
		}
	}

	private static JsonObject item(ItemStack stack) {
		JsonObject o = new JsonObject();
		o.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		o.addProperty("count", stack.getCount());
		return o;
	}

	private static double round2(double v) {
		return Math.round(v * 100) / 100.0;
	}

	private static final class FluidStats {
		int count;
		int sources;
		final JsonArray sample = new JsonArray();

		void add(BlockPos pos, boolean source) {
			count++;
			if (source) {
				sources++;
			}
			if (sample.size() < MAX_FLUID_SAMPLES) {
				sample.add(Json.blockPos(pos));
			}
		}

		JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("count", count);
			o.addProperty("sources", sources);
			if (count > 0) {
				o.add("sample", sample);
			}
			return o;
		}
	}
}
