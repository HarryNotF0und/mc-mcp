package dev.haochen.mcmcp.jobs;

import dev.haochen.mcmcp.Services;
import dev.haochen.mcmcp.api.Plan;
import dev.haochen.mcmcp.arch.BuildingRegistry;
import dev.haochen.mcmcp.history.BlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.Iterator;

/** Placement whose registry metadata and originals follow actual execution and history. */
public final class BuildingJob extends PlaceJob {
	private final Services services;
	private final BuildingRegistry.Entry entry;
	private final BuildingRegistry.State before;
	private final Plan plan;

	public BuildingJob(Services services, BuildingRegistry.Entry entry, Plan plan, Iterator<Placement> source) {
		super(plan.kind(), plan.description(), plan.level(), plan.blocks().size(), source, services.history());
		this.services = services;
		this.entry = entry;
		this.plan = plan;
		this.before = entry.applied != null ? entry.applied : entry.snapshot();
	}

	@Override
	protected void start(MinecraftServer server) {
		plan.validate(services);
		entry.incomplete = true;
		services.buildings().save(entry);
	}

	@Override
	protected void beforeSet(BlockPos pos, BlockSnapshot before) {
		BuildingRegistry.captureOriginal(entry, pos, before);
	}

	@Override
	protected void onFinished() {
		entry.pendingJobId = -1;
		if (state() == State.DONE) {
			entry.built = true;
			entry.incomplete = false;
			entry.applied = entry.snapshot();
		} else if (changes().isEmpty()) {
			entry.restore(before);
		} else {
			entry.built = true;
			entry.incomplete = true;
			entry.applied = entry.snapshot();
		}
		BuildingRegistry.State after = entry.snapshot();
		changes().onRestore(jobId -> {
			entry.requireIdle();
			if (entry.previewId >= 0 && services.previews().active(entry.previewId)) {
				services.previews().discard(entry.previewId);
			}
			entry.pendingJobId = jobId;
			entry.incomplete = true;
		}, (undo, complete) -> {
			entry.pendingJobId = -1;
			if (complete) entry.restore(undo ? before : after);
			else entry.incomplete = true;
			services.buildings().save(entry);
		});
		services.buildings().save(entry);
	}
}
