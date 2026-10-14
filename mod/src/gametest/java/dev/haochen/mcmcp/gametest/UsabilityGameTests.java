package dev.haochen.mcmcp.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpConfig;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.api.AgentApiAccess;
import dev.haochen.mcmcp.api.ArchApi;
import dev.haochen.mcmcp.api.BuildApi;
import dev.haochen.mcmcp.api.Json;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.BlockInfo;
import dev.haochen.mcmcp.world.Blocks;
import dev.haochen.mcmcp.world.Guard;
import dev.haochen.mcmcp.world.Region;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.HashSet;
import java.util.Set;

/** Fixes from the first large build (a castle) made with the tools: messages, limits and helpers. */
public class UsabilityGameTests implements FabricGameTest {
	private static final String EMPTY = FabricGameTest.EMPTY_STRUCTURE;

	/** A tower that does not clear the main roof gets a warning with the height it needs. */
	@GameTest(template = EMPTY, batch = "usability_tower")
	public void towerHiddenByTheRoofIsWarned(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		var server = helper.getLevel().getServer();
		JsonObject low = ArchGameTests.examplePlan();
		low.add("components", tower(8));
		JsonObject v = ArchApi.compile(server, ArchGameTests.params(helper, low, ArchGameTests.FAR.offset(0, 0, 360))).validation();
		helper.assertTrue(codes(v.getAsJsonArray("warnings")).contains("TOWER_HIDDEN"), "low tower warned: " + v);

		JsonObject high = ArchGameTests.examplePlan();
		high.add("components", tower(22));
		v = ArchApi.compile(server, ArchGameTests.params(helper, high, ArchGameTests.FAR.offset(0, 0, 360))).validation();
		helper.assertTrue(!codes(v.getAsJsonArray("warnings")).contains("TOWER_HIDDEN"), "tall tower fine: " + v);
		helper.succeed();
	}

	/** Outside the allowed regions: the error names the nearest place a box of that size fits. */
	@GameTest(template = EMPTY)
	public void outsideAllowedRegionSuggestsTheNearestSpot(GameTestHelper helper) {
		McMcpConfig config = new McMcpConfig();
		Guard guard = new Guard(config);
		Region wanted = Region.of(new BlockPos(-360, 64, 10), new BlockPos(-340, 70, 30));
		try {
			guard.checkRegion(helper.getLevel(), wanted);
			throw new GameTestAssertException("x=-360 is outside the default +-256");
		} catch (RpcException e) {
			JsonObject nearest = e.data().getAsJsonObject().getAsJsonObject("nearestAllowed");
			helper.assertTrue(nearest.getAsJsonArray("min").get(0).getAsInt() == -256
					&& nearest.getAsJsonArray("max").get(0).getAsInt() == -236
					&& nearest.getAsJsonArray("min").get(2).getAsInt() == 10, "moved just inside: " + nearest);
			helper.assertTrue(e.getMessage().contains("nearest allowed spot"), e.getMessage());
		}
		helper.succeed();
	}

