package dev.haochen.mcmcp.agent;

import dev.haochen.mcmcp.world.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;

/**
 * Walks within reach and places one block from the inventory, consuming the item. Like a player,
 * it needs a replaceable target (air, grass, water...), a neighbouring block to place against,
 * and the block must be able to survive there; the exact requested state is placed.
 */
public final class PlaceTask extends AgentTask {
	private final BlockPos pos;
	private final BlockState state;
	private final Item item;
	private GotoTask approach;

	public PlaceTask(AgentPlayer agent, BlockPos pos, BlockState state) {
		super(agent);
		this.pos = pos.immutable();
		this.state = state;
		this.item = state.getBlock().asItem();
	}

	/** Checks that placement is possible (other than reach and having the item); returns a reason or null. */
	public static String problem(AgentPlayer agent, BlockPos pos, BlockState state) {
		var level = agent.serverLevel();
		BlockState existing = level.getBlockState(pos);
		if (!existing.canBeReplaced()) {
			return Blocks.id(existing) + " is in the way at " + pos.toShortString();
		}
		boolean support = false;
		for (Direction d : Direction.values()) {
			BlockState n = level.getBlockState(pos.relative(d));
			if (!n.isAir() && n.getFluidState().isEmpty()) {
				support = true;
				break;
			}
		}
		if (!support) {
			return "nothing to place against at " + pos.toShortString();
		}
		if (!state.canSurvive(level, pos)) {
			return Blocks.id(state) + " cannot stay at " + pos.toShortString();
		}
		return null;
	}

	@Override
	public Status tick() {
		if (Inventories.count(agent.getInventory(), item) == 0) {
			throw new Failure("no " + Inventories.id(item) + " in the inventory");
		}
		if (approach == null) {
			String problem = problem(agent, pos, state);
			if (problem != null) {
				throw new Failure(problem);
			}
			approach = new GotoTask(agent, clearOf(pos), "a spot within reach of " + pos.toShortString(), true);
		}
		if (approach.tick() == Status.RUNNING) {
			return Status.RUNNING;
		}
		place(agent, pos, state);
		result.addProperty("placed", Blocks.toString(state));
		return Status.DONE;
	}

	/**
	 * Within reach, but not so close that the agent's body overlaps the target: it stops anywhere
	 * within ~0.35 of a cell's centre, so a block at body height needs 2 cells of distance.
	 */
	static Pathfinder.Goal clearOf(BlockPos pos) {
		Pathfinder.Goal reach = Pathfinder.withinReach(pos, MineTask.REACH - 0.3);
		return new Pathfinder.Goal() {
			@Override
			public boolean reached(BlockPos feet) {
				boolean bodyHeight = pos.getY() == feet.getY() || pos.getY() == feet.getY() + 1;
				int manhattan = Math.abs(feet.getX() - pos.getX()) + Math.abs(feet.getZ() - pos.getZ());
				return reach.reached(feet) && (!bodyHeight || manhattan >= 2);
			}

			@Override
			public double estimate(BlockPos feet) {
				return reach.estimate(feet);
			}
		};
	}

	/** Places immediately (caller has checked reach, item and {@link #problem}). */
	static void place(AgentPlayer agent, BlockPos pos, BlockState state) {
		var level = agent.serverLevel();
		if (!state.getCollisionShape(level, pos).isEmpty() && agent.getBoundingBox().intersects(new AABB(pos))) {
			throw new Failure("the agent is standing where " + pos.toShortString() + " should go");
		}
		Item item = state.getBlock().asItem();
		Inventories.holdItem(agent.getInventory(), item);
		agent.lookAt(pos);
		level.setBlock(pos, state, 3);
		Inventories.consumeOne(agent.getInventory(), item);
		agent.swing(InteractionHand.MAIN_HAND);
		SoundType sound = state.getSoundType();
		level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f);
		level.gameEvent(agent, GameEvent.BLOCK_PLACE, pos);
	}
}
