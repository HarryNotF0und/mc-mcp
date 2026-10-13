package dev.haochen.mcmcp.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpConfig;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.api.BuildApi;
import dev.haochen.mcmcp.api.Json;
import dev.haochen.mcmcp.jobs.Job;
import dev.haochen.mcmcp.jobs.RestoreJob;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.LayerFormat;
import dev.haochen.mcmcp.world.Region;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.List;
import java.util.function.Supplier;

/**
 * Tests that use undo/redo get their own batch: history is global, and tests within one batch
 * run at the same time.
 */
public class McMcpGameTests implements FabricGameTest {
	private static final String EMPTY = FabricGameTest.EMPTY_STRUCTURE;

	@GameTest(template = EMPTY, batch = "history_fill")
	public void hollowFillThenUndoRestoresBlocksAndChestContents(GameTestHelper helper) {
		allowEverywhere();
		BlockPos min = new BlockPos(1, 1, 1), max = new BlockPos(5, 5, 5), center = new BlockPos(3, 3, 3);
		helper.setBlock(min, Blocks.STONE);
		helper.setBlock(center, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(center);
		chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
		int[] job = new int[1];

		helper.startSequence()
				.thenExecute(() -> job[0] = fill(helper, min, max, "minecraft:stone_bricks", "hollow"))
				.thenWaitUntil(() -> assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.STONE_BRICKS, min);
					helper.assertBlockPresent(Blocks.STONE_BRICKS, max);
					helper.assertBlockPresent(Blocks.STONE_BRICKS, new BlockPos(5, 3, 3));
					helper.assertBlockPresent(Blocks.AIR, center);
					helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 2, 2));
					job[0] = submit(new RestoreJob(McMcpMod.services().history(), true));
				})
				.thenWaitUntil(() -> assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.STONE, min);
					helper.assertBlockPresent(Blocks.AIR, max);
					helper.assertBlockPresent(Blocks.CHEST, center);
					ChestBlockEntity restored = helper.getBlockEntity(center);
					ItemStack stack = restored.getItem(0);
					helper.assertTrue(stack.is(Items.DIAMOND) && stack.getCount() == 5, "chest contents restored, got " + stack);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY, batch = "history_redo")
	public void placeBlocksUndoRedo(GameTestHelper helper) {
		allowEverywhere();
		BlockPos a = new BlockPos(2, 1, 2), b = new BlockPos(3, 1, 2);
		JsonArray blocks = new JsonArray();
		JsonArray tuple = Json.blockPos(helper.absolutePos(a));
		tuple.add("minecraft:oak_planks");
		blocks.add(tuple);
		JsonObject obj = new JsonObject();
		obj.add("pos", Json.blockPos(helper.absolutePos(b)));
		obj.addProperty("block", "minecraft:oak_stairs[facing=east]");
		blocks.add(obj);
		JsonObject params = new JsonObject();
		params.add("blocks", blocks);
		int[] job = new int[1];

		helper.startSequence()
				.thenExecute(() -> job[0] = BuildApi.placeBlocks(helper.getLevel().getServer(), params).get("jobId").getAsInt())
				.thenWaitUntil(() -> assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.OAK_PLANKS, a);
					helper.assertBlockProperty(b, StairBlock.FACING, Direction.EAST);
					job[0] = submit(new RestoreJob(McMcpMod.services().history(), true));
				})
				.thenWaitUntil(() -> assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.AIR, a);
					helper.assertBlockPresent(Blocks.AIR, b);
					job[0] = submit(new RestoreJob(McMcpMod.services().history(), false));
				})
				.thenWaitUntil(() -> assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.OAK_PLANKS, a);
					helper.assertBlockProperty(b, StairBlock.FACING, Direction.EAST);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY, batch = "history_shapes")
	public void shapesUpdateInsideTheJobButNeverOutside(GameTestHelper helper) {
		allowEverywhere();
		// An existing fence at x=1 must not connect to (or be changed by) the new fences at x=2..4.
		BlockPos outside = new BlockPos(1, 1, 3);
		helper.setBlock(outside, Blocks.OAK_FENCE);
		BlockState outsideBefore = helper.getBlockState(outside);
		int[] job = new int[1];

		helper.startSequence()
				.thenExecute(() -> job[0] = fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 1, 3), "minecraft:oak_fence", "solid"))
				.thenWaitUntil(() -> assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertTrue(helper.getBlockState(outside) == outsideBefore, "fence outside the job unchanged");
					helper.assertBlockProperty(new BlockPos(2, 1, 3), FenceBlock.EAST, true);
					helper.assertBlockProperty(new BlockPos(3, 1, 3), FenceBlock.WEST, true);
					helper.assertBlockProperty(new BlockPos(3, 1, 3), FenceBlock.EAST, true);
					helper.assertBlockProperty(new BlockPos(4, 1, 3), FenceBlock.WEST, true);
					job[0] = submit(new RestoreJob(McMcpMod.services().history(), true));
				})
				.thenWaitUntil(() -> assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertTrue(helper.getBlockState(outside) == outsideBefore, "fence outside unchanged after undo");
					helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 1, 3));
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY)
	public void guardsRejectBadRequests(GameTestHelper helper) {
		allowEverywhere();
		var server = helper.getLevel().getServer();
		BlockPos far = new BlockPos(29_500_000, 70, 0);
		expectCode(helper, RpcException.OUTSIDE_ALLOWED_REGION, () ->
				BuildApi.fillRegion(server, fillParams(far, far.offset(1, 1, 1), "minecraft:stone", "solid")));
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		expectCode(helper, RpcException.VOLUME_TOO_LARGE, () ->
				BuildApi.fillRegion(server, fillParams(origin, origin.offset(99, 99, 99), "minecraft:stone", "solid")));
		expectCode(helper, RpcException.UNKNOWN_BLOCK, () ->
				BuildApi.fillRegion(server, fillParams(origin, origin, "minecraft:tnt", "solid")));
		expectCode(helper, RpcException.UNKNOWN_BLOCK, () ->
				BuildApi.fillRegion(server, fillParams(origin, origin, "minecraft:not_a_block", "solid")));
		expectCode(helper, RpcException.UNKNOWN_BLOCK, () ->
				BuildApi.fillRegion(server, fillParams(origin, origin, "minecraft:oak_stairs[facing=up]", "solid")));
		expectCode(helper, RpcException.INVALID_PARAMS, () ->
				BuildApi.fillRegion(server, fillParams(origin, origin, "minecraft:stone", "sideways")));
		helper.succeed();
	}

	@GameTest(template = EMPTY)
	public void layerSlicesUsePaletteAndAxisOrder(GameTestHelper helper) {
		// z=1 row: stone stone air ; z=2 row: air glass air  (x increases to the right)
		helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
		helper.setBlock(new BlockPos(2, 1, 1), Blocks.STONE);
		helper.setBlock(new BlockPos(2, 1, 2), Blocks.GLASS);
		Region region = Region.of(helper.absolutePos(new BlockPos(1, 1, 1)), helper.absolutePos(new BlockPos(3, 1, 2)));
		JsonObject out = LayerFormat.encode(helper.getLevel(), region);

		helper.assertTrue(out.getAsJsonObject("palette").get(".").getAsString().equals("minecraft:air"), "air is '.': " + out);
		helper.assertTrue(out.getAsJsonObject("palette").get("S").getAsString().equals("minecraft:stone"), "stone is 'S': " + out);
		helper.assertTrue(out.getAsJsonObject("palette").get("G").getAsString().equals("minecraft:glass"), "glass is 'G': " + out);
		JsonArray layer = out.getAsJsonArray("layers").get(0).getAsJsonArray();
		helper.assertTrue(layer.get(0).getAsString().equals("SS.") && layer.get(1).getAsString().equals(".G."), "layer rows: " + layer);
		helper.succeed();
	}

	private static void allowEverywhere() {
		McMcpConfig config = McMcpMod.services().config();
		config.allowedRegions = List.of(new McMcpConfig.Region("minecraft:overworld",
				new int[]{-29_000_000, -64, -29_000_000}, new int[]{29_000_000, 319, 29_000_000}));
	}

	private static int fill(GameTestHelper helper, BlockPos min, BlockPos max, String block, String mode) {
		JsonObject params = fillParams(helper.absolutePos(min), helper.absolutePos(max), block, mode);
		return BuildApi.fillRegion(helper.getLevel().getServer(), params).get("jobId").getAsInt();
	}

	private static JsonObject fillParams(BlockPos min, BlockPos max, String block, String mode) {
		JsonObject params = new JsonObject();
		params.add("region", Region.of(min, max).toJson());
		params.addProperty("block", block);
		params.addProperty("mode", mode);
		params.addProperty("loadChunks", true);
		return params;
	}

	private static int submit(Job job) {
		return McMcpMod.services().jobs().submit(job).id();
	}

	private static void assertDone(GameTestHelper helper, int id) {
		Job job = McMcpMod.services().jobs().get(id);
		if (!job.state().finished()) {
			throw new GameTestAssertException("job " + id + " still " + job.state().id());
		}
		helper.assertTrue(job.state() == Job.State.DONE, "job finished: " + job.toJson());
	}

	private static void expectCode(GameTestHelper helper, int code, Supplier<?> call) {
		try {
			call.get();
		} catch (RpcException e) {
			helper.assertTrue(e.code() == code, "expected error " + code + ", got " + e.code() + ": " + e.getMessage());
			return;
		}
		throw new GameTestAssertException("expected error " + code + " but the call succeeded");
	}
}
