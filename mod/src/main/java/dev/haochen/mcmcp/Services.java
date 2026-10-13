package dev.haochen.mcmcp;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.agent.AgentManager;
import dev.haochen.mcmcp.blueprint.BlueprintStore;
import dev.haochen.mcmcp.history.History;
import dev.haochen.mcmcp.jobs.JobManager;
import dev.haochen.mcmcp.preview.PreviewManager;
import dev.haochen.mcmcp.script.ScriptSandbox;
import dev.haochen.mcmcp.world.Guard;

import java.nio.file.Path;
import java.util.function.BiConsumer;

/** Per-server state, created when a server starts and dropped when it stops. */
public record Services(McMcpConfig config, Guard guard, History history, JobManager jobs,
					   ScriptSandbox scripts, BlueprintStore blueprints, PreviewManager previews,
					   AgentManager agents) {
	public static Services create(McMcpConfig config, Path gameDir, BiConsumer<String, JsonObject> notifier) {
		return new Services(config, new Guard(config), new History(config.historySize),
				new JobManager(config.blocksPerTick, notifier), new ScriptSandbox(),
				new BlueprintStore(gameDir.resolve("mcmcp").resolve("blueprints")), new PreviewManager(), new AgentManager());
	}

	public void shutdown() {
		scripts.shutdown();
	}
}
