package dev.haochen.mcmcp.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

/** Handles one JSON-RPC method. Throw {@link RpcException} to return an error. */
@FunctionalInterface
public interface RpcHandler {
	JsonElement handle(MinecraftServer server, JsonObject params) throws RpcException;
}
