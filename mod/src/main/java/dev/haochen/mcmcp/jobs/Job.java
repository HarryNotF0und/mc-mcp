package dev.haochen.mcmcp.jobs;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.net.RpcException;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/**
 * A unit of world-modifying work that {@link JobManager} runs a slice of every tick.
 * All methods are called on the server thread.
 */
public abstract class Job {
	public enum State {
		QUEUED, RUNNING, DONE, FAILED, CANCELLED;

		public boolean finished() {
			return this == DONE || this == FAILED || this == CANCELLED;
		}

		public String id() {
			return name().toLowerCase();
		}
	}

	int id;
	State state = State.QUEUED;
	protected int done;
	protected int total;
	private @Nullable String error;
	private long startedAt;
	private long finishedAt;

	private final String kind;
	private final String description;

	protected Job(String kind, String description, int total) {
		this.kind = kind;
		this.description = description;
		this.total = total;
	}

	/** Called once when the job reaches the front of the queue. Throw {@link RpcException} to fail it. */
	protected void start(MinecraftServer server) {
	}

	/**
	 * Parallel jobs (agent actions) run every tick next to the build queue instead of waiting in
	 * it, and do not use its block budget; {@link #step} is then called once per tick.
	 */
	protected boolean parallel() {
		return false;
	}

	/** Processes up to {@code budget} blocks and returns how many were processed. */
	protected abstract int step(MinecraftServer server, int budget);

	/** True once {@link #step} has nothing left to do. */
	protected abstract boolean isComplete();

	/** Called once after the job finished successfully. */
	protected void onDone(MinecraftServer server) {
	}

	/** Called once if the job is cancelled or fails after it started. */
	protected void onAborted(MinecraftServer server) {
	}

	/** Extra fields for status and {@code job.finished}, e.g. the change set id. */
	protected void describe(JsonObject out) {
	}

	public int id() {
		return id;
	}

	public State state() {
		return state;
	}

	void markStarted() {
		state = State.RUNNING;
		startedAt = System.currentTimeMillis();
	}

	void markFinished(State state, @Nullable String error) {
		this.state = state;
		this.error = error;
		this.finishedAt = System.currentTimeMillis();
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("jobId", id);
		o.addProperty("kind", kind);
		o.addProperty("description", description);
		o.addProperty("state", state.id());
		o.addProperty("done", done);
		o.addProperty("total", total);
		if (startedAt != 0) {
			o.addProperty("elapsedMs", (finishedAt != 0 ? finishedAt : System.currentTimeMillis()) - startedAt);
		}
		if (error != null) {
			o.addProperty("error", error);
		}
		describe(o);
		return o;
	}
}
