package dev.haochen.mcmcp.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.Services;
import dev.haochen.mcmcp.agent.AgentManager;
import dev.haochen.mcmcp.agent.AgentPlayer;
import dev.haochen.mcmcp.agent.BuildTask;
import dev.haochen.mcmcp.agent.CollectTask;
import dev.haochen.mcmcp.agent.Crafting;
import dev.haochen.mcmcp.agent.GatherTask;
import dev.haochen.mcmcp.agent.GotoTask;
import dev.haochen.mcmcp.agent.Inventories;
import dev.haochen.mcmcp.agent.MineTask;
import dev.haochen.mcmcp.agent.Pathfinder;
import dev.haochen.mcmcp.agent.PlaceTask;
import dev.haochen.mcmcp.net.RpcDispatcher;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Survival-mode agent (stage 5): a fake player that walks, mines, collects, places and crafts
 * like a player. Long actions are parallel jobs; the agent's own state never uses god-mode tools.
 * All handlers run on the server thread. Every method takes {@code agent} (name, default "Agent").
 */
public final class AgentApi {
	private AgentApi() {
	}

	public static void register(RpcDispatcher d) {
		d.onMainThread("agent.spawn", AgentApi::spawn);
		d.onMainThread("agent.despawn", (server, p) -> {
			AgentManager m = McMcpMod.services().agents();
			m.despawn(m.get(name(p)));
			JsonObject o = new JsonObject();
			o.addProperty("despawned", name(p));
			return o;
		});
		d.onMainThread("agent.getState", (server, p) -> agents().state(agent(p)));
		d.onMainThread("agent.goto", (server, p) -> {
			BlockPos target = Params.pos(p, "pos");
			double range = Params.number(p, "range", 1);
			boolean dig = Params.bool(p, "dig", false);
			AgentPlayer a = agent(p);
			return start(a, "agent.goto", "go to " + target.toShortString(),
					new GotoTask(a, Pathfinder.near(target, range), target.toShortString(), dig), 300);
		});
		d.onMainThread("agent.mineBlock", (server, p) -> {
			BlockPos pos = Params.pos(p, "pos");
			AgentPlayer a = agent(p);
			return start(a, "agent.mineBlock", "mine " + pos.toShortString(), new MineTask(a, pos, Params.bool(p, "dig", true)), 120);
		});
		d.onMainThread("agent.collectItems", (server, p) -> {
			double radius = Math.min(32, Params.number(p, "radius", 8));
			AgentPlayer a = agent(p);
			return start(a, "agent.collectItems", "collect items within " + radius, new CollectTask(a, radius), 180);
		});
		d.onMainThread("agent.placeBlock", (server, p) -> {
			BlockPos pos = Params.pos(p, "pos");
			BlockState state = McMcpMod.services().guard().block(Params.string(p, "block"));
			AgentPlayer a = agent(p);
			if (Inventories.count(a.getInventory(), state.getBlock().asItem()) == 0) {
				throw Params.invalid("the agent has no " + Inventories.id(state.getBlock().asItem()));
			}
			return start(a, "agent.placeBlock", "place " + Blocks.id(state) + " at " + pos.toShortString(),
					new PlaceTask(a, pos, state), 120);
		});
		d.onMainThread("agent.craft", (server, p) -> {
			AgentPlayer a = agent(p);
			requireIdle(a);
			return Crafting.craft(a, Inventories.item(Params.string(p, "item")), Math.max(1, Params.integer(p, "count", 1)));
		});
		d.onMainThread("agent.equip", AgentApi::equip);
		d.onMainThread("agent.gather", (server, p) -> {
			Item item = Inventories.item(Params.string(p, "item"));
			int count = Math.max(1, Params.integer(p, "count", 1));
			int radius = Math.min(48, Math.max(4, Params.integer(p, "radius", 24)));
			AgentPlayer a = agent(p);
			return start(a, "agent.gather", "gather " + count + " " + Inventories.id(item),
					new GatherTask(a, item, count, radius), 1200);
		});
		d.onMainThread("agent.buildBlueprint", (server, p) -> {
			AgentPlayer a = agent(p);
			Plan plan = BlueprintApi.plan(server, p);
			Map<Item, Integer> missing = BuildTask.missingMaterials(a, plan.blocks());
			if (!missing.isEmpty()) {
				JsonArray list = new JsonArray();
				missing.forEach((item, n) -> {
					JsonObject o = new JsonObject();
					o.addProperty("item", Inventories.id(item));
					o.addProperty("missing", n);
					list.add(o);
				});
				JsonObject data = new JsonObject();
				data.add("missing", list);
				throw new RpcException(RpcException.INVALID_PARAMS, "not enough materials in the agent's inventory", data);
			}
			return start(a, "agent.buildBlueprint", plan.description(), new BuildTask(a, plan.blocks()), 1800);
		});
	}

