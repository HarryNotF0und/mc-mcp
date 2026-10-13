package dev.haochen.mcmcp.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.jobs.PlaceJob.Placement;
import dev.haochen.mcmcp.world.BlockList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a blueprint by hand: bottom-up, one block at a time, walking within reach, mining
 * whatever is in the way and placing from the inventory. Blueprint air is skipped. Blocks that
 * have nothing to place against yet are retried once at the end.
 */
public final class BuildTask extends AgentTask {
	private static final int MAX_ERRORS = 20;

	private final Deque<Placement> todo = new ArrayDeque<>();
	private final List<Placement> deferred = new ArrayList<>();
	private final JsonArray errors = new JsonArray();
	private boolean retried;
	private AgentTask child;
	private Placement current;
	private int placed, skipped, errorCount;

	public BuildTask(AgentPlayer agent, BlockList blocks) {
		super(agent);
		List<Placement> list = new ArrayList<>();
		for (Iterator<Placement> it = blocks.placements(); it.hasNext(); ) {
			Placement p = it.next();
			if (!p.state().isAir()) {
				list.add(p);
			}
		}
		list.sort(Comparator.comparingInt((Placement p) -> p.pos().getY())
				.thenComparingInt(p -> p.pos().getZ()).thenComparingInt(p -> p.pos().getX()));
		todo.addAll(list);
	}

	/** Items needed for the non-air blocks that differ from the world, minus what the agent has. */
	public static Map<Item, Integer> missingMaterials(AgentPlayer agent, BlockList blocks) {
		Map<Item, Integer> need = new LinkedHashMap<>();
		var level = agent.serverLevel();
		for (Iterator<Placement> it = blocks.placements(); it.hasNext(); ) {
			Placement p = it.next();
			if (p.state().isAir() || level.getBlockState(p.pos()) == p.state()) {
				continue;
			}
			Item item = p.state().getBlock().asItem();
			need.merge(item, 1, Integer::sum);
		}
		Map<Item, Integer> missing = new LinkedHashMap<>();
		need.forEach((item, n) -> {
			int have = item == Items.AIR ? 0 : Inventories.count(agent.getInventory(), item);
			if (have < n) {
				missing.put(item, n - have);
			}
		});
		return missing;
	}

	@Override
	public Status tick() {
		if (child != null) {
			try {
				if (child.tick() == Status.RUNNING) {
					return Status.RUNNING;
				}
				if (child instanceof PlaceTask) {
					placed++;
				} else {
					todo.addFirst(current); // obstacle mined; now place
				}
			} catch (AgentTask.Failure f) {
				error(current, f.getMessage());
			}
			child = null;
		}
		if (todo.isEmpty()) {
			if (!deferred.isEmpty() && !retried) {
				retried = true;
				todo.addAll(deferred);
				deferred.clear();
			} else {
				for (Placement p : deferred) {
					error(p, "nothing to place against");
				}
				deferred.clear();
				result.addProperty("placed", placed);
				result.addProperty("alreadyThere", skipped);
				if (!errors.isEmpty()) {
					result.add("errors", errors);
				}
				return Status.DONE;
			}
		}
		current = todo.pollFirst();
		BlockPos pos = current.pos();
		BlockState state = current.state();
		BlockState existing = agent.serverLevel().getBlockState(pos);
		if (existing == state) {
			skipped++;
			return Status.RUNNING;
		}
		if (state.getBlock().asItem() == Items.AIR) {
			error(current, "no item places " + state.getBlock());
			return Status.RUNNING;
		}
		if (!existing.canBeReplaced()) {
			child = new MineTask(agent, pos, true);
			return Status.RUNNING;
		}
		String problem = PlaceTask.problem(agent, pos, state);
		if (problem != null && problem.startsWith("nothing to place against") && !retried) {
			deferred.add(current);
			return Status.RUNNING;
		}
		child = new PlaceTask(agent, pos, state);
		return Status.RUNNING;
	}

	private void error(Placement p, String message) {
		if (errors.size() < 10) {
			JsonObject e = new JsonObject();
			e.add("pos", dev.haochen.mcmcp.api.Json.blockPos(p.pos()));
			e.addProperty("error", message);
			errors.add(e);
		}
		if (++errorCount >= MAX_ERRORS) {
			throw new Failure("too many blocks could not be placed; first: " + errors.get(0));
		}
	}

	@Override
	public void abort() {
		if (child != null) {
			child.abort();
		}
		super.abort();
	}
}
