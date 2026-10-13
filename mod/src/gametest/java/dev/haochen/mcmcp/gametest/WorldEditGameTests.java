package dev.haochen.mcmcp.gametest;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.api.BlueprintApi;
import dev.haochen.mcmcp.api.BuildApi;
import dev.haochen.mcmcp.api.Json;
import dev.haochen.mcmcp.jobs.Job;
import dev.haochen.mcmcp.world.Region;
import dev.haochen.mcmcp.worldedit.WorldEditBridge;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Runs with WorldEdit on the dev classpath (modLocalRuntime). */
public class WorldEditGameTests implements FabricGameTest {
	private static final String EMPTY = FabricGameTest.EMPTY_STRUCTURE;

	@GameTest(template = EMPTY)
	public void fillAcceptsWorldEditPatterns(GameTestHelper helper) {
		requireWorldEdit(helper);
		McMcpGameTests.allowEverywhere();
		JsonObject params = fill(helper, new BlockPos(1, 1, 1), new BlockPos(4, 1, 4), "50%minecraft:stone,50%minecraft:andesite");
		int job = BuildApi.fillRegion(helper.getLevel().getServer(), params).get("jobId").getAsInt();
		helper.startSequence()
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job))
				.thenExecute(() -> {
					int stone = 0, andesite = 0;
					for (int x = 1; x <= 4; x++) {
						for (int z = 1; z <= 4; z++) {
							BlockState s = helper.getBlockState(new BlockPos(x, 1, z));
							if (s.is(Blocks.STONE)) stone++;
							else if (s.is(Blocks.ANDESITE)) andesite++;
							else throw new GameTestAssertException("unexpected " + s);
						}
					}
					helper.assertTrue(stone > 0 && andesite > 0, "both blocks used: " + stone + "/" + andesite);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY)
	public void blacklistedBlockInsidePatternFailsTheJob(GameTestHelper helper) {
		requireWorldEdit(helper);
		McMcpGameTests.allowEverywhere();
		JsonObject params = fill(helper, new BlockPos(1, 1, 1), new BlockPos(3, 1, 3), "50%minecraft:tnt,50%minecraft:stone");
		int job = BuildApi.fillRegion(helper.getLevel().getServer(), params).get("jobId").getAsInt();
		helper.succeedWhen(() -> {
			Job j = McMcpMod.services().jobs().get(job);
			if (!j.state().finished()) {
				throw new GameTestAssertException("still running");
			}
			helper.assertTrue(j.state() == Job.State.FAILED && j.toJson().get("error").getAsString().contains("blacklisted"),
					"job failed with blacklist error: " + j.toJson());
			// Blocks placed before the failure stay and are recorded as one undoable change.
		});
	}

	@GameTest(template = EMPTY)
	public void schematicRoundTripWithRotation(GameTestHelper helper) {
		requireWorldEdit(helper);
		McMcpGameTests.allowEverywhere();
		var server = helper.getLevel().getServer();
		BlockPos src = new BlockPos(1, 1, 1);
		helper.setBlock(src, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
		helper.setBlock(src.offset(1, 0, 0), Blocks.STONE);

		JsonObject save = new JsonObject();
		save.add("region", Region.of(helper.absolutePos(src), helper.absolutePos(src.offset(1, 0, 0))).toJson());
		save.addProperty("name", "gametest-probe");
		save.addProperty("format", "schem");
		String file = BlueprintApi.saveRegion(server, save).get("schematic").getAsString();
		helper.assertTrue(file.equals("gametest-probe.schem"), "saved " + file);

		BlockPos dst = new BlockPos(4, 1, 4);
		JsonObject place = new JsonObject();
		place.addProperty("schematic", "gametest-probe");
		place.add("origin", Json.blockPos(helper.absolutePos(dst)));
		place.addProperty("rotation", 90);
		int job = BlueprintApi.placeBlueprint(server, place).get("jobId").getAsInt();
		helper.startSequence()
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job))
				.thenExecute(() -> {
					// 2 x 1 x 1 rotated 90: (x, z) -> (sz - 1 - z, x) = (0, x)
					helper.assertBlockProperty(dst, StairBlock.FACING, Direction.EAST);
					helper.assertBlockPresent(Blocks.STONE, dst.offset(0, 0, 1));
				})
				.thenSucceed();
	}

	private static JsonObject fill(GameTestHelper helper, BlockPos min, BlockPos max, String block) {
		JsonObject params = new JsonObject();
		params.add("region", Region.of(helper.absolutePos(min), helper.absolutePos(max)).toJson());
		params.addProperty("block", block);
		return params;
	}

	private static void requireWorldEdit(GameTestHelper helper) {
		if (WorldEditBridge.get() == null) {
			throw new GameTestAssertException("WorldEdit is not on the GameTest classpath");
		}
	}
}
