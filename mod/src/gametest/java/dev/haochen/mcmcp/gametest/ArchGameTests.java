package dev.haochen.mcmcp.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.api.ArchApi;
import dev.haochen.mcmcp.api.BlueprintApi;
import dev.haochen.mcmcp.api.Json;
import dev.haochen.mcmcp.arch.SiteAnalyzer;
import dev.haochen.mcmcp.net.RpcException;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Phase 1 (v2): terrain analysis, building plans, validation. Each test has its own batch because
 * a house with its terrace is larger than the 8x8 test plot.
 */
public class ArchGameTests implements FabricGameTest {
	private static final String EMPTY = FabricGameTest.EMPTY_STRUCTURE;

	/** The shipped example plan, relative to the GameTest run directory (mod/build/gametest). */
	static JsonObject examplePlan() {
		try {
			return JsonParser.parseString(Files.readString(Path.of("../../../examples/buildings/two-story-house.json"))).getAsJsonObject();
		} catch (Exception e) {
			throw new GameTestAssertException("cannot read examples/buildings/two-story-house.json: " + e);
		}
	}

	static JsonObject params(GameTestHelper helper, JsonObject building, BlockPos localOrigin) {
		building.add("origin", Json.blockPos(helper.absolutePos(localOrigin)));
		JsonObject p = new JsonObject();
		p.add("building", building);
		p.addProperty("loadChunks", true);
		return p;
	}

	/**
	 * Houses are bigger than the 8x8 test plot and GameTest only resets the plot, so these tests
	 * build far away from the test grid (no barriers there, and nothing left behind near other tests).
	 */
	static final BlockPos FAR = new BlockPos(400, 0, 0);