	private static JsonObject spawn(MinecraftServer server, JsonObject p) {
		String name = name(p);
		if (!name.matches("[A-Za-z0-9_]{3,16}")) {
			throw Params.invalid("agent names are 3-16 letters, digits or _");
		}
		ServerLevel level = WorldApi.level(server, p);
		Vec3 pos = null;
		if (Params.has(p, "pos")) {
			pos = Params.vec3(p, "pos");
		} else if (!Params.has(p, "dimension")) {
			// Next to the first real player, if any.
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (!(player instanceof AgentPlayer)) {
					level = player.serverLevel();
					pos = player.position().add(1.5, 0, 0);
					break;
				}
			}
		}
		GameType mode = GameType.byName(Params.string(p, "gameMode", "survival"), null);
		if (mode == null) {
			throw Params.invalid("gameMode must be survival, creative, adventure or spectator");
		}
		AgentPlayer agent = agents().spawn(server, name, level, pos, mode);
		return agents().state(agent);
	}

	private static JsonObject equip(MinecraftServer server, JsonObject p) {
		AgentPlayer a = agent(p);
		requireIdle(a);
		Item item = Inventories.item(Params.string(p, "item"));
		String slotName = Params.string(p, "slot", "mainhand");
		var inv = a.getInventory();
		if (slotName.equals("mainhand")) {
			if (!Inventories.holdItem(inv, item)) {
				throw Params.invalid("the agent has no " + Inventories.id(item));
			}
		} else {
			EquipmentSlot slot;
			try {
				slot = EquipmentSlot.byName(slotName);
			} catch (IllegalArgumentException e) {
				throw Params.invalid("slot must be mainhand, offhand, head, chest, legs or feet");
			}
			int from = -1;
			for (int i = 0; i < inv.items.size(); i++) {
				if (inv.items.get(i).is(item)) {
					from = i;
					break;
				}
			}
			if (from < 0) {
				throw Params.invalid("the agent has no " + Inventories.id(item));
			}
			ItemStack stack = inv.items.get(from);
			ItemStack old = a.getItemBySlot(slot);
			a.setItemSlot(slot, stack);
			inv.items.set(from, old);
		}
		return agents().state(a);
	}

	private static JsonObject start(AgentPlayer a, String kind, String description, dev.haochen.mcmcp.agent.AgentTask task, int maxSeconds) {
		Services s = McMcpMod.services();
		return s.agents().start(s.jobs(), a, kind, description, task, maxSeconds);
	}

	private static void requireIdle(AgentPlayer a) {
		JsonObject state = agents().state(a);
		if (state.has("busyWithJob")) {
			throw Params.invalid("agent is busy with job " + state.get("busyWithJob").getAsInt());
		}
	}

	private static AgentManager agents() {
		return McMcpMod.services().agents();
	}

	private static String name(JsonObject p) {
		return Params.string(p, "agent", AgentManager.DEFAULT_NAME);
	}

	private static AgentPlayer agent(JsonObject p) {
		return agents().get(name(p));
	}
}
