package dev.haochen.mcmcp.agent;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A* over feet positions. Moves: walk to a horizontal neighbour, step up one block (jump), drop
 * down up to 4 blocks, and, when digging is allowed, the same moves through breakable blocks
 * plus digging straight down. Digging costs the block's real break time with the agent's best
 * tool. Never digs next to fluids, under falling blocks, into block entities or very hard blocks;
 * avoids water, lava, fire and other hazards. Only loaded chunks are searched. Server thread.
 */
public final class Pathfinder {
	/** What the agent is trying to reach. */
	public interface Goal {
		boolean reached(BlockPos feet);

		/** Admissible-ish estimate of the remaining cost. */
		double estimate(BlockPos feet);
	}

	/** A waypoint and the blocks to break before moving into it. */
	public record Step(BlockPos feet, List<BlockPos> dig) {
	}

	public static final int MAX_NODES = 30_000;
	public static final int MAX_RANGE = 96;
	private static final int MAX_DROP = 4; // a 4-block fall costs half a heart
	/** Blocks that take longer than this to break (with the best tool) are treated as walls. */
	private static final int MAX_BREAK_TICKS = 100;
	private static final double TICKS_PER_COST = 5; // walking one block takes ~5 ticks

	private record Node(long pos, double f) {
	}

	private final ServerLevel level;
	private final AgentPlayer agent;
	private final boolean dig;

	public Pathfinder(AgentPlayer agent, boolean dig) {
		this.level = agent.serverLevel();
		this.agent = agent;
		this.dig = dig;
	}

	public static Goal near(BlockPos target, double range) {
		return new Goal() {
			@Override
			public boolean reached(BlockPos feet) {
				return feet.distSqr(target) <= range * range;
			}

			@Override
			public double estimate(BlockPos feet) {
				return Math.max(0, Math.sqrt(feet.distSqr(target)) - range);
			}
		};
	}

	/** Standing somewhere the eyes are within {@code reach} of the block's centre, not inside it. */
	public static Goal withinReach(BlockPos block, double reach) {
		return new Goal() {
			@Override
			public boolean reached(BlockPos feet) {
				if (feet.equals(block) || feet.above().equals(block)) {
					return false;
				}
				double dx = feet.getX() + 0.5 - (block.getX() + 0.5);
				double dy = feet.getY() + 1.62 - (block.getY() + 0.5);
				double dz = feet.getZ() + 0.5 - (block.getZ() + 0.5);
				return dx * dx + dy * dy + dz * dz <= reach * reach;
			}

			@Override
			public double estimate(BlockPos feet) {
				return Math.max(0, Math.sqrt(feet.distSqr(block)) - reach);
			}
		};
	}

	/** Returns the steps after {@code start} up to a position satisfying the goal, or null. */
	public List<Step> find(BlockPos start, Goal goal) {
		if (goal.reached(start)) {
			return List.of();
		}
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		Long2DoubleOpenHashMap g = new Long2DoubleOpenHashMap();
		g.defaultReturnValue(Double.MAX_VALUE);
		Long2LongOpenHashMap cameFrom = new Long2LongOpenHashMap();
		Long2ObjectOpenHashMap<List<BlockPos>> digFor = new Long2ObjectOpenHashMap<>();
		LongOpenHashSet closed = new LongOpenHashSet();
		long s = start.asLong();
		g.put(s, 0);
		open.add(new Node(s, goal.estimate(start)));
		int expanded = 0;
		List<Edge> edges = new ArrayList<>(12);
		while (!open.isEmpty() && expanded < MAX_NODES) {
			Node node = open.poll();
			if (!closed.add(node.pos)) {
				continue;
			}
			expanded++;
			BlockPos cur = BlockPos.of(node.pos);
			if (goal.reached(cur)) {
				return reconstruct(cameFrom, digFor, s, node.pos);
			}
			edges.clear();
			neighbours(cur, start, edges);
			for (Edge e : edges) {
				long key = e.to.asLong();
				double tentative = g.get(node.pos) + e.cost;
				if (tentative < g.get(key)) {
					g.put(key, tentative);
					cameFrom.put(key, node.pos);
					digFor.put(key, e.dig);
					open.add(new Node(key, tentative + goal.estimate(e.to)));
				}
			}
		}
		return null;
	}

	private record Edge(BlockPos to, double cost, List<BlockPos> dig) {
	}

