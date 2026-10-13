package dev.haochen.mcmcp.history;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.world.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.BiConsumer;

/**
 * Everything one modifying operation changed, with the before and after state of each block.
 * Stored as parallel arrays (~16 bytes per block) because a single fill can touch 262144 blocks
 * and up to {@code historySize} change sets are kept.
 */
public final class ChangeSet {
	private final int id;
	private final String description;
	private final ResourceKey<Level> dimension;
	private final long createdAt = System.currentTimeMillis();

	private int size;
	private long[] positions = new long[64];
	private BlockState[] before = new BlockState[64];
	private BlockState[] after = new BlockState[64];
	/** Block entity data by entry index; most blocks have none. */
	private final Map<Integer, CompoundTag> beforeNbt = new HashMap<>();
	private final Map<Integer, CompoundTag> afterNbt = new HashMap<>();
	private @Nullable Region bounds;
	private IntConsumer restoreStarted = id -> {};
	private BiConsumer<Boolean, Boolean> restoreFinished = (undo, complete) -> {};

	/** Metadata follows the same queued undo/redo as the world snapshots. */
	public void onRestore(IntConsumer started, BiConsumer<Boolean, Boolean> finished) {
		restoreStarted = started;
		restoreFinished = finished;
	}
	public void restoreStarted(int jobId) { restoreStarted.accept(jobId); }
	public void restoreFinished(boolean undo, boolean complete) { restoreFinished.accept(undo, complete); }

	public ChangeSet(int id, String description, ResourceKey<Level> dimension) {
		this.id = id;
		this.description = description;
		this.dimension = dimension;
	}

	public void record(BlockPos pos, BlockSnapshot from, BlockSnapshot to) {
		if (size == positions.length) {
			int n = size * 2;
			positions = Arrays.copyOf(positions, n);
			before = Arrays.copyOf(before, n);
			after = Arrays.copyOf(after, n);
		}
		positions[size] = pos.asLong();
		before[size] = from.state();
		after[size] = to.state();
		if (from.nbt() != null) {
			beforeNbt.put(size, from.nbt());
		}
		if (to.nbt() != null) {
			afterNbt.put(size, to.nbt());
		}
		size++;
		bounds = bounds == null ? new Region(pos.immutable(), pos.immutable()) : bounds.include(pos);
	}

	public int size() {
		return size;
	}

	public BlockPos pos(int i) {
		return BlockPos.of(positions[i]);
	}

	public BlockSnapshot before(int i) {
		return new BlockSnapshot(before[i], beforeNbt.get(i));
	}

	public BlockSnapshot after(int i) {
		return new BlockSnapshot(after[i], afterNbt.get(i));
	}

	public int id() {
		return id;
	}

	public String description() {
		return description;
	}

	public ResourceKey<Level> dimension() {
		return dimension;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("description", description);
		o.addProperty("dimension", dimension.location().toString());
		o.addProperty("blocks", size);
		if (bounds != null) {
			o.add("bounds", bounds.toJson());
		}
		o.addProperty("createdAt", createdAt);
		return o;
	}
}
