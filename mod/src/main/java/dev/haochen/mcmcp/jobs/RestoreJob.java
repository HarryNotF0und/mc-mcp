package dev.haochen.mcmcp.jobs;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.history.ChangeSet;
import dev.haochen.mcmcp.history.History;
import dev.haochen.mcmcp.net.RpcException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Undo or redo of the most recent change set. The change set is picked when the job starts,
 * so an undo queued behind a build undoes that build.
 */
public final class RestoreJob extends Job {
	private final History history;
	private final boolean undo;
	private @Nullable ChangeSet changes;
	private @Nullable ServerLevel level;
	private int next;

	public RestoreJob(History history, boolean undo) {
		super(undo ? "undo" : "redo", undo ? "undo last change" : "redo last undone change", 0);
		this.history = history;
		this.undo = undo;
	}

	@Override
	protected void start(MinecraftServer server) {
		changes = undo ? history.popUndo() : history.popRedo();
		if (changes == null) {
			throw new RpcException(RpcException.INVALID_PARAMS, undo ? "nothing to undo" : "nothing to redo");
		}
		level = server.getLevel(changes.dimension());
		if (level == null) {
			returnToSource();
			throw new RpcException(RpcException.INTERNAL_ERROR, "dimension no longer exists: " + changes.dimension().location());
		}
		total = changes.size();
	}

	@Override
	protected int step(MinecraftServer server, int budget) {
		int n = 0;
		while (n < budget && next < total) {
			// Undo walks backwards so a block changed twice ends at its oldest state.
			int i = undo ? total - 1 - next : next;
			(undo ? changes.before(i) : changes.after(i)).restore(level, changes.pos(i));
			next++;
			n++;
		}
		done += n;
		return n;
	}

	@Override
	protected boolean isComplete() {
		return next >= total;
	}

	@Override
	protected void onDone(MinecraftServer server) {
		if (undo) {
			history.pushRedo(changes);
		} else {
			history.pushUndo(changes);
		}
	}

	@Override
	protected void onAborted(MinecraftServer server) {
		// Snapshots are absolute, so running the same undo/redo again later completes it.
		returnToSource();
	}

	private void returnToSource() {
		if (changes == null) {
			return;
		}
		if (undo) {
			history.pushUndo(changes);
		} else {
			history.pushRedo(changes);
		}
	}

	@Override
	protected void describe(JsonObject out) {
		if (changes != null) {
			out.addProperty("changeSetId", changes.id());
			out.addProperty("changeSetDescription", changes.description());
		}
	}
}
