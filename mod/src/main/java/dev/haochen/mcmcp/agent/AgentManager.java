package dev.haochen.mcmcp.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.api.Json;
import dev.haochen.mcmcp.api.Params;
import dev.haochen.mcmcp.jobs.JobManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/** Agents by name and the one job each is running. Server thread. */
public final class AgentManager {
	public static final String DEFAULT_NAME = "Agent";

	private final Map<String, AgentPlayer> agents = new HashMap<>();
	private final Map<AgentPlayer, AgentJob> busy = new HashMap<>();

	public AgentPlayer spawn(MinecraftServer server, String name, ServerLevel level, @Nullable Vec3 pos, GameType mode) {
		AgentPlayer existing = agents.get(name);
		if (existing != null && !existing.isRemoved() && !existing.hasDisconnected()) {
			if (!existing.isDeadOrDying()) {
				if (pos != null) {
					existing.teleportTo(level, pos.x, pos.y, pos.z, existing.getYRot(), existing.getXRot());
				}
				existing.setGameMode(mode);
				return existing;
			}
			despawn(existing);
		}
		if (server.getPlayerList().getPlayerByName(name) != null) {
			throw Params.invalid("a real player named " + name + " is online; pick another agent name");
		}
		AgentPlayer agent = AgentPlayer.spawn(server, name, level, pos, mode);
		agents.put(name, agent);
		return agent;
	}

	public void despawn(AgentPlayer agent) {
		// A running job notices on its next tick and fails with "agent left the game".
		busy.remove(agent);
		agents.values().remove(agent);
		agent.despawn();
	}

	public AgentPlayer get(String name) {
		AgentPlayer agent = agents.get(name);
		if (agent == null || agent.isRemoved() || agent.hasDisconnected()) {
			agents.remove(name);
			throw Params.invalid("no agent named " + name + "; call agent_spawn first");
		}
		return agent;
	}

	/** Starts a task unless the agent is already doing something. */
	public JsonObject start(JobManager jobs, AgentPlayer agent, String kind, String description, AgentTask task, int maxSeconds) {
		AgentJob running = busy.get(agent);
		if (running != null) {
			throw Params.invalid("agent is busy with job " + running.id() + " (" + running.toJson().get("description").getAsString()
					+ "); wait for it or cancel_job");
		}
		if (agent.isDeadOrDying()) {
			throw Params.invalid("agent is dead; agent_spawn brings it back");
		}
		AgentJob job = new AgentJob(this, agent, kind, description, task, maxSeconds * 20);
		busy.put(agent, job);
		jobs.submit(job);
		return job.toJson();
	}

	void finished(AgentPlayer agent, AgentJob job) {
		busy.remove(agent, job);
	}

	public JsonObject state(AgentPlayer a) {
		JsonObject o = new JsonObject();
		o.addProperty("agent", a.getGameProfile().getName());
		o.addProperty("dimension", a.level().dimension().location().toString());
		JsonArray pos = new JsonArray();
		pos.add(Math.round(a.getX() * 100) / 100.0);
		pos.add(Math.round(a.getY() * 100) / 100.0);
		pos.add(Math.round(a.getZ() * 100) / 100.0);
		o.add("pos", pos);
		o.add("blockPos", Json.blockPos(a.blockPosition()));
		o.addProperty("dead", a.isDeadOrDying());
		o.addProperty("health", a.getHealth());
		o.addProperty("food", a.getFoodData().getFoodLevel());
		o.addProperty("gameMode", a.gameMode.getGameModeForPlayer().getName());
		o.addProperty("mainHand", Inventories.id(a.getMainHandItem().getItem()));
		o.add("inventory", Inventories.summary(a.getInventory()));
		JsonObject armor = new JsonObject();
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
			ItemStack s = a.getItemBySlot(slot);
			if (!s.isEmpty()) {
				armor.addProperty(slot.getName(), BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
			}
		}
		o.add("equipment", armor);
		AgentJob job = busy.get(a);
		if (job != null) {
			o.addProperty("busyWithJob", job.id());
		}
		return o;
	}
}
