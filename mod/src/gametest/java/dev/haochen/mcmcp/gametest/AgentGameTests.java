package dev.haochen.mcmcp.gametest;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.agent.AgentPlayer;
import dev.haochen.mcmcp.agent.Inventories;
import dev.haochen.mcmcp.api.AgentApiAccess;
import dev.haochen.mcmcp.api.Json;
import dev.haochen.mcmcp.jobs.Job;
import dev.haochen.mcmcp.net.RpcException;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Supplier;

/**
 * Stage 5 acceptance in a controlled setting: an agent with an empty inventory chops a tree,
 * crafts a crafting table and a wooden pickaxe, and mines 10 cobblestone, using only agent
 * actions (no god-mode building). Then: failures are reported clearly.
 */
public class AgentGameTests implements FabricGameTest {
	private static final String EMPTY = FabricGameTest.EMPTY_STRUCTURE;

	@GameTest(template = EMPTY, batch = "agent_survival", timeoutTicks = 12000)
	public void chopCraftAndMineCobblestone(GameTestHelper helper) {
		// Scenery (test setup, not agent actions): an oak trunk of 5 logs and a 4x3 stone patch in the ground.
		for (int y = 0; y < 5; y++) {
			helper.setBlock(new BlockPos(2, y, 2), Blocks.OAK_LOG);
		}
		for (int x = 4; x <= 7; x++) {
			for (int z = 4; z <= 6; z++) {
				helper.setBlock(new BlockPos(x, -1, z), Blocks.STONE);
			}
		}
		String name = "TestAgent";
		JsonObject spawn = params(name);
		spawn.add("pos", Json.blockPos(helper.absolutePos(new BlockPos(5, 0, 1))));
		AgentApiAccess.call("agent.spawn", spawn);
		AgentPlayer agent = McMcpMod.services().agents().get(name);
		agent.getInventory().clearContent(); // player data persists between runs
		int[] job = new int[1];

		GameTestSequence seq = helper.startSequence();
		seq.thenExecute(() -> job[0] = start("agent.gather", with(params(name), "item", "minecraft:oak_log", "count", 3)))
				.thenWaitUntil(() -> done(helper, job[0]))
				.thenExecute(() -> {
					helper.assertTrue(Inventories.count(agent.getInventory(), Items.OAK_LOG) >= 3, "has 3 logs");
					// 3 logs -> 12 planks; 4 for the table, 2 for sticks, 3 for the pickaxe.
					AgentApiAccess.call("agent.craft", with(params(name), "item", "minecraft:oak_planks", "count", 12));
					AgentApiAccess.call("agent.craft", with(params(name), "item", "minecraft:crafting_table", "count", 1));
					AgentApiAccess.call("agent.craft", with(params(name), "item", "minecraft:stick", "count", 4));
					// A pickaxe needs a 3x3 grid: without a table nearby this must fail with a clear reason.
					expect(helper, "crafting table", () -> AgentApiAccess.call("agent.craft",
							with(params(name), "item", "minecraft:wooden_pickaxe", "count", 1)));
					JsonObject place = with(params(name), "block", "minecraft:crafting_table", null, null);
					place.add("pos", Json.blockPos(agent.blockPosition().east()));
					job[0] = start("agent.placeBlock", place);
				})
				.thenWaitUntil(() -> done(helper, job[0]))
				.thenExecute(() -> {
					AgentApiAccess.call("agent.craft", with(params(name), "item", "minecraft:wooden_pickaxe", "count", 1));
					JsonObject crafted = AgentApiAccess.call("agent.getState", params(name));
					helper.assertTrue(Inventories.count(agent.getInventory(), Items.WOODEN_PICKAXE) == 1, "crafted a wooden pickaxe: " + crafted);
					job[0] = start("agent.gather", with(params(name), "item", "minecraft:cobblestone", "count", 10));
				})
				.thenWaitUntil(() -> done(helper, job[0]))
				.thenExecute(() -> {
					helper.assertTrue(Inventories.count(agent.getInventory(), Items.COBBLESTONE) >= 10,
							"10 cobblestone, have " + Inventories.count(agent.getInventory(), Items.COBBLESTONE));
					AgentApiAccess.call("agent.despawn", params(name));
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY, batch = "agent_failures", timeoutTicks = 2000)
	public void failuresAreExplained(GameTestHelper helper) {
		String name = "FailAgent";
		JsonObject spawn = params(name);
		spawn.add("pos", Json.blockPos(helper.absolutePos(new BlockPos(1, 0, 1))));
		AgentApiAccess.call("agent.spawn", spawn);
		AgentPlayer agent = McMcpMod.services().agents().get(name);
		agent.getInventory().clearContent();
		// Walled in: a 1x1 pit with 3-high walls cannot be left by walking.
		for (int y = 0; y <= 2; y++) {
			for (BlockPos p : new BlockPos[]{new BlockPos(0, y, 1), new BlockPos(2, y, 1), new BlockPos(1, y, 0), new BlockPos(1, y, 2)}) {
				helper.setBlock(p, Blocks.STONE);
			}
		}
		int[] job = new int[1];
		helper.startSequence()
				.thenExecute(() -> {
					JsonObject go = params(name);
					go.add("pos", Json.blockPos(helper.absolutePos(new BlockPos(6, 0, 6))));
					job[0] = start("agent.goto", go);
				})
				.thenWaitUntil(() -> {
					Job j = McMcpMod.services().jobs().get(job[0]);
					if (!j.state().finished()) {
						throw new GameTestAssertException("still running");
					}
					helper.assertTrue(j.state() == Job.State.FAILED && j.toJson().get("error").getAsString().contains("no path"),
							"goto fails with a reason: " + j.toJson());
				})
				.thenExecute(() -> {
					expect(helper, "no minecraft:diamond_block", () -> {
						JsonObject place = with(params(name), "block", "minecraft:diamond_block", null, null);
						place.add("pos", Json.blockPos(agent.blockPosition().above(2)));
						return AgentApiAccess.call("agent.placeBlock", place);
					});
					expect(helper, "missing ingredients", () -> AgentApiAccess.call("agent.craft",
							with(params(name), "item", "minecraft:stick", "count", 1)));
					// Stone without a pickaxe drops nothing, so it is not a cobblestone source.
					helper.setBlock(new BlockPos(1, -1, 1), Blocks.STONE);
					job[0] = start("agent.gather", with(params(name), "item", "minecraft:cobblestone", "count", 1));
				})
				.thenWaitUntil(() -> {
					Job j = McMcpMod.services().jobs().get(job[0]);
					if (!j.state().finished()) {
						throw new GameTestAssertException("still running");
					}
					helper.assertTrue(j.state() == Job.State.FAILED && j.toJson().get("error").getAsString().contains("better tool"),
							"gather without a pickaxe fails fast: " + j.toJson());
				})
				.thenExecute(() -> {
					// Death ends the running job with a clear reason.
					JsonObject go = params(name);
					go.add("pos", Json.blockPos(helper.absolutePos(new BlockPos(6, 0, 6))));
					helper.setBlock(new BlockPos(1, 0, 2), Blocks.AIR); // open the pit so the walk starts
					job[0] = start("agent.goto", go);
					agent.kill();
				})
				.thenWaitUntil(() -> {
					Job j = McMcpMod.services().jobs().get(job[0]);
					if (!j.state().finished()) {
						throw new GameTestAssertException("still running");
					}
					helper.assertTrue(j.state() == Job.State.FAILED && j.toJson().get("error").getAsString().contains("agent died"),
							"death fails the job: " + j.toJson());
					AgentApiAccess.call("agent.despawn", params(name));
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY, batch = "agent_dig", timeoutTicks = 4000)
	public void digsOutOfAPitWhenAllowed(GameTestHelper helper) {
		String name = "DigAgent";
		// A 1x1 pit with 3-high stone walls; the agent has a wooden pickaxe.
		for (int y = 0; y <= 2; y++) {
			for (BlockPos p : new BlockPos[]{new BlockPos(0, y, 1), new BlockPos(2, y, 1), new BlockPos(1, y, 0), new BlockPos(1, y, 2)}) {
				helper.setBlock(p, Blocks.STONE);
			}
		}
		JsonObject spawn = params(name);
		spawn.add("pos", Json.blockPos(helper.absolutePos(new BlockPos(1, 0, 1))));
		AgentApiAccess.call("agent.spawn", spawn);
		AgentPlayer agent = McMcpMod.services().agents().get(name);
		agent.getInventory().clearContent();
		agent.getInventory().add(new net.minecraft.world.item.ItemStack(Items.WOODEN_PICKAXE));
		BlockPos target = new BlockPos(6, 0, 6);
		int[] job = new int[1];
		helper.startSequence()
				.thenExecute(() -> {
					JsonObject go = params(name);
					go.add("pos", Json.blockPos(helper.absolutePos(target)));
					go.addProperty("dig", true);
					job[0] = start("agent.goto", go);
				})
				.thenWaitUntil(() -> done(helper, job[0]))
				.thenExecute(() -> {
					helper.assertTrue(agent.blockPosition().distSqr(helper.absolutePos(target)) <= 2, "reached " + agent.blockPosition());
					helper.assertTrue(McMcpMod.services().jobs().get(job[0]).toJson().getAsJsonObject("result").get("blocksDug").getAsInt() >= 1,
							"dug through the wall");
					AgentApiAccess.call("agent.despawn", params(name));
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY, batch = "agent_build", timeoutTicks = 4000)
	public void buildsABlueprintByHandAndListsMissingMaterials(GameTestHelper helper) {
		String name = "BuildAgent";
		JsonObject spawn = params(name);
		spawn.add("pos", Json.blockPos(helper.absolutePos(new BlockPos(1, 0, 1))));
		AgentApiAccess.call("agent.spawn", spawn);
		AgentPlayer agent = McMcpMod.services().agents().get(name);
		agent.getInventory().clearContent();
		BlockPos at = new BlockPos(4, 0, 4);
		JsonObject build = params(name);
		build.add("blueprint", com.google.gson.JsonParser.parseString(
				"{\"palette\":{\"P\":\"minecraft:oak_planks\"},\"layers\":[[\"PP\",\"PP\"]]}"));
		build.add("origin", Json.blockPos(helper.absolutePos(at)));
		try {
			AgentApiAccess.call("agent.buildBlueprint", build);
			throw new GameTestAssertException("expected missing materials");
		} catch (RpcException e) {
			String missing = e.data().toString();
			helper.assertTrue(missing.contains("minecraft:oak_planks") && missing.contains("\"missing\":4"), "missing list: " + missing);
		}
		agent.getInventory().add(new net.minecraft.world.item.ItemStack(Items.OAK_PLANKS, 4));
		int[] job = {start("agent.buildBlueprint", build)};
		helper.startSequence()
				.thenWaitUntil(() -> done(helper, job[0]))
				.thenExecute(() -> {
					for (BlockPos p : new BlockPos[]{at, at.east(), at.south(), at.east().south()}) {
						helper.assertBlockPresent(Blocks.OAK_PLANKS, p);
					}
					helper.assertTrue(Inventories.count(agent.getInventory(), Items.OAK_PLANKS) == 0, "planks used up");
					AgentApiAccess.call("agent.despawn", params(name));
				})
				.thenSucceed();
	}

	private static JsonObject params(String agent) {
		JsonObject p = new JsonObject();
		p.addProperty("agent", agent);
		return p;
	}

	private static JsonObject with(JsonObject p, String k1, String v1, String k2, Object v2) {
		p.addProperty(k1, v1);
		if (k2 != null) {
			p.addProperty(k2, (Number) v2);
		}
		return p;
	}

	private static int start(String method, JsonObject params) {
		return AgentApiAccess.call(method, params).get("jobId").getAsInt();
	}

	private static void done(GameTestHelper helper, int id) {
		Job j = McMcpMod.services().jobs().get(id);
		if (!j.state().finished()) {
			throw new GameTestAssertException("job " + id + " still " + j.state().id() + ": " + j.toJson());
		}
		helper.assertTrue(j.state() == Job.State.DONE, "job finished: " + j.toJson());
	}

	private static void expect(GameTestHelper helper, String messagePart, Supplier<?> call) {
		try {
			call.get();
		} catch (RpcException e) {
			helper.assertTrue(e.getMessage().contains(messagePart), "error mentions '" + messagePart + "': " + e.getMessage());
			return;
		}
		throw new GameTestAssertException("expected an error mentioning " + messagePart);
	}
}
