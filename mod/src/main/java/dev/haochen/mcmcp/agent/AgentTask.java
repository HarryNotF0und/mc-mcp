package dev.haochen.mcmcp.agent;

import com.google.gson.JsonObject;

/** One step-by-step agent activity, ticked once per server tick. Tasks can run sub-tasks. */
public abstract class AgentTask {
	public enum Status {
		RUNNING, DONE
	}

	/** Thrown to end the task with a reason the model can act on. */
	public static final class Failure extends RuntimeException {
		public Failure(String message) {
			super(message, null, false, false);
		}
	}

	protected final AgentPlayer agent;
	protected final JsonObject result = new JsonObject();

	protected AgentTask(AgentPlayer agent) {
		this.agent = agent;
	}

	/** Advances one tick. Throw {@link Failure} to fail. */
	public abstract Status tick();

	/** Called when the task ends early (cancel, failure, death). */
	public void abort() {
		agent.stop();
	}

	/** Extra fields reported with the job (what was collected, crafted, ...). */
	public JsonObject result() {
		return result;
	}
}
