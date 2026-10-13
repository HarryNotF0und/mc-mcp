package dev.haochen.mcmcp.api;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.net.RpcDispatcher;

public final class SystemApi {
	private SystemApi() {
	}

	public static void register(RpcDispatcher dispatcher) {
		dispatcher.onNetworkThread("system.ping", (server, params) -> {
			JsonObject result = new JsonObject();
			result.addProperty("pong", true);
			result.addProperty("modVersion", McMcpMod.version());
			result.addProperty("serverTimeMs", System.currentTimeMillis());
			return result;
		});
	}
}
