package dev.haochen.mcmcp;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.history.History;
import dev.haochen.mcmcp.jobs.JobManager;
import dev.haochen.mcmcp.world.Guard;

import java.util.function.BiConsumer;

/** Per-server state, created when a server starts and dropped when it stops. */
public record Services(McMcpConfig config, Guard guard, History history, JobManager jobs) {
	public static Services create(McMcpConfig config, BiConsumer<String, JsonObject> notifier) {
		return new Services(config, new Guard(config), new History(config.historySize),
				new JobManager(config.blocksPerTick, notifier));
	}
}
