package dev.haochen.mcmcp.jobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.net.RpcException;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Runs jobs one at a time, in submission order, spending at most {@code blocksPerTick} blocks
 * per server tick. Pushes {@code job.progress} roughly once a second and {@code job.finished}
 * when a job ends. Server thread only.
 */
public final class JobManager {
	private static final int KEEP_FINISHED = 100;
	private static final int PROGRESS_EVERY_TICKS = 20;

	private final int blocksPerTick;
	private final BiConsumer<String, JsonObject> notifier;
	private final Deque<Job> queue = new ArrayDeque<>();
	private final java.util.List<Job> parallel = new java.util.ArrayList<>();
	private final Map<Integer, Job> jobs = new LinkedHashMap<>();
	private @Nullable Job current;
	private int nextId = 1;
	private int ticksSinceProgress;

	public JobManager(int blocksPerTick, BiConsumer<String, JsonObject> notifier) {
		this.blocksPerTick = Math.max(1, blocksPerTick);
		this.notifier = notifier;
	}

	public Job submit(Job job) {
		job.id = nextId++;
		jobs.put(job.id, job);
		pruneFinished();
		if (job.parallel()) {
			parallel.add(job);
		} else {
			queue.addLast(job);
		}
		return job;
	}

	public Job get(int id) {
		Job job = jobs.get(id);
		if (job == null) {
			throw new RpcException(RpcException.JOB_NOT_FOUND, "no job with id " + id);
		}
		return job;
	}

	public Job cancel(MinecraftServer server, int id) {
		Job job = get(id);
		if (job.state() == Job.State.QUEUED) {
			queue.remove(job);
			parallel.remove(job);
			finish(job, Job.State.CANCELLED, null);
		} else if (job == current) {
			current = null;
			job.onAborted(server);
			finish(job, Job.State.CANCELLED, null);
		} else if (parallel.remove(job)) {
			if (job.state() == Job.State.RUNNING) {
				job.onAborted(server);
			}
			finish(job, Job.State.CANCELLED, null);
		}
		return job;
	}

	public JsonArray toJson() {
		JsonArray a = new JsonArray();
		jobs.values().forEach(j -> a.add(j.toJson()));
		return a;
	}

	public void tick(MinecraftServer server) {
		tickParallel(server);
		int budget = blocksPerTick;
		while (budget > 0) {
			if (current == null && !startNext(server)) {
				break;
			}
			Job job = current;
			try {
				// Charge at least 1 so a job that makes no progress cannot spin forever.
				budget -= Math.max(1, job.step(server, budget));
				if (job.isComplete()) {
					current = null;
					job.onDone(server);
					finish(job, Job.State.DONE, null);
				}
			} catch (RuntimeException e) {
				McMcpMod.LOGGER.error("Job {} failed", job.id(), e);
				current = null;
				job.onAborted(server);
				finish(job, Job.State.FAILED, e.getMessage());
			}
		}
		if (current != null && ++ticksSinceProgress >= PROGRESS_EVERY_TICKS) {
			ticksSinceProgress = 0;
			notifier.accept("job.progress", current.toJson());
		}
	}

	private void tickParallel(MinecraftServer server) {
		for (Job job : java.util.List.copyOf(parallel)) {
			if (job.state().finished()) { parallel.remove(job); continue; }
			try {
				if (job.state() == Job.State.QUEUED) {
					job.markStarted();
					job.start(server);
				}
				job.step(server, 1);
				if (job.isComplete()) {
					parallel.remove(job);
					job.onDone(server);
					finish(job, Job.State.DONE, null);
				} else if (server.getTickCount() % PROGRESS_EVERY_TICKS == 0) {
					notifier.accept("job.progress", job.toJson());
				}
			} catch (RuntimeException e) {
				parallel.remove(job);
				if (!(e instanceof RpcException)) {
					McMcpMod.LOGGER.error("Job {} failed", job.id(), e);
				}
				job.onAborted(server);
				finish(job, Job.State.FAILED, e.getMessage());
			}
		}
	}

	private boolean startNext(MinecraftServer server) {
		while (!queue.isEmpty()) {
			Job job = queue.pollFirst();
			job.markStarted();
			try {
				job.start(server);
			} catch (RuntimeException e) {
				finish(job, Job.State.FAILED, e.getMessage());
				continue;
			}
			current = job;
			ticksSinceProgress = 0;
			return true;
		}
		return false;
	}

	private void finish(Job job, Job.State state, @Nullable String error) {
		job.markFinished(state, error);
		job.onFinished();
		notifier.accept("job.finished", job.toJson());
	}

	private void pruneFinished() {
		var it = jobs.values().iterator();
		int excess = jobs.size() - KEEP_FINISHED;
		while (excess > 0 && it.hasNext()) {
			if (it.next().state().finished()) {
				it.remove();
				excess--;
			}
		}
	}
}
