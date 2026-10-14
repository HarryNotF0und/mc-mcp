package dev.haochen.mcmcp.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.Services;
import dev.haochen.mcmcp.script.ScriptSandbox;
import dev.haochen.mcmcp.net.RpcDispatcher;
import dev.haochen.mcmcp.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.CompletableFuture;

/** {@code build.runScript}: the script runs on the sandbox worker, the result is placed from the server thread. */
public final class ScriptApi {
	private ScriptApi() {
	}

	public static void register(RpcDispatcher dispatcher) {
		dispatcher.async("build.runScript", ScriptApi::runScript);
	}

	public static CompletableFuture<JsonElement> runScript(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		boolean dryRun = Params.bool(params, "dryRun", false);
		return plan(server, params).thenCompose(plan -> server.submit(() -> {
			if (dryRun) {
				JsonObject stats = plan.stats();
				stats.addProperty("dryRun", true);
				return (JsonElement) stats;
			}
			JsonObject job = plan.submit(s);
			job.add("script", plan.stats());
			return job;
		}));
	}

	/** Runs the script described by {@code params} ({@code source, origin, dimension?, loadChunks?}). */
	public static CompletableFuture<Plan> plan(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		String source = Params.string(params, "source");
		BlockPos origin = Params.pos(params, "origin");
		boolean loadChunks = Params.loadChunks(params);
		// Level lookup reads a map that is fixed after startup, so it is safe off the server thread.
		ServerLevel level = Guard.level(server, Params.string(params, "dimension", WorldApi.DEFAULT_DIMENSION));

		java.util.List<ScriptSandbox.Library> libraries = new java.util.ArrayList<>();
		if (Params.has(params, "libraries")) {
			for (var e : Params.array(params, "libraries")) {
				if (!e.isJsonObject()) {
					throw Params.invalid("libraries must be [{\"name\", \"source\"}]");
				}
				libraries.add(new ScriptSandbox.Library(Params.string(e.getAsJsonObject(), "name"), Params.string(e.getAsJsonObject(), "source")));
			}
		}
		ScriptSandbox.Options options = new ScriptSandbox.Options(libraries, Params.bool(params, "keepExisting", false));

		return s.scripts()
				.run(server, level, source, origin, s.guard(), s.config().scriptTimeoutMs, s.config().maxVolumePerOp, loadChunks, options)
				.thenApply(result -> {
					JsonObject stats = result.blocks().stats(20);
					if (result.skippedExisting() > 0) {
						stats.addProperty("skippedExisting", result.skippedExisting());
					}
					JsonArray logs = new JsonArray();
					result.logs().forEach(logs::add);
					stats.add("logs", logs);
					stats.addProperty("scriptMs", result.elapsedMs());
					return new Plan(level, result.blocks(), "runScript",
							"script at " + origin.toShortString() + " (" + result.blocks().size() + " blocks)", loadChunks, stats);
				});
	}
}
