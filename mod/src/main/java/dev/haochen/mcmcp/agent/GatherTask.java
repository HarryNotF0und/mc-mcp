package dev.haochen.mcmcp.agent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.HashMap;
import java.util.Map;

/**
 * "Get N of an item": repeatedly finds the nearest exposed block whose drops (computed from the
 * loot table with the agent's best tool) include the item, mines it and collects what dropped.
 */
public final class GatherTask extends AgentTask {
	private static final int MAX_FAILURES = 30;
	private static final int VERTICAL = 12;

	private final Item item;
	private final int count;
	private final int radius;
	private final int startCount;
	private final LongOpenHashSet skipped = new LongOpenHashSet();
	private final java.util.Set<Integer> unreachableItems = new java.util.HashSet<>();
	private final Map<String, Boolean> dropsCache = new HashMap<>();
	private AgentTask child;
	private BlockPos target;
	private int mined;
	private int failures;
	private int ticks;
	private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();

	public GatherTask(AgentPlayer agent, Item item, int count, int radius) {
		super(agent);
		this.item = item;
		this.count = count;
		this.radius = radius;
		this.startCount = Inventories.count(agent.getInventory(), item);
	}

	private void event(String e) {
		if (recent.size() == 8) {
			recent.removeFirst();
		}
		recent.addLast("t" + ticks + " " + e);
	}

	@Override
	public Status tick() {
		ticks++;
		if (child != null) {
			try {
				if (child.tick() == Status.RUNNING) {
					return Status.RUNNING;
				}
				if (child instanceof MineTask) {
					mined++;
					event("mined " + target.toShortString() + " " + child.result());
					child = new CollectTask(agent, 5, unreachableItems);
					return Status.RUNNING;
				}
				event("collected " + child.result());
				child = null;
			} catch (Failure f) {
				if (target != null) {
					skipped.add(target.asLong());
				}
				event((child instanceof MineTask ? "mine failed: " : "collect failed: ") + f.getMessage());
				child = null;
				result.addProperty("lastError", f.getMessage());
				if (++failures > MAX_FAILURES) {
					throw new Failure("gave up after " + failures + " failed attempts; last: " + f.getMessage());
				}
			}
		}
		int have = Inventories.count(agent.getInventory(), item);
		report(have); // keep job_status current
		if (have >= count) {
			return Status.DONE;
		}
		target = findSource();
		if (target == null) {
			report(have);
			throw new Failure("no reachable block that drops " + Inventories.id(item) + " within " + radius
					+ " blocks of " + agent.blockPosition().toShortString() + " (have " + have + "/" + count
					+ "); move the agent elsewhere or get a better tool");
		}
		child = new MineTask(agent, target, true);
		return Status.RUNNING;
	}

	private void report(int have) {
		result.addProperty("step", child == null ? "searching" : child instanceof MineTask ? "mining " + target.toShortString() : "collecting drops");
		result.addProperty("failedAttempts", failures);
		result.addProperty("item", Inventories.id(item));
		result.addProperty("have", have);
		result.addProperty("gained", have - startCount);
		result.addProperty("blocksMined", mined);
		com.google.gson.JsonArray r = new com.google.gson.JsonArray();
		recent.forEach(r::add);
		result.add("recent", r);
	}

	private BlockPos findSource() {
		ServerLevel level = agent.serverLevel();
		BlockPos center = agent.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dy = -VERTICAL; dy <= VERTICAL; dy++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					double d = dx * dx + dz * dz + 2.0 * dy * dy; // prefer the same height
					if (d >= bestDist) {
						continue;
					}
					p.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (!level.hasChunkAt(p) || skipped.contains(p.asLong())) {
						continue;
					}
					BlockState state = level.getBlockState(p);
					if (state.isAir() || state.getDestroySpeed(level, p) < 0 || !exposed(level, p) || !drops(level, p, state)) {
						continue;
					}
					best = p.immutable();
					bestDist = d;
				}
			}
		}
		return best;
	}

	private static boolean exposed(ServerLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockState n = level.getBlockState(pos.relative(d));
			if (n.getCollisionShape(level, pos.relative(d)).isEmpty() && n.getFluidState().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private boolean drops(ServerLevel level, BlockPos pos, BlockState state) {
		ItemStack tool = bestToolFor(state);
		String key = state + "|" + tool.getItem();
		return dropsCache.computeIfAbsent(key, k -> {
			// Loot tables list e.g. cobblestone for stone regardless of the tool; the "needs a
			// pickaxe" rule is applied separately when the block breaks.
			if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state)) {
				return false;
			}
			for (ItemStack drop : Block.getDrops(state, level, pos, level.getBlockEntity(pos), agent, tool)) {
				if (drop.is(item)) {
					return true;
				}
			}
			return false;
		});
	}

	private ItemStack bestToolFor(BlockState state) {
		var inv = agent.getInventory();
		ItemStack best = inv.getSelected();
		for (ItemStack s : inv.items) {
			if (s.isCorrectToolForDrops(state) && !best.isCorrectToolForDrops(state)
					|| s.getDestroySpeed(state) > best.getDestroySpeed(state)) {
				best = s;
			}
		}
		return best;
	}

	@Override
	public void abort() {
		if (child != null) {
			child.abort();
		}
		super.abort();
	}
}
