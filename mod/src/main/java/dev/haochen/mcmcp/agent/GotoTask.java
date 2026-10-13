package dev.haochen.mcmcp.agent;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Walks to a goal: plans with {@link Pathfinder}, breaks the blocks a step needs (when digging is
 * allowed), steers along the path, and re-plans when the path ends short of the goal or the agent
 * stops making progress (blocked by a mob, slipped off a ledge, ...). Fails after a few re-plans.
 */
public final class GotoTask extends AgentTask {
	private static final int STUCK_TICKS = 50;
	private static final int MAX_REPLANS = 4;

	private final Pathfinder.Goal goal;
	private final String what;
	private final boolean dig;
	private List<Pathfinder.Step> steps;
	private Miner miner;
	private int replans;
	private int dug;
	private double bestRemaining = Double.MAX_VALUE;
	private int ticksSinceProgress;

	public GotoTask(AgentPlayer agent, Pathfinder.Goal goal, String what, boolean dig) {
		super(agent);
		this.goal = goal;
		this.what = what;
		this.dig = dig;
	}

	@Override
	public Status tick() {
		BlockPos feet = agent.blockPosition();
		if (goal.reached(feet) && (agent.onGround() || agent.isInWater())) {
			agent.stop();
			result.addProperty("reached", true);
			if (dug > 0) {
				result.addProperty("blocksDug", dug);
			}
			return Status.DONE;
		}
		if (steps == null) {
			plan(feet);
		}
		// Break whatever the next step needs before walking into it.
		int i = agent.waypointIndex();
		if (i < steps.size()) {
			for (BlockPos b : steps.get(i).dig()) {
				if (!agent.serverLevel().getBlockState(b).isAir()) {
					if (miner == null || !miner.pos().equals(b)) {
						miner = new Miner(b);
					}
					if (miner.tick(agent)) {
						miner = null;
						dug++;
					}
					ticksSinceProgress = 0;
					return Status.RUNNING;
				}
			}
		}
		double remaining = goal.estimate(feet);
		if (remaining < bestRemaining - 0.25) {
			bestRemaining = remaining;
			ticksSinceProgress = 0;
		} else if (++ticksSinceProgress > STUCK_TICKS) {
			replan(feet, "stuck");
		}
		if (!agent.steer() && (agent.onGround() || agent.isInWater())) {
			// Path used up but goal not reached (e.g. we were pushed): plan again from here.
			replan(feet, "path ended");
		}
		return Status.RUNNING;
	}

	private void replan(BlockPos feet, String why) {
		if (++replans > MAX_REPLANS) {
			throw new Failure("could not reach " + what + ": " + why + " at " + feet.toShortString()
					+ " after " + MAX_REPLANS + " re-plans");
		}
		ticksSinceProgress = 0;
		bestRemaining = Double.MAX_VALUE;
		plan(feet);
	}

	private void plan(BlockPos feet) {
		steps = new Pathfinder(agent, dig).find(feet, goal);
		if (steps == null) {
			throw new Failure("no path to " + what + " from " + feet.toShortString()
					+ " (searched " + Pathfinder.MAX_NODES + " positions within " + Pathfinder.MAX_RANGE
					+ " blocks; the agent walks, jumps 1 block and drops up to 4"
					+ (dig ? ", and digs through blocks that are safe to dig" : "; pass dig=true to let it dig") + ")");
		}
		agent.setPath(steps.stream().map(Pathfinder.Step::feet).toList());
	}

	@Override
	public void abort() {
		if (miner != null) {
			miner.cancel(agent);
		}
		super.abort();
	}
}
