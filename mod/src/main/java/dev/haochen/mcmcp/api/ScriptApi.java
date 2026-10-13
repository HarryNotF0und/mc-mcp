package dev.haochen.mcmcp.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.Services;
import dev.haochen.mcmcp.net.RpcDispatcher;
import dev.haochen.mcmcp.script.ScriptSandbox;
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
		String source = Params.string(params, "source");
		BlockPos origin = Params.pos(params, "origin");
		boolean dryRun = Params.bool(params, "dryRun", false);
		boolean loadChunks = Params.bool(params, "loadChunks", false);
		// Level lookup reads a map that is fixed after startup, so it is safe off the server thread.
		ServerLevel level = Guard.level(server, Params.string(params, "dimension", WorldApi.DEFAULT_DIMENSION));

		return s.scripts()
				.run(server, level, source, origin, s.guard(), s.config().scriptTimeoutMs, s.config().maxVolumePerOp, loadChunks)
				.thenCompose(result -> server.submit(() -> finish(s, level, origin, result, dryRun, loadChunks)));
	}

	private static JsonElement finish(Services s, ServerLevel level, BlockPos origin, ScriptSandbox.Result result,
									  boolean dryRun, boolean loadChunks) {
		JsonObject script = result.blocks().stats(20);
		JsonArray logs = new JsonArray();
		result.logs().forEach(logs::add);
		script.add("logs", logs);
		script.addProperty("scriptMs", result.elapsedMs());
		if (dryRun) {
			script.addProperty("dryRun", true);
			return script;
		}
		JsonObject job = BuildApi.submitList(s, level, result.blocks(), "runScript",
				"script at " + origin.toShortString() + " (" + result.blocks().size() + " blocks)", loadChunks);
		job.add("script", script);
		return job;
	}
}
