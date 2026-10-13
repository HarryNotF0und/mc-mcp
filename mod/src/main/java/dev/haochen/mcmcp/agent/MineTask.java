package dev.haochen.mcmcp.agent;

import dev.haochen.mcmcp.world.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gets within reach of a block (digging a way there if allowed) and breaks it like a survival
 * player with the best tool in the inventory. Drops fall as item entities (see collect).
 */
public final class MineTask extends AgentTask {
	public static final double REACH = 4.5;

	private final BlockPos pos;
	private final boolean dig;
	private GotoTask approach;
	private BlockState target;
	private Miner miner;

	public MineTask(AgentPlayer agent, BlockPos pos, boolean dig) {
		super(agent);
		this.pos = pos.immutable();
		this.dig = dig;
	}

	@Override
	public Status tick() {
		var level = agent.serverLevel();
		BlockState state = level.getBlockState(pos);
		if (target == null) {
			if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty()) {
				throw new Failure("nothing to mine at " + pos.toShortString() + " (" + Blocks.toString(state) + ")");
			}
			if (state.getDestroySpeed(level, pos) < 0) {
				throw new Failure(Blocks.id(state) + " at " + pos.toShortString() + " is unbreakable");
			}
			target = state;
			result.addProperty("block", Blocks.id(state));
		}
		if (state.getBlock() != target.getBlock()) {
			// Someone else broke or replaced it (or we dug it on the way).
			return Status.DONE;
		}
		if (approach == null) {
			approach = new GotoTask(agent, Pathfinder.withinReach(pos, REACH - 0.3), "a spot within reach of " + pos.toShortString(), dig);
		}
		if (approach.tick() == Status.RUNNING) {
			return Status.RUNNING;
		}
		if (miner == null) {
			miner = new Miner(pos);
			Inventories.holdBestTool(agent, state);
			result.addProperty("tool", Inventories.id(agent.getMainHandItem().getItem()));
			if (!agent.hasCorrectToolForDrops(state)) {
				result.addProperty("warning", Blocks.id(state) + " drops nothing without the right tool");
			}
		}
		if (miner.tick(agent)) {
			result.addProperty("ticks", miner.ticks());
			return Status.DONE;
		}
		return Status.RUNNING;
	}

	@Override
	public void abort() {
		if (miner != null) {
			miner.cancel(agent);
		}
		if (approach != null) {
			approach.abort();
		}
		super.abort();
	}
}
