package dev.haochen.mcmcp.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.Services;
import dev.haochen.mcmcp.arch.BuildingRegistry;
import dev.haochen.mcmcp.net.RpcDispatcher;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;

/** {@code build.preview}, {@code build.confirmPreview}, {@code build.discardPreview}. */
public final class PreviewApi {
	private PreviewApi() {
	}

	public static void register(RpcDispatcher dispatcher) {
		dispatcher.async("build.preview", PreviewApi::preview);
		dispatcher.onMainThread("build.confirmPreview", (server, params) -> {
			Params.require(params, "previewId");
			Services s = McMcpMod.services();
			int id = Params.integer(params, "previewId", 0);
			BuildingRegistry.Entry building = s.buildings().byPreview(id);
			if (building != null) {
				// Remember what the building replaces, so later revisions can restore removed parts.
				BuildingRegistry.captureOriginals(building, building.level, building.compiled.keySet());
			}
			JsonObject job = s.previews().confirm(s, id);
			if (building != null) {
				building.previewId = -1;
				building.built = true;
				job.addProperty("building", building.name);
			}
			return job;
		});
		dispatcher.onMainThread("build.discardPreview", (server, params) -> {
			Params.require(params, "previewId");
			Services s = McMcpMod.services();
			int id = Params.integer(params, "previewId", 0);
			s.previews().discard(id);
			BuildingRegistry.Entry building = s.buildings().byPreview(id);
			if (building != null) {
				building.previewId = -1;
			}
			JsonObject o = new JsonObject();
			o.addProperty("discarded", true);
			return o;
		});
	}

	/** Same input as {@code build.runScript} (with {@code source}) or {@code build.placeBlueprint} (otherwise). */
	private static CompletableFuture<JsonElement> preview(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		if (Params.has(params, "building")) {
			return server.submit(() -> (JsonElement) ArchApi.preview(server, params));
		}
		CompletableFuture<Plan> plan = Params.has(params, "source")
				? ScriptApi.plan(server, params)
				: server.submit(() -> BlueprintApi.plan(server, params));
		return plan.thenCompose(p -> server.submit(() -> (JsonElement) s.previews().create(s, p)));
	}
}
