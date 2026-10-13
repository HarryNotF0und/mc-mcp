package dev.haochen.mcmcp.api;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.jobs.RestoreJob;
import dev.haochen.mcmcp.net.RpcDispatcher;

/** Job queue and undo history methods. */
public final class JobApi {
	private JobApi() {
	}

	public static void register(RpcDispatcher dispatcher) {
		dispatcher.onMainThread("job.status", (server, params) -> {
			if (!Params.has(params, "jobId")) {
				JsonObject o = new JsonObject();
				o.add("jobs", McMcpMod.services().jobs().toJson());
				return o;
			}
			return McMcpMod.services().jobs().get(Params.integer(params, "jobId", 0)).toJson();
		});
		dispatcher.onMainThread("job.cancel", (server, params) -> {
			Params.require(params, "jobId");
			return McMcpMod.services().jobs().cancel(server, Params.integer(params, "jobId", 0)).toJson();
		});
		dispatcher.onMainThread("history.list", (server, params) -> McMcpMod.services().history().toJson());
		dispatcher.onMainThread("history.undo", (server, params) ->
				BuildApi.submit(McMcpMod.services(), new RestoreJob(McMcpMod.services().history(), true)));
		dispatcher.onMainThread("history.redo", (server, params) ->
				BuildApi.submit(McMcpMod.services(), new RestoreJob(McMcpMod.services().history(), false)));
	}
}
