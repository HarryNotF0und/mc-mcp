package dev.haochen.mcmcp.api;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.net.RpcDispatcher;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletionException;

/** Calls agent.* handlers like the bridge would. On the server thread the dispatch runs synchronously. */
public final class AgentApiAccess {
	private static RpcDispatcher dispatcher;
	private static MinecraftServer server;

	private AgentApiAccess() {
	}

	public static JsonObject call(String method, JsonObject params) {
		if (dispatcher == null || server != McMcpMod.server()) {
			server = McMcpMod.server();
			dispatcher = new RpcDispatcher(server);
			AgentApi.register(dispatcher);
		}
		try {
			return dispatcher.dispatch(method, params).join().getAsJsonObject();
		} catch (CompletionException e) {
			throw RpcDispatcher.toRpcException(e);
		}
	}
}
