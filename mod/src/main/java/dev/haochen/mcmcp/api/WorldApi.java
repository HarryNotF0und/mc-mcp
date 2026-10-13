package dev.haochen.mcmcp.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.net.RpcDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public final class WorldApi {
	private WorldApi() {
	}

	public static void register(RpcDispatcher dispatcher) {
		dispatcher.onMainThread("world.getInfo", (server, params) -> getInfo(server));
	}

	private static JsonObject getInfo(MinecraftServer server) {
		JsonObject result = new JsonObject();
		result.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().getName());
		result.addProperty("modVersion", McMcpMod.version());
		result.addProperty("dedicated", server.isDedicatedServer());

		JsonArray dimensions = new JsonArray();
		for (ResourceKey<Level> key : server.levelKeys()) {
			dimensions.add(key.location().toString());
		}
		result.add("dimensions", dimensions);

		JsonArray players = new JsonArray();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			JsonObject p = new JsonObject();
			p.addProperty("name", player.getGameProfile().getName());
			p.addProperty("uuid", player.getStringUUID());
			p.addProperty("dimension", player.level().dimension().location().toString());
			p.add("pos", Json.blockPos(player.blockPosition()));
			players.add(p);
		}
		result.add("players", players);

		ServerLevel overworld = server.overworld();
		result.addProperty("gameTime", overworld.getGameTime());
		result.addProperty("dayTime", overworld.getDayTime());
		result.addProperty("difficulty", server.getWorldData().getDifficulty().getKey());
		return result;
	}
}
