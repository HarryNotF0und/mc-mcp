package dev.haochen.mcmcp.api;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.Services;
import dev.haochen.mcmcp.blueprint.Blueprint;
import dev.haochen.mcmcp.net.RpcDispatcher;
import dev.haochen.mcmcp.world.BlockList;
import dev.haochen.mcmcp.world.Guard;
import dev.haochen.mcmcp.world.LayerFormat;
import dev.haochen.mcmcp.world.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.nio.file.Path;

/** {@code build.placeBlueprint} and {@code build.saveRegionAsBlueprint}. Server thread. */
public final class BlueprintApi {
	private BlueprintApi() {
	}

	public static void register(RpcDispatcher dispatcher) {
		dispatcher.onMainThread("build.placeBlueprint", BlueprintApi::placeBlueprint);
		dispatcher.onMainThread("build.saveRegionAsBlueprint", BlueprintApi::saveRegion);
	}

	public static JsonObject placeBlueprint(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		Plan plan = plan(server, params);
		if (Params.bool(params, "dryRun", false)) {
			JsonObject stats = plan.stats();
			stats.addProperty("dryRun", true);
			return stats;
		}
		return plan.submit(s);
	}

	/**
	 * Resolves and transforms the blueprint described by {@code params}
	 * ({@code blueprint | name, origin?, rotation?, mirror?, skipAir?, dimension?, loadChunks?}). Server thread.
	 */
	public static Plan plan(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		ServerLevel level = WorldApi.level(server, params);
		JsonObject json;
		String label;
		if (Params.has(params, "blueprint")) {
			json = Params.object(params, "blueprint");
			label = "blueprint";
		} else if (Params.has(params, "name")) {
			label = Params.string(params, "name");
			json = s.blueprints().load(label);
		} else {
			throw Params.invalid("pass either blueprint (the JSON object) or name (a saved blueprint)");
		}
		Blueprint bp = Blueprint.parse(json, s.guard());
		BlockPos at;
		if (Params.has(params, "origin")) {
			at = Params.pos(params, "origin");
		} else if (bp.origin() != null) {
			at = bp.origin();
		} else {
			throw Params.invalid("origin is required when the blueprint has none");
		}
		int degrees = Params.integer(params, "rotation", 0);
		Rotation rotation = Blueprint.rotation(degrees);
		String mirrorAxis = Params.string(params, "mirror", "none");
		Mirror mirror = Blueprint.mirror(mirrorAxis);
		BlockList list = bp.place(at, rotation, mirror, Params.bool(params, "skipAir", false));
		String description = "place " + label + " at " + at.toShortString()
				+ (degrees != 0 ? " rotated " + degrees : "") + (mirror != Mirror.NONE ? " mirrored " + mirrorAxis : "");
		return new Plan(level, list, "placeBlueprint", description, Params.bool(params, "loadChunks", false), list.stats(20));
	}

	public static JsonObject saveRegion(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		ServerLevel level = WorldApi.level(server, params);
		Region region = Params.region(params, "region");
		s.guard().checkVolume(region.volume());
		if (region.min().getY() < level.getMinBuildHeight() || region.max().getY() >= level.getMaxBuildHeight()) {
			throw Params.invalid("y must be within build height");
		}
		Guard.checkChunks(level, region, Params.bool(params, "loadChunks", false));
		JsonObject blueprint = LayerFormat.encode(level, region);
		if (!Params.has(params, "name")) {
			return blueprint;
		}
		String name = Params.string(params, "name");
		Path file = s.blueprints().save(name, blueprint);
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("path", file.toAbsolutePath().toString());
		o.add("origin", blueprint.get("origin"));
		o.add("size", blueprint.get("size"));
		o.addProperty("paletteSize", blueprint.getAsJsonObject("palette").size());
		return o;
	}
}
