package dev.haochen.mcmcp.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/** Undo and redo stacks of {@link ChangeSet}s. Accessed only from the server thread. */
public final class History {
	private final int capacity;
	private final Deque<ChangeSet> undo = new ArrayDeque<>();
	private final Deque<ChangeSet> redo = new ArrayDeque<>();
	private int nextId = 1;

	public History(int capacity) {
		this.capacity = Math.max(1, capacity);
	}

	public ChangeSet create(String description, ResourceKey<Level> dimension) {
		return new ChangeSet(nextId++, description, dimension);
	}

	/** Records a new change; this invalidates everything that could be redone. */
	public void push(ChangeSet changes) {
		redo.clear();
		pushUndo(changes);
	}

	public @Nullable ChangeSet popUndo() {
		return undo.pollFirst();
	}

	public @Nullable ChangeSet popRedo() {
		return redo.pollFirst();
	}

	public void pushUndo(ChangeSet changes) {
		undo.addFirst(changes);
		while (undo.size() > capacity) {
			undo.removeLast();
		}
	}

	public void pushRedo(ChangeSet changes) {
		redo.addFirst(changes);
	}

	/** Most recent first. */
	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		JsonArray u = new JsonArray();
		undo.forEach(c -> u.add(c.toJson()));
		JsonArray r = new JsonArray();
		redo.forEach(c -> r.add(c.toJson()));
		o.add("undo", u);
		o.add("redo", r);
		return o;
	}
}
