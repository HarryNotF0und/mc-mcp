package dev.haochen.mcmcp.api;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.Services;
import dev.haochen.mcmcp.arch.BuildingPlan;
import dev.haochen.mcmcp.arch.PlanCompiler;
import dev.haochen.mcmcp.arch.PlanValidator;
import dev.haochen.mcmcp.arch.SiteAnalyzer;
import dev.haochen.mcmcp.net.RpcDispatcher;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.BlockList;
import dev.haochen.mcmcp.world.Guard;
import dev.haochen.mcmcp.world.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Architectural planning: {@code site.analyze}, {@code arch.validate}, and compiling building plans. */
public final class ArchApi {
	private ArchApi() {
	}

	public static void register(RpcDispatcher d) {
		d.onMainThread("site.analyze", ArchApi::analyze);
		d.onMainThread("arch.validate", (server, params) -> {
			Compiled c = compile(server, params);
			JsonObject o = c.stats.deepCopy();
			o.add("components", c.compiler.components());
			return o;
		});
	}

	private static JsonObject analyze(MinecraftServer server, JsonObject p) {
		Services s = McMcpMod.services();
		ServerLevel level = WorldApi.level(server, p);
		BlockPos center;
		if (Params.has(p, "center")) {
			center = Params.pos(p, "center");
		} else {
			ServerPlayer player = server.getPlayerList().getPlayers().stream()
					.filter(pl -> !(pl instanceof dev.haochen.mcmcp.agent.AgentPlayer)).findFirst().orElse(null);
			if (player == null) {
				throw Params.invalid("center is required when no player is online");
			}
			center = player.blockPosition();
		}
		int radius = Params.integer(p, "radius", 24);
		int width = 9, depth = 7;
		if (Params.has(p, "footprint")) {
			var fp = Params.array(p, "footprint");
			width = fp.get(0).getAsInt();
			depth = fp.get(1).getAsInt();
		}
		if (width < 3 || depth < 3 || width > 48 || depth > 48 || width > 2 * radius || depth > 2 * radius) {
			throw Params.invalid("footprint must be 3-48 per side and fit inside the search area (2 * radius)");
		}
		Direction prefer = Params.has(p, "facing") ? Direction.byName(Params.string(p, "facing")) : null;
		Guard.checkChunks(level, Region.of(center.offset(-radius, 0, -radius), center.offset(radius, 0, radius)),
				Params.bool(p, "loadChunks", false));
		return SiteAnalyzer.analyze(level, s.guard(), new SiteAnalyzer.Request(center, radius, width, depth,
				Params.integer(p, "margin", 2), prefer, Math.max(1, Math.min(10, Params.integer(p, "top", 3)))));
	}

	public record Compiled(BuildingPlan plan, PlanCompiler compiler, JsonObject validation, BlockList blocks, JsonObject stats,
						   ServerLevel level) {
	}

	/** Parses, compiles and validates {@code params.building}. Server thread. */
	public static Compiled compile(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		ServerLevel level = WorldApi.level(server, params);
		BuildingPlan plan = BuildingPlan.parse(Params.object(params, "building"), s.guard());
		int reach = Math.max(plan.width, plan.depth) + plan.terrainMargin + 8;
		Guard.checkChunks(level, Region.of(plan.origin.offset(-reach, 0, -reach), plan.origin.offset(reach, 0, reach)),
				Params.bool(params, "loadChunks", false));
		PlanCompiler compiler = PlanCompiler.compile(plan, level);
		JsonObject validation = PlanValidator.validate(compiler, s.guard());
		BlockList blocks = compiler.toBlockList();
		JsonObject stats = blocks.stats(12);
		stats.addProperty("building", plan.name);
		stats.addProperty("facing", plan.facing.getName());
		stats.add("origin", Json.blockPos(plan.origin));
		stats.addProperty("floors", plan.floors.size());
		JsonObject terrain = new JsonObject();
		terrain.addProperty("foundationMaxDepth", compiler.foundationDepth());
		stats.add("terrain", terrain);
		stats.add("validation", validation);
		return new Compiled(plan, compiler, validation, blocks, stats, level);
	}

	/** A {@link Plan} for preview / placement; placement refuses invalid designs unless forced. */
	public static Plan plan(MinecraftServer server, JsonObject params, boolean forPlacement) {
		Compiled c = compile(server, params);
		if (forPlacement && !c.validation.get("ok").getAsBoolean() && !Params.bool(params, "ignoreValidation", false)) {
			throw new RpcException(RpcException.BUILDING_INVALID, "the building plan has validation errors; fix them (or preview it, "
					+ "or pass ignoreValidation: true)", c.validation);
		}
		return new Plan(c.level, c.blocks, "building", "build " + c.plan.name, Params.bool(params, "loadChunks", false), c.stats);
	}
}
