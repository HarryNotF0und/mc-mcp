package dev.haochen.mcmcp.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.Services;
import dev.haochen.mcmcp.blueprint.Blueprint;
import dev.haochen.mcmcp.net.RpcDispatcher;
import dev.haochen.mcmcp.world.BlockList;
import dev.haochen.mcmcp.worldedit.WorldEditBridge;
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
		dispatcher.onMainThread("build.listBlueprints", (server, params) -> {
			JsonObject o = new JsonObject();
			JsonArray names = new JsonArray();
			McMcpMod.services().blueprints().list().forEach(names::add);
			o.add("blueprints", names);
			WorldEditBridge we = WorldEditBridge.get();
			if (we != null) {
				JsonArray schematics = new JsonArray();
				we.listSchematics().forEach(schematics::add);
				o.add("schematics", schematics);
			}
			o.addProperty("worldEdit", we != null);
			return o;
		});
	}

	private static WorldEditBridge worldEdit(String feature) {
		WorldEditBridge we = WorldEditBridge.get();
		if (we == null) {
			throw Params.invalid(feature + " needs WorldEdit installed on the server");
		}
		return we;
	}

	public static JsonObject placeBlueprint(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		Plan plan = Params.has(params, "building") && !Params.bool(params, "dryRun", false)
				? ArchApi.plan(server, params, true) : plan(server, params);
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
		if (Params.has(params, "building")) {
			return ArchApi.plan(server, params, false);
		}
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
		} else if (Params.has(params, "schematic")) {
			label = Params.string(params, "schematic");
			json = null;
		} else {
			throw Params.invalid("pass blueprint (the JSON object), name (a saved blueprint) or schematic (a WorldEdit schematic file)");
		}
		Blueprint bp;
		if (json != null) {
			bp = Blueprint.parse(json, s.guard());
		} else {
			bp = worldEdit("schematic").loadSchematic(label);
			bp.states().forEach(s.guard()::checkAllowed);
		}
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
		String format = Params.string(params, "format", "json");
		if (format.equals("schem")) {
			if (!Params.has(params, "name")) {
				throw Params.invalid("format schem needs a name");
			}
			Path file = worldEdit("format schem").saveSchematic(Params.string(params, "name"), level, region);
			JsonObject o = new JsonObject();
			o.addProperty("schematic", file.getFileName().toString());
			o.addProperty("path", file.toAbsolutePath().toString());
			o.add("size", Json.blockPos(new BlockPos(region.sizeX(), region.sizeY(), region.sizeZ())));
			return o;
		} else if (!format.equals("json")) {
			throw Params.invalid("format must be json or schem");
		}
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
