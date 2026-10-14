package dev.haochen.mcmcp.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.api.BlueprintApi;
import dev.haochen.mcmcp.api.Json;
import dev.haochen.mcmcp.api.ScriptApi;
import dev.haochen.mcmcp.blueprint.Blueprint;
import dev.haochen.mcmcp.net.RpcDispatcher;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.BlockList;
import dev.haochen.mcmcp.world.LayerFormat;
import dev.haochen.mcmcp.world.Region;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ScriptBlueprintGameTests implements FabricGameTest {
	private static final String EMPTY = FabricGameTest.EMPTY_STRUCTURE;

	@GameTest(template = EMPTY, timeoutTicks = 1000)
	public void cylinderDryRunHasExpectedDiameterAndLogs(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		BlockPos origin = helper.absolutePos(new BlockPos(3, 1, 3));
		var future = script(helper, origin, "b.cylinder(0, 0, 0, 7, 2, 'minecraft:stone');\nb.log('origin', b.origin.x);", true);
		expectResult(helper, future, result -> {
			int expected = 0;
			for (int dx = -7; dx <= 7; dx++) {
				for (int dz = -7; dz <= 7; dz++) {
					if (dx * dx + dz * dz < 7.5 * 7.5) {
						expected++;
					}
				}
			}
			JsonObject r = result.getAsJsonObject();
			helper.assertTrue(r.get("blocks").getAsInt() == expected * 2, "cylinder block count: " + r);
			int minX = r.getAsJsonObject("bounds").getAsJsonArray("min").get(0).getAsInt();
			int maxX = r.getAsJsonObject("bounds").getAsJsonArray("max").get(0).getAsInt();
			helper.assertTrue(maxX - minX + 1 == 15, "diameter 15 for radius 7: " + r);
			helper.assertTrue(r.getAsJsonArray("logs").get(0).getAsString().equals("origin " + origin.getX()), "log: " + r);
			helper.assertTrue(r.get("dryRun").getAsBoolean(), "dry run flag");
		});
	}

	@GameTest(template = EMPTY, timeoutTicks = 1000)
	public void infiniteLoopIsStoppedWithLineNumber(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		McMcpMod.services().config().scriptTimeoutMs = 1000;
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		// The catch must not be able to swallow the timeout.
		var future = script(helper, origin, "var i = 0;\nwhile (true) {\n  try { i++; } catch (e) {}\n}", true);
		expectError(helper, future, e -> {
			helper.assertTrue(e.code() == RpcException.SCRIPT_ERROR, "code 1006, got " + e.code());
			int line = e.data().getAsJsonObject().get("line").getAsInt();
			helper.assertTrue(line >= 2 && line <= 3, "line of the loop, got " + line + ": " + e.getMessage());
			helper.assertTrue(e.getMessage().contains("time limit"), e.getMessage());
		});
	}

	@GameTest(template = EMPTY, timeoutTicks = 1000)
	public void scriptsCannotReachJava(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		var future = script(helper, origin, "b.set(0, 0, 0, 'minecraft:stone');\njava.lang.System.exit(1);", true);
		expectError(helper, future, e -> {
			helper.assertTrue(e.code() == RpcException.SCRIPT_ERROR, "code 1006");
			helper.assertTrue(e.data().getAsJsonObject().get("line").getAsInt() == 2, "line 2: " + e.getMessage());
		});
	}

	@GameTest(template = EMPTY, timeoutTicks = 1000)
	public void modernSyntaxWorksIncludingConstInLoops(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		var future = script(helper, origin, String.join("\n",
				"const N = 3;",
				"for (let i = 0; i < N; i++) {",
				"  const x = i; // const inside a loop body",
				"  b.set(x, 0, 0, 'minecraft:stone');",
				"}",
				"for (const [dx, dz] of [[0, 1], [0, 2]]) b.set(dx, 0, dz, 'minecraft:glass');",
				"const f = (v) => v * 2;",
				"b.log(`x${f(1)}`, [...[1, 2]].length, 'const stays in strings');"), true);
		expectResult(helper, future, result -> {
			JsonObject r = result.getAsJsonObject();
			helper.assertTrue(r.get("blocks").getAsInt() == 5, "3 stones + 2 glass: " + r);
			helper.assertTrue(r.getAsJsonArray("logs").get(0).getAsString().equals("x2 2 const stays in strings"), "logs: " + r);
		});
	}

	@GameTest(template = EMPTY, timeoutTicks = 1000)
	public void librariesAndKeepExisting(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		helper.setBlock(new BlockPos(1, 2, 0), Blocks.STONE);
		JsonObject params = new JsonObject();
		params.addProperty("source", "pillar(1, 0, 3);");
		params.add("origin", Json.blockPos(helper.absolutePos(new BlockPos(0, 1, 0))));
		params.addProperty("dryRun", true);
		params.addProperty("keepExisting", true);
		JsonArray libs = new JsonArray();
		JsonObject lib = new JsonObject();
		lib.addProperty("name", "lib/pillar.js");
		lib.addProperty("source", "function pillar(x, z, h) {\n  for (let y = 0; y < h; y++) b.set(x, y, z, 'minecraft:oak_planks');\n}");
		libs.add(lib);
		params.add("libraries", libs);
		expectResult(helper, ScriptApi.runScript(helper.getLevel().getServer(), params), result -> {
			JsonObject r = result.getAsJsonObject();
			helper.assertTrue(r.get("blocks").getAsInt() == 2 && r.get("skippedExisting").getAsInt() == 1,
					"the stone in the way is kept: " + r);
		});
	}

	@GameTest(template = EMPTY, timeoutTicks = 1000)
	public void libraryErrorsNameTheFileAndClassIsExplained(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		JsonObject params = new JsonObject();
		params.addProperty("source", "class A {}");
		params.add("origin", Json.blockPos(helper.absolutePos(BlockPos.ZERO)));
		params.addProperty("dryRun", true);
		JsonArray libs = new JsonArray();
		JsonObject lib = new JsonObject();
		lib.addProperty("name", "lib/bad.js");
		lib.addProperty("source", "var ok = 1;\nundefinedFunction();");
		libs.add(lib);
		params.add("libraries", libs);
		var withLib = ScriptApi.runScript(helper.getLevel().getServer(), params);
		params.remove("libraries");
		var withClass = ScriptApi.runScript(helper.getLevel().getServer(), params);
		helper.succeedWhen(() -> {
			awaitWallClock(withLib);
			awaitWallClock(withClass);
			RpcException a = errorOf(withLib), c = errorOf(withClass);
			helper.assertTrue(a.getMessage().startsWith("lib/bad.js line 2"), "library file and line: " + a.getMessage());
			helper.assertTrue(c.getMessage().contains("class syntax is not supported"), c.getMessage());
		});
	}

	private static RpcException errorOf(CompletableFuture<JsonElement> future) {
		try {
			throw new GameTestAssertException("expected an error, got " + future.join());
		} catch (java.util.concurrent.CompletionException e) {
			return RpcDispatcher.toRpcException(e);
		}
	}

	@GameTest(template = EMPTY)
	public void blueprintRotationAndMirrorTransformPositionsAndFacing(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		// 2 x 1 x 2 source at relative (1,1,1): stairs (facing north) at x=0,z=0; stone at x=1,z=0; glass at x=0,z=1.
		BlockPos src = new BlockPos(1, 1, 1);
		helper.setBlock(src, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
		helper.setBlock(src.offset(1, 0, 0), Blocks.STONE);
		helper.setBlock(src.offset(0, 0, 1), Blocks.GLASS);
		JsonObject bp = LayerFormat.encode(helper.getLevel(), Region.of(helper.absolutePos(src), helper.absolutePos(src.offset(1, 0, 1))));

		// Pure transform check: mirror along x turns facing=east into west and swaps columns.
		Blueprint parsed = Blueprint.parse(bp, McMcpMod.services().guard());
		BlockList rotated = parsed.place(BlockPos.ZERO, Rotation.CLOCKWISE_90, Mirror.NONE, false);
		helper.assertTrue(rotated.get(new BlockPos(1, 0, 0).asLong()).getValue(StairBlock.FACING) == Direction.EAST,
				"rotated stairs move to the east edge and face east");
		BlockList mirrored = parsed.place(BlockPos.ZERO, Rotation.CLOCKWISE_90, Mirror.FRONT_BACK, false);
		BlockState m = mirrored.get(new BlockPos(1, 0, 1).asLong());
		helper.assertTrue(m.is(Blocks.OAK_STAIRS) && m.getValue(StairBlock.FACING) == Direction.EAST,
				"mirror x then rotate 90: stairs at (1,0,1) facing east, got " + m);

		// Through the API: place the export rotated 90 degrees at relative (4,1,4).
		BlockPos dst = new BlockPos(4, 1, 4);
		JsonObject params = new JsonObject();
		params.add("blueprint", bp);
		params.add("origin", Json.blockPos(helper.absolutePos(dst)));
		params.addProperty("rotation", 90);
		int[] job = new int[1];
		helper.startSequence()
				.thenExecute(() -> job[0] = BlueprintApi.placeBlueprint(helper.getLevel().getServer(), params).get("jobId").getAsInt())
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					// (x, z) -> (sz - 1 - z, x) with sz = 2
					helper.assertBlockProperty(dst.offset(1, 0, 0), StairBlock.FACING, Direction.EAST);
					helper.assertBlockPresent(Blocks.STONE, dst.offset(1, 0, 1));
					helper.assertBlockPresent(Blocks.GLASS, dst.offset(0, 0, 0));
					helper.assertBlockPresent(Blocks.AIR, dst.offset(0, 0, 1));
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY)
	public void previewLeavesTheWorldAloneUntilConfirmed(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		var server = helper.getLevel().getServer();
		var s = McMcpMod.services();
		BlockPos a = new BlockPos(1, 1, 1), b = new BlockPos(2, 1, 1);
		JsonObject params = blueprintParams(helper, "{\"palette\":{\"G\":\"minecraft:gold_block\"},\"layers\":[[\"GG\"]]}");
		params.add("origin", Json.blockPos(helper.absolutePos(a)));
		JsonObject preview = s.previews().create(s, BlueprintApi.plan(server, params));
		int id = preview.get("previewId").getAsInt();
		helper.assertBlockPresent(Blocks.AIR, a);
		helper.assertBlockPresent(Blocks.AIR, b);
		int[] job = {s.previews().confirm(s, id).get("jobId").getAsInt()};
		McMcpGameTests.expectCode(helper, RpcException.INVALID_PARAMS, () -> s.previews().confirm(s, id));
		helper.startSequence()
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.GOLD_BLOCK, a);
					helper.assertBlockPresent(Blocks.GOLD_BLOCK, b);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY)
	public void malformedBlueprintsAreRejected(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		var server = helper.getLevel().getServer();
		McMcpGameTests.expectCode(helper, RpcException.INVALID_PARAMS, () -> BlueprintApi.placeBlueprint(server,
				blueprintParams(helper, "{\"palette\":{\"S\":\"minecraft:stone\"},\"layers\":[[\"SX\"]]}")));
		McMcpGameTests.expectCode(helper, RpcException.INVALID_PARAMS, () -> BlueprintApi.placeBlueprint(server,
				blueprintParams(helper, "{\"palette\":{\"S\":\"minecraft:stone\"},\"layers\":[[\"SS\",\"S\"]]}")));
		McMcpGameTests.expectCode(helper, RpcException.UNKNOWN_BLOCK, () -> BlueprintApi.placeBlueprint(server,
				blueprintParams(helper, "{\"palette\":{\"T\":\"minecraft:tnt\"},\"layers\":[[\"T\"]]}")));
		helper.succeed();
	}

	private static JsonObject blueprintParams(GameTestHelper helper, String blueprint) {
		JsonObject params = new JsonObject();
		params.add("blueprint", com.google.gson.JsonParser.parseString(blueprint));
		params.add("origin", Json.blockPos(helper.absolutePos(BlockPos.ZERO)));
		return params;
	}

	private static CompletableFuture<JsonElement> script(GameTestHelper helper, BlockPos origin, String source, boolean dryRun) {
		JsonObject params = new JsonObject();
		params.addProperty("source", source);
		params.add("origin", Json.blockPos(origin));
		params.addProperty("dryRun", dryRun);
		return ScriptApi.runScript(helper.getLevel().getServer(), params);
	}

	/**
	 * The GameTest server ticks as fast as it can, so a tick-based timeout is far shorter than the
	 * script's wall-clock time. Slow ticks down while the script is still running.
	 */
	private static void awaitWallClock(CompletableFuture<?> future) {
		if (!future.isDone()) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			throw new GameTestAssertException("script still running");
		}
	}

	private static void expectResult(GameTestHelper helper, CompletableFuture<JsonElement> future, Consumer<JsonElement> check) {
		helper.succeedWhen(() -> {
			awaitWallClock(future);
			check.accept(future.join());
		});
	}

	private static void expectError(GameTestHelper helper, CompletableFuture<JsonElement> future, Consumer<RpcException> check) {
		helper.succeedWhen(() -> {
			awaitWallClock(future);
			JsonElement result;
			try {
				result = future.join();
			} catch (java.util.concurrent.CompletionException e) {
				check.accept(RpcDispatcher.toRpcException(e));
				return;
			}
			throw new GameTestAssertException("expected an error, got " + result);
		});
	}
}
