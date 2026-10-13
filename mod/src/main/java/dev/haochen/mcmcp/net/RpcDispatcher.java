package dev.haochen.mcmcp.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Maps method names to handlers. Handlers registered with {@link #onMainThread} run on the
 * server thread; the network thread only queues them and sends the result when it completes.
 */
public final class RpcDispatcher {
	/** A handler that starts work elsewhere (e.g. a worker thread) and completes later. */
	@FunctionalInterface
	public interface AsyncHandler {
		CompletableFuture<JsonElement> handle(MinecraftServer server, JsonObject params) throws RpcException;
	}

	private record Entry(RpcHandler handler, boolean mainThread, AsyncHandler async) {
	}

	private final MinecraftServer server;
	private final Map<String, Entry> handlers = new HashMap<>();

	public RpcDispatcher(MinecraftServer server) {
		this.server = server;
	}

	/** Registers a handler that touches game state and therefore must run on the server thread. */
	public RpcDispatcher onMainThread(String method, RpcHandler handler) {
		handlers.put(method, new Entry(handler, true, null));
		return this;
	}

	/** Registers a handler that is safe to run directly on the network thread. */
	public RpcDispatcher onNetworkThread(String method, RpcHandler handler) {
		handlers.put(method, new Entry(handler, false, null));
		return this;
	}

	/**
	 * Registers a handler that is called on the network thread and returns a future. It must not
	 * touch game state directly; use {@code server.submit} for that.
	 */
	public RpcDispatcher async(String method, AsyncHandler handler) {
		handlers.put(method, new Entry(null, false, handler));
		return this;
	}

	public CompletableFuture<JsonElement> dispatch(String method, JsonObject params) {
		Entry entry = handlers.get(method);
		if (entry == null) {
			return CompletableFuture.failedFuture(
					new RpcException(RpcException.METHOD_NOT_FOUND, "unknown method: " + method));
		}
		if (entry.async() != null) {
			try {
				return entry.async().handle(server, params);
			} catch (Throwable t) {
				return CompletableFuture.failedFuture(t);
			}
		}
		if (!entry.mainThread()) {
			try {
				return CompletableFuture.completedFuture(entry.handler().handle(server, params));
			} catch (Throwable t) {
				return CompletableFuture.failedFuture(t);
			}
		}
		return server.submit(() -> entry.handler().handle(server, params));
	}

	/** Unwraps a failure from {@link #dispatch} into an {@link RpcException}. */
	public static RpcException toRpcException(Throwable t) {
		while (t instanceof CompletionException && t.getCause() != null) {
			t = t.getCause();
		}
		if (t instanceof RpcException e) {
			return e;
		}
		McMcpMod.LOGGER.error("Unhandled error in RPC handler", t);
		return new RpcException(RpcException.INTERNAL_ERROR, "internal error: " + t);
	}
}