	/** Raises and lowers the flat test ground so the house has to cope with uneven terrain. */
	static void unevenGround(GameTestHelper helper, int x0, int z0, int w, int d) {
		for (int x = x0 - 3; x < x0 + w + 3; x++) {
			for (int z = z0 - 3; z < z0 + d + 3; z++) {
				int h = (x - x0 < w / 2 ? 2 : 0) + (z - z0 > d / 2 ? 1 : 0); // a 2-high rise on the left, 1 at the front
				for (int y = 0; y < h; y++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.DIRT);
				}
				if (h > 0) {
					helper.setBlock(new BlockPos(x, h - 1, z), Blocks.GRASS_BLOCK);
				}
			}
		}
		helper.setBlock(new BlockPos(x0 + w - 2, -1, z0 + 1), Blocks.AIR); // a hole
		helper.setBlock(new BlockPos(x0 + w - 2, -2, z0 + 1), Blocks.AIR);
	}

	@GameTest(template = EMPTY, batch = "arch_site")
	public void siteAnalysisAvoidsWaterAndRanksFlatGround(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		// Flat ground with a pond on the west half (far from the test grid, see FAR).
		BlockPos site = new BlockPos(-400, 0, 0);
		for (int x = -12; x < 0; x++) {
			for (int z = -12; z <= 12; z++) {
				helper.setBlock(site.offset(x, -1, z), Blocks.WATER);
			}
		}
		BlockPos center = helper.absolutePos(site);
		JsonObject out = SiteAnalyzer.analyze(helper.getLevel(), McMcpMod.services().guard(),
				new SiteAnalyzer.Request(center, 12, 7, 5, 2, Direction.SOUTH, 3));
		JsonArray candidates = out.getAsJsonArray("candidates");
		helper.assertTrue(!candidates.isEmpty(), "candidates found: " + out);
		JsonObject best = candidates.get(0).getAsJsonObject();
		JsonObject stats = best.getAsJsonObject("stats");
		helper.assertTrue(!stats.has("waterColumns"), "best site is dry: " + best);
		helper.assertTrue(stats.get("cut").getAsInt() == 0 && stats.get("fill").getAsInt() == 0, "flat site needs no earthworks: " + best);
		helper.assertTrue(best.getAsJsonArray("origin").get(0).getAsInt() >= center.getX(), "east of the pond: " + best);
		helper.assertTrue(out.getAsJsonObject("map").getAsJsonArray("rows").get(0).getAsString().contains("~"), "map shows water");
		helper.assertTrue(best.get("facing").getAsString().equals("south"), "preferred facing kept on flat ground: " + best);
		helper.succeed();
	}

	@GameTest(template = EMPTY, batch = "arch_house", timeoutTicks = 600)
	public void twoStoryHouseOnUnevenGround(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		unevenGround(helper, FAR.getX(), FAR.getZ(), 11, 9);
		var server = helper.getLevel().getServer();
		JsonObject params = params(helper, examplePlan(), FAR);

		JsonObject result = ArchApi.compile(server, params).stats();
		JsonObject validation = result.getAsJsonObject("validation");
		helper.assertTrue(validation.get("ok").getAsBoolean(), "example house validates: " + validation);
		JsonObject reach = validation.getAsJsonObject("reachable");
		for (String floor : new String[]{"ground", "upper"}) {
			helper.assertTrue(reach.getAsJsonObject("floors").get(floor).getAsBoolean(), floor + " reachable");
		}
		for (String room : new String[]{"hall", "kitchen", "bedroom", "study"}) {
			helper.assertTrue(reach.getAsJsonObject("rooms").get(room).getAsBoolean(), room + " reachable");
		}

		int job = BlueprintApi.placeBlueprint(server, params).get("jobId").getAsInt();
		helper.startSequence()
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job))
				.thenExecute(() -> {
					// Front door (local x=5, z=8, y=1..2), facing in (north for a south-facing house).
					BlockState door = helper.getBlockState(FAR.offset(5, 1, 8));
					helper.assertTrue(door.getBlock() instanceof DoorBlock && door.getValue(DoorBlock.FACING) == Direction.NORTH, "front door: " + door);
					helper.assertTrue(helper.getBlockState(FAR.offset(5, 2, 8)).getBlock() instanceof DoorBlock, "door upper half");
					helper.assertBlockPresent(Blocks.GLASS_PANE, FAR.offset(2, 2, 8));
					BlockState step = helper.getBlockState(FAR.offset(1, 1, 6));
					helper.assertTrue(step.getBlock() instanceof StairBlock && step.getValue(StairBlock.FACING) == Direction.NORTH, "first step: " + step);
					helper.assertBlockPresent(Blocks.SPRUCE_PLANKS, FAR.offset(3, 4, 4)); // upper floor
					helper.assertTrue(helper.getBlockState(FAR.offset(5, 9, -1)).getBlock() instanceof StairBlock, "roof overhang");
					helper.assertBlockPresent(Blocks.AIR, FAR.offset(4, 1, 4)); // interior cleared through the mound
					helper.assertBlockPresent(Blocks.COBBLESTONE, FAR.offset(9, -2, 1)); // foundation fills the hole
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY, batch = "arch_defects", timeoutTicks = 200)
	public void validationReportsEachDefect(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		var server = helper.getLevel().getServer();
		BlockPos at = FAR;

		expect(helper, at, "MISSING_STAIRS", b -> b.remove("stairs"));
		expect(helper, at, "MISSING_ENTRANCE", b -> removeOpening(b, "front-door"));
		expect(helper, at, "UNREACHABLE_ROOM", b -> removeOpening(b, "study-door"));
		expect(helper, at, "ROOF_GAP", b -> patch(b, "roof", 4, 12, 2, "minecraft:air", 4, 12, 6, "minecraft:air"));
		expect(helper, at, "ROOF_ORIENTATION", b -> patch(b, "roof", 3, 10, 0, "minecraft:dark_oak_stairs[facing=north]"));
		expect(helper, at, "INCOMPLETE_WALL", b -> patch(b, "wall-back", 9, 2, 0, "minecraft:air"));
		expect(helper, at, "FLOATING", b -> patch(b, "chimney", 5, 20, 4, "minecraft:bricks"));
		expect(helper, at, "OPENING_BOUNDS", b -> b.getAsJsonArray("openings").get(1).getAsJsonObject().addProperty("at", 0));
		expect(helper, at, "STAIRS_BOUNDS", b -> b.getAsJsonArray("stairs").get(0).getAsJsonObject().addProperty("direction", "front"));
		expect(helper, at, "DOOR_ORIENTATION", b -> patch(b, "front-door", 5, 1, 8, "minecraft:spruce_door[facing=east,half=lower]"));

		// Existing structure in the way.
		helper.setBlock(FAR.offset(4, 1, 4), Blocks.BRICKS);
		expect(helper, at, "COLLISION", b -> {
		});
		helper.setBlock(FAR.offset(4, 1, 4), Blocks.AIR);

		// Outside the allowed region: placement is refused, validation says why.
		JsonObject far = params(helper, examplePlan(), at);
		far.getAsJsonObject("building").add("origin", Json.blockPos(new BlockPos(29_500_000, 70, 0)));
		far.addProperty("loadChunks", true);
		JsonObject v = ArchApi.compile(server, far).validation();
		helper.assertTrue(codes(v).contains("REGION"), "region error: " + v);
		try {
			BlueprintApi.placeBlueprint(server, far);
			throw new GameTestAssertException("placing an invalid building must fail");
		} catch (RpcException e) {
			helper.assertTrue(e.code() == RpcException.BUILDING_INVALID, "code 1007, got " + e.code());
		}
		helper.succeed();
	}

	private static void expect(GameTestHelper helper, BlockPos at, String code, Consumer<JsonObject> mutate) {
		JsonObject building = examplePlan();
		mutate.accept(building);
		JsonObject v = ArchApi.compile(helper.getLevel().getServer(), params(helper, building, at)).validation();
		helper.assertTrue(codes(v).contains(code), "expected " + code + ", got " + v);
	}

	private static Set<String> codes(JsonObject validation) {
		Set<String> out = new HashSet<>();
		for (JsonElement e : validation.getAsJsonArray("errors")) {
			out.add(e.getAsJsonObject().get("code").getAsString());
		}
		return out;
	}

	private static void removeOpening(JsonObject b, String id) {
		JsonArray openings = b.getAsJsonArray("openings");
		for (int i = 0; i < openings.size(); i++) {
			if (openings.get(i).getAsJsonObject().get("id").getAsString().equals(id)) {
				openings.remove(i);
				return;
			}
		}
		throw new GameTestAssertException("no opening " + id);
	}

	/** Adds a patch: component, then (x, y, z, block) quadruples in local coordinates. */
	static void patch(JsonObject b, String component, Object... xyzb) {
		JsonArray blocks = new JsonArray();
		for (int i = 0; i < xyzb.length; i += 4) {
			JsonArray a = new JsonArray();
			a.add((Integer) xyzb[i]);
			a.add((Integer) xyzb[i + 1]);
			a.add((Integer) xyzb[i + 2]);
			a.add((String) xyzb[i + 3]);
			blocks.add(a);
		}
		JsonObject p = new JsonObject();
		p.addProperty("id", "test-patch-" + component);
		p.addProperty("component", component);
		p.add("blocks", blocks);
		JsonArray patches = b.has("patches") ? b.getAsJsonArray("patches") : new JsonArray();
		patches.add(p);
		b.add("patches", patches);
	}
}