	/** fill_region above maxVolumePerOp: refused with a hint, or split into several jobs on request. */
	@GameTest(template = EMPTY, timeoutTicks = 200)
	public void largeFillsCanBeSplit(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		McMcpConfig config = McMcpMod.services().config();
		int oldMax = config.maxVolumePerOp;
		config.maxVolumePerOp = 40;
		JsonObject p = new JsonObject();
		p.add("region", Region.of(helper.absolutePos(new BlockPos(1, 1, 1)), helper.absolutePos(new BlockPos(6, 2, 6))).toJson());
		p.addProperty("block", "minecraft:stone");
		JsonObject result;
		try {
			try {
				BuildApi.fillRegion(helper.getLevel().getServer(), p);
				throw new GameTestAssertException("72 blocks > 40 must be refused without split");
			} catch (RpcException e) {
				helper.assertTrue(e.code() == RpcException.VOLUME_TOO_LARGE && e.getMessage().contains("split: true"), e.getMessage());
			}
			p.addProperty("split", true);
			result = BuildApi.fillRegion(helper.getLevel().getServer(), p);
		} finally {
			config.maxVolumePerOp = oldMax;
		}
		JsonArray ids = result.getAsJsonArray("jobIds");
		helper.assertTrue(ids.size() == 2, "two 6x1x6 layers of 36: " + result);
		int last = result.get("jobId").getAsInt();
		helper.succeedWhen(() -> {
			McMcpGameTests.assertDone(helper, last);
			for (JsonElement id : ids) {
				McMcpGameTests.assertDone(helper, id.getAsInt());
			}
			helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.STONE, new BlockPos(1, 1, 1));
			helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.STONE, new BlockPos(6, 2, 6));
		});
	}

	/** Large level areas: candidates are reported with their ground level, inside the allowed region. */
	@GameTest(template = EMPTY, batch = "usability_flat", timeoutTicks = 200)
	public void flatAreasAreFound(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		// The test world is empty this far out: lay a 30 x 30 grass field with a 4-high hill in one corner.
		BlockPos field = ArchGameTests.FAR.offset(0, 0, 480);
		helper.getLevel().getChunk(helper.absolutePos(field));
		for (int x = 0; x < 30; x++) {
			for (int z = 0; z < 30; z++) {
				helper.setBlock(field.offset(x, 0, z), net.minecraft.world.level.block.Blocks.GRASS_BLOCK);
				if (x < 8 && z < 8) {
					for (int y = 1; y <= 4; y++) {
						helper.setBlock(field.offset(x, y, z), net.minecraft.world.level.block.Blocks.STONE);
					}
				}
			}
		}
		BlockPos center = helper.absolutePos(field.offset(15, 0, 15));
		JsonObject p = new JsonObject();
		p.add("center", Json.blockPos(center));
		p.addProperty("radius", 24);
		JsonArray size = new JsonArray();
		size.add(12);
		size.add(20);
		p.add("size", size);
		p.addProperty("loadChunks", true);
		JsonObject r = AgentApiAccess.call("site.findFlat", p);
		JsonArray c = r.getAsJsonArray("candidates");
		helper.assertTrue(!c.isEmpty(), "the flat test world has level ground: " + r);
		JsonObject best = c.get(0).getAsJsonObject();
		helper.assertTrue(best.get("groundMax").getAsInt() == best.get("groundMin").getAsInt(), "level, away from the hill: " + best);
		helper.assertTrue(best.get("groundY").getAsInt() == helper.absolutePos(field).getY(), "ground level: " + best);
		helper.assertTrue(r.has("allowedRegions"), "reports the allowed regions");
		helper.succeed();
	}

	/** block_info explains orientations from the real block code. */
	@GameTest(template = EMPTY)
	public void blockInfoExplainsFacing(GameTestHelper helper) {
		JsonObject door = BlockInfo.describe(Blocks.parse("minecraft:oak_door[facing=north]"));
		helper.assertTrue(door.getAsJsonObject("properties").has("hinge"), "door properties: " + door);
		helper.assertTrue(door.getAsJsonArray("notes").get(0).getAsString().contains("OPPOSITE"), "door note: " + door);
		JsonObject stairs = BlockInfo.describe(Blocks.parse("minecraft:oak_stairs"));
		helper.assertTrue(stairs.getAsJsonArray("notes").get(0).getAsString().startsWith("facing = the side of the tall back part"), "stairs: " + stairs);
		helper.succeed();
	}

	private static JsonArray tower(int height) {
		JsonObject t = new JsonObject();
		t.addProperty("id", "bell-tower");
		t.addProperty("type", "tower");
		JsonArray at = new JsonArray();
		at.add(5);
		at.add(4);
		t.add("at", at);
		t.addProperty("radius", 1);
		t.addProperty("height", height);
		t.addProperty("top", "flat");
		JsonArray a = new JsonArray();
		a.add(t);
		return a;
	}

	private static Set<String> codes(JsonArray issues) {
		Set<String> out = new HashSet<>();
		for (JsonElement e : issues) {
			out.add(e.getAsJsonObject().get("code").getAsString());
		}
		return out;
	}
}
