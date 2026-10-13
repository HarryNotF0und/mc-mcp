package dev.haochen.mcmcp.api;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.Services;
import dev.haochen.mcmcp.jobs.PlaceJob;
import dev.haochen.mcmcp.world.BlockList;
import dev.haochen.mcmcp.world.Guard;
import net.minecraft.server.level.ServerLevel;

/**
 * A computed set of blocks (from a script or blueprint) that can be dry-run, previewed or placed.
 * {@code stats} is what a dry run returns (block counts, bounds, script logs, ...).
 */
public record Plan(ServerLevel level, BlockList blocks, String kind, String description, boolean loadChunks,
				   JsonObject stats) {
	/** Runs the same checks as every other write. Server thread. */
	public void validate(Services s) {
		if (blocks.size() == 0) {
			throw Params.invalid("nothing to place: the block list is empty");
		}
		s.guard().checkVolume(blocks.size());
		s.guard().checkRegion(level, blocks.bounds());
		Guard.checkChunks(level, blocks.chunks(), loadChunks);
	}

	/** Validates and queues the blocks as one undoable job. Server thread. */
	public JsonObject submit(Services s) {
		validate(s);
		return BuildApi.submit(s, new PlaceJob(kind, description, level, blocks.size(), blocks.placements(), s.history()));
	}
}