	private void neighbours(BlockPos cur, BlockPos start, List<Edge> out) {
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos side = cur.relative(dir);
			if (Math.abs(side.getX() - start.getX()) > MAX_RANGE || Math.abs(side.getZ() - start.getZ()) > MAX_RANGE) {
				continue;
			}
			// Walk (tunnel if digging).
			if (solidGround(side.below())) {
				List<BlockPos> d = clear(side, side.above());
				if (d != null) {
					out.add(new Edge(side, 1 + cost(d), d));
				}
			}
			// Step up one block (dig out the headroom if needed).
			BlockPos up = side.above();
			if (solidGround(side)) {
				List<BlockPos> d = clear(cur.above(2), up, up.above());
				if (d != null) {
					out.add(new Edge(up, 2 + cost(d), d));
				}
			}
			// Drop down (only through open air).
			if (passable(side) && passable(side.above())) {
				for (int k = 1; k <= MAX_DROP; k++) {
					BlockPos down = side.below(k);
					if (standable(down)) {
						out.add(new Edge(down, 1 + 0.5 * k + (k == MAX_DROP ? 2 : 0), List.of()));
						break;
					}
					if (!passable(down)) {
						break;
					}
				}
			}
		}
		// Dig straight down (out of pits, towards ores).
		if (dig) {
			BlockPos down = cur.below();
			if (breakTicks(down) >= 0 && solidGround(down.below())) {
				List<BlockPos> d = List.of(down);
				out.add(new Edge(down, 1 + cost(d), d));
			}
		}
	}

	/** The blocks to break so that all positions are passable, or null if impossible. */
	private List<BlockPos> clear(BlockPos... positions) {
		List<BlockPos> d = null;
		for (BlockPos p : positions) {
			if (passable(p)) {
				continue;
			}
			if (!dig || breakTicks(p) < 0) {
				return null;
			}
			if (d == null) {
				d = new ArrayList<>(3);
			}
			d.add(p);
		}
		return d == null ? List.of() : d;
	}

	private double cost(List<BlockPos> blocks) {
		double c = 0;
		for (BlockPos p : blocks) {
			c += breakTicks(p) / TICKS_PER_COST + 1;
		}
		return c;
	}

	/** Ticks to break with the best inventory tool, or -1 if the agent should not dig it. */
	public int breakTicks(BlockPos pos) {
		if (!level.hasChunkAt(pos)) {
			return -1;
		}
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty() || hazard(state) || state.hasBlockEntity()
				|| state.getBlock() instanceof FallingBlock || state.is(Blocks.BEDROCK)) {
			return -1;
		}
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0) {
			return -1;
		}
		for (Direction d : Direction.values()) {
			if (!level.getFluidState(pos.relative(d)).isEmpty()) {
				return -1; // would let water or lava in
			}
		}
		if (state.getBlock() instanceof FallingBlock || level.getBlockState(pos.above()).getBlock() instanceof FallingBlock) {
			return -1;
		}
		if (hardness == 0) {
			return 1;
		}
		float speed = 1;
		boolean harvest = !state.requiresCorrectToolForDrops();
		for (ItemStack s : agent.getInventory().items) {
			speed = Math.max(speed, s.getDestroySpeed(state));
			harvest |= s.isCorrectToolForDrops(state);
		}
		int ticks = (int) Math.ceil(hardness * (harvest ? 30 : 100) / speed);
		return ticks > MAX_BREAK_TICKS ? -1 : ticks;
	}

	private static List<Step> reconstruct(Long2LongOpenHashMap cameFrom, Long2ObjectOpenHashMap<List<BlockPos>> digFor,
										  long start, long end) {
		List<Step> path = new ArrayList<>();
		for (long p = end; p != start; p = cameFrom.get(p)) {
			path.add(new Step(BlockPos.of(p), digFor.get(p)));
		}
		Collections.reverse(path);
		return path;
	}

	/** Feet and head fit, and there is solid ground below. */
	public boolean standable(BlockPos feet) {
		return passable(feet) && passable(feet.above()) && solidGround(feet.below());
	}

	public boolean passable(BlockPos pos) {
		if (!level.hasChunkAt(pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		if (!state.getFluidState().isEmpty() || hazard(state)) {
			return false;
		}
		return state.getCollisionShape(level, pos).isEmpty();
	}

	private boolean solidGround(BlockPos pos) {
		if (!level.hasChunkAt(pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		return !hazard(state) && state.isFaceSturdy(level, pos, Direction.UP);
	}

	private static boolean hazard(BlockState state) {
		return state.is(BlockTags.FIRE) || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
				|| state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.COBWEB)
				|| state.is(Blocks.POWDER_SNOW) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
				|| state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POINTED_DRIPSTONE);
	}
}
