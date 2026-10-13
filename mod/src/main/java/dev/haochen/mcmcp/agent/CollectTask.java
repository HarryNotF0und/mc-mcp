package dev.haochen.mcmcp.agent;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks to dropped items within a radius until none are left. Pickup itself is vanilla: items
 * are collected when the agent touches them. Items it cannot reach are skipped.
 */
public final class CollectTask extends AgentTask {
	private static final int TICKS_PER_ITEM = 200;

	private final double radius;
	private final Set<Integer> unreachable = new HashSet<>();
	private final Map<Item, Integer> before = new HashMap<>();
	private ItemEntity current;
	private GotoTask approach;
	private int ticksOnCurrent;

	public CollectTask(AgentPlayer agent, double radius) {
		super(agent);
		this.radius = radius;
		snapshot(before);
	}

	@Override
	public Status tick() {
		if (current != null && (current.isRemoved() || ticksOnCurrent > TICKS_PER_ITEM)) {
			if (!current.isRemoved()) {
				unreachable.add(current.getId());
			}
			current = null;
		}
		if (current == null) {
			current = nearest();
			if (current == null) {
				report();
				return Status.DONE;
			}
			ticksOnCurrent = 0;
			approach = new GotoTask(agent, Pathfinder.near(current.blockPosition(), 0.9), "item " + current.getItem().getItem(), false);
		}
		ticksOnCurrent++;
		try {
			if (approach.tick() == Status.DONE) {
				// Standing on it; vanilla pickup happens within a few ticks (pickup delay).
				agent.stop();
			}
		} catch (Failure f) {
			unreachable.add(current.getId());
			current = null;
		}
		return Status.RUNNING;
	}

	private ItemEntity nearest() {
		AABB box = agent.getBoundingBox().inflate(radius);
		List<ItemEntity> items = agent.serverLevel().getEntitiesOfClass(ItemEntity.class, box,
				e -> e.isAlive() && !unreachable.contains(e.getId()));
		BlockPos at = agent.blockPosition();
		return items.stream().min(Comparator.comparingDouble(e -> e.blockPosition().distSqr(at))).orElse(null);
	}

	private void snapshot(Map<Item, Integer> into) {
		var inv = agent.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			var s = inv.getItem(i);
			if (!s.isEmpty()) {
				into.merge(s.getItem(), s.getCount(), Integer::sum);
			}
		}
	}

	private void report() {
		Map<Item, Integer> after = new HashMap<>();
		snapshot(after);
		JsonObject gained = new JsonObject();
		after.forEach((item, n) -> {
			int d = n - before.getOrDefault(item, 0);
			if (d > 0) {
				gained.addProperty(Inventories.id(item), d);
			}
		});
		result.add("collected", gained);
		if (!unreachable.isEmpty()) {
			result.addProperty("unreachableItems", unreachable.size());
		}
	}
}
