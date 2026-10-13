package dev.haochen.mcmcp.jobs;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.history.BlockSnapshot;
import dev.haochen.mcmcp.history.ChangeSet;
import dev.haochen.mcmcp.history.History;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;

/**
 * Places blocks from a (possibly lazy) source and records the result as one {@link ChangeSet}.
 *
 * <p>Shapes (fence/wall/pane connections, stair corners) are computed against the world, but only
 * blocks placed by this job are re-shaped when a later neighbour arrives; blocks outside the job
 * are never modified, which keeps undo exact.
 */
public final class PlaceJob extends Job {
	public record Placement(BlockPos pos, BlockState state) {
	}

	private final ServerLevel level;
	private final Iterator<Placement> source;
	private final History history;
	private final ChangeSet changes;
	private final LongOpenHashSet placed = new LongOpenHashSet();

	public PlaceJob(String kind, String description, ServerLevel level, int total, Iterator<Placement> source, History history) {
		super(kind, description, total);
		this.level = level;
		this.source = source;
		this.history = history;
		this.changes = history.create(description, level.dimension());
	}

	@Override
	protected int step(MinecraftServer server, int budget) {
		int n = 0;
		while (n < budget && source.hasNext()) {
			Placement p = source.next();
			place(p.pos(), p.state());
			n++;
		}
		done += n;
		return n;
	}

	private void place(BlockPos pos, BlockState requested) {
		BlockState state = Block.updateFromNeighbourShapes(requested, level, pos);
		if (state.isAir() && !requested.isAir()) {
			state = requested;
		}
		set(pos, state);
		placed.add(pos.asLong());
		for (Direction dir : Direction.values()) {
			BlockPos np = pos.relative(dir);
			if (placed.contains(np.asLong())) {
				BlockState ns = level.getBlockState(np);
				set(np, ns.updateShape(dir.getOpposite(), state, level, np, pos));
			}
		}
	}

	private void set(BlockPos pos, BlockState state) {
		BlockSnapshot before = BlockSnapshot.capture(level, pos);
		if (before.state() == state && before.nbt() == null) {
			return;
		}
		Clearable.tryClear(level.getBlockEntity(pos));
		level.setBlock(pos, state, BlockSnapshot.SET_FLAGS);
		changes.record(pos, before, BlockSnapshot.capture(level, pos));
	}

	@Override
	protected boolean isComplete() {
		return !source.hasNext();
	}

	@Override
	protected void onDone(MinecraftServer server) {
		commit();
	}

	@Override
	protected void onAborted(MinecraftServer server) {
		// Keep partial work undoable.
		commit();
	}

	private void commit() {
		if (!changes.isEmpty()) {
			history.push(changes);
		}
	}

	@Override
	protected void describe(JsonObject out) {
		out.addProperty("changed", changes.size());
		if (!changes.isEmpty()) {
			out.addProperty("changeSetId", changes.id());
		}
	}
}
