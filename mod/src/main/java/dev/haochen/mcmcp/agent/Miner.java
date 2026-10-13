package dev.haochen.mcmcp.agent;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaks one block the way a survival player does: best inventory tool, vanilla break speed
 * ({@code BlockState.getDestroyProgress}), crack animation, drops via the game mode.
 */
final class Miner {
	private final BlockPos pos;
	private float progress;
	private int ticks;

	Miner(BlockPos pos) {
		this.pos = pos.immutable();
	}

	BlockPos pos() {
		return pos;
	}

	int ticks() {
		return ticks;
	}

	/** Advances one tick; true once the block is gone. */
	boolean tick(AgentPlayer agent) {
		var level = agent.serverLevel();
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return true;
		}
		if (ticks == 0) {
			Inventories.holdBestTool(agent, state);
		}
		ticks++;
		agent.stop();
		agent.lookAt(pos);
		progress += state.getDestroyProgress(agent, level, pos);
		level.destroyBlockProgress(agent.getId(), pos, Math.min(9, (int) (progress * 10)));
		if (ticks % 5 == 1) {
			agent.swing(InteractionHand.MAIN_HAND);
		}
		if (progress >= 1) {
			agent.gameMode.destroyBlock(pos);
			cancel(agent);
			return true;
		}
		return false;
	}

	void cancel(AgentPlayer agent) {
		agent.serverLevel().destroyBlockProgress(agent.getId(), pos, -1);
	}
}
