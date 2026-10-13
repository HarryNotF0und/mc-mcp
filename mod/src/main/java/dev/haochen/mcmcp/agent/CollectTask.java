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
	private final Set<Integer> unreachable;
	private final Map<Item, Integer> before = new HashMap<>();
	private final Map<Integer, Integer> attempts = new HashMap<>();
	private ItemEntity current;
	private GotoTask approach;
	private int ticksOnCurrent;
	private int waitedForFalling;

	public CollectTask(AgentPlayer agent, double radius) {
		this(agent, radius, new HashSet<>());
	}

	/** {@code unreachable} (entity ids) is shared so repeated collects don't retry the same items. */
	public CollectTask(AgentPlayer agent, double radius, Set<Integer> unreachable) {
		super(agent);
		this.radius = radius;
		this.unreachable = unreachable;
		snapshot(before);
	}

	@Override
	public Status tick() {
		// Vanilla picks items up on bounding-box contact; items in a hole next to the agent may
		// never touch it, so pick up anything within arm's length (pickup delay still applies).
		for (ItemEntity e : agent.serverLevel().getEntitiesOfClass(ItemEntity.class, agent.getBoundingBox().inflate(1.5, 1.0, 1.5))) {
			e.playerTouch(agent);
		}
		if (current != null && (current.isRemoved() || ticksOnCurrent > TICKS_PER_ITEM)) {
			if (!current.isRemoved()) {
				unreachable.add(current.getId());
			}
			current = null;
		}
		if (current == null) {
			current = nearest();
			if (current == null) {
				// Drops that are still falling (e.g. from a tree trunk) are targeted once they land.
				if (anyFalling() && ++waitedForFalling < 60) {
					return Status.RUNNING;
				}
				report();
				return Status.DONE;
			}
			ticksOnCurrent = 0;
			approach = new GotoTask(agent, Pathfinder.near(current.blockPosition(), 1.5), "item " + current.getItem().getItem(), false);
		}
		ticksOnCurrent++;
		try {
			if (approach.tick() == Status.DONE) {
				// Standing on it; vanilla pickup happens within a few ticks (pickup delay).
				agent.stop();
			}
		} catch (Failure f) {
			// Give an item a few tries (the agent may be better placed later) before giving up on it.
			if (attempts.merge(current.getId(), 1, Integer::sum) >= 3) {
				unreachable.add(current.getId());
			}
			current = null;
		}
		return Status.RUNNING;
	}

	private boolean anyFalling() {
		return !agent.serverLevel().getEntitiesOfClass(ItemEntity.class, agent.getBoundingBox().inflate(radius),
				e -> e.isAlive() && !e.onGround() && !e.isInWater() && !unreachable.contains(e.getId())).isEmpty();
	}

	private ItemEntity nearest() {
		AABB box = agent.getBoundingBox().inflate(radius);
		List<ItemEntity> items = agent.serverLevel().getEntitiesOfClass(ItemEntity.class, box,
				e -> e.isAlive() && (e.onGround() || e.isInWater()) && !unreachable.contains(e.getId()));
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
