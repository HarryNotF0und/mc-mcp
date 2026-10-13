package dev.haochen.mcmcp.agent;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.jobs.Job;
import dev.haochen.mcmcp.net.RpcException;
import net.minecraft.server.MinecraftServer;

/**
 * Runs an {@link AgentTask} as a parallel job, so it shows up in job.status, can be cancelled and
 * pushes job.finished. Fails with a clear reason if the agent dies, leaves, or runs out of time.
 */
public final class AgentJob extends Job {
	private final AgentManager manager;
	private final AgentPlayer agent;
	private final AgentTask task;
	private final int maxTicks;
	private int ticks;
	private boolean complete;

	public AgentJob(AgentManager manager, AgentPlayer agent, String kind, String description, AgentTask task, int maxTicks) {
		super(kind, agent.getGameProfile().getName() + ": " + description, 0);
		this.manager = manager;
		this.agent = agent;
		this.task = task;
		this.maxTicks = maxTicks;
	}

	@Override
	protected boolean parallel() {
		return true;
	}

	@Override
	protected int step(MinecraftServer server, int budget) {
		if (agent.isRemoved() || agent.hasDisconnected()) {
			throw new RpcException(RpcException.INTERNAL_ERROR, "agent left the game");
		}
		if (agent.isDeadOrDying()) {
			throw new RpcException(RpcException.INTERNAL_ERROR, "agent died at " + agent.blockPosition().toShortString()
					+ " (" + agent.getCombatTracker().getDeathMessage().getString() + "); agent_spawn brings it back");
		}
		if (++ticks > maxTicks) {
			throw new RpcException(RpcException.INTERNAL_ERROR, "timed out after " + maxTicks / 20 + " s");
		}
		try {
			complete = task.tick() == AgentTask.Status.DONE;
		} catch (AgentTask.Failure f) {
			throw new RpcException(RpcException.INTERNAL_ERROR, f.getMessage());
		}
		done = ticks;
		return 1;
	}

	@Override
	protected boolean isComplete() {
		return complete;
	}

	@Override
	protected void onDone(MinecraftServer server) {
		agent.stop();
		manager.finished(agent, this);
	}

	@Override
	protected void onAborted(MinecraftServer server) {
		task.abort();
		manager.finished(agent, this);
	}

	@Override
	protected void describe(JsonObject out) {
		out.addProperty("agent", agent.getGameProfile().getName());
		out.add("result", task.result());
		out.add("agentPos", dev.haochen.mcmcp.api.Json.blockPos(agent.blockPosition()));
	}
}
