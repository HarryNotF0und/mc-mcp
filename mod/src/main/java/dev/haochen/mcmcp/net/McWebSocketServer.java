package dev.haochen.mcmcp.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.haochen.mcmcp.McMcpMod;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * JSON-RPC 2.0 over WebSocket. Runs on its own threads; game state is only touched through
 * {@link RpcDispatcher}, which hops to the server thread.
 *
 * <p>The first message on every connection must be {@code auth} with the configured token,
 * otherwise the connection is closed.
 */
public final class McWebSocketServer extends WebSocketServer {
	private final byte[] token;
	private final RpcDispatcher dispatcher;

	public McWebSocketServer(InetSocketAddress address, String token, RpcDispatcher dispatcher) {
		super(address);
		this.token = token.getBytes(StandardCharsets.UTF_8);
		this.dispatcher = dispatcher;
		setReuseAddr(true);
		setConnectionLostTimeout(30);
	}

	@Override
	public void onStart() {
		McMcpMod.LOGGER.info("MC-MCP listening on ws://{}:{}", getAddress().getHostString(), getPort());
	}

	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake) {
		conn.setAttachment(Boolean.FALSE);
	}

	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote) {
	}

	@Override
	public void onError(WebSocket conn, Exception ex) {
		if (conn == null) {
			McMcpMod.LOGGER.error("MC-MCP WebSocket server error (is port {} already in use?)", getPort(), ex);
		} else {
			McMcpMod.LOGGER.warn("MC-MCP connection error", ex);
		}
	}

	@Override
	public void onMessage(WebSocket conn, String message) {
		JsonObject request;
		try {
			request = JsonParser.parseString(message).getAsJsonObject();
		} catch (RuntimeException e) {
			sendError(conn, JsonNull.INSTANCE, new RpcException(RpcException.PARSE_ERROR, "invalid JSON"));
			return;
		}
		JsonElement id = request.has("id") ? request.get("id") : JsonNull.INSTANCE;
		if (!request.has("method") || !request.get("method").isJsonPrimitive()) {
			sendError(conn, id, new RpcException(RpcException.INVALID_REQUEST, "missing method"));
			return;
		}
		String method = request.get("method").getAsString();
		JsonObject params = request.has("params") && request.get("params").isJsonObject()
				? request.getAsJsonObject("params") : new JsonObject();

		if (!Boolean.TRUE.equals(conn.getAttachment())) {
			if (method.equals("auth") && checkToken(params)) {
				conn.setAttachment(Boolean.TRUE);
				JsonObject result = new JsonObject();
				result.addProperty("ok", true);
				sendResult(conn, id, result);
			} else {
				sendError(conn, id, new RpcException(RpcException.UNAUTHORIZED,
						"first message must be auth with a valid token"));
				conn.close(CloseFrame.POLICY_VALIDATION, "unauthorized");
			}
			return;
		}

		dispatcher.dispatch(method, params).whenComplete((result, error) -> {
			if (!conn.isOpen()) {
				return;
			}
			if (error != null) {
				sendError(conn, id, RpcDispatcher.toRpcException(error));
			} else {
				sendResult(conn, id, result == null ? JsonNull.INSTANCE : result);
			}
		});
	}

	/** Pushes a notification (no id) to every authenticated connection. */
	public void notifyAll(String method, JsonElement params) {
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("method", method);
		msg.add("params", params);
		String text = msg.toString();
		for (WebSocket conn : getConnections()) {
			if (Boolean.TRUE.equals(conn.getAttachment()) && conn.isOpen()) {
				conn.send(text);
			}
		}
	}

	private boolean checkToken(JsonObject params) {
		if (!params.has("token") || !params.get("token").isJsonPrimitive()) {
			return false;
		}
		byte[] given = params.get("token").getAsString().getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(token, given);
	}

	private static void sendResult(WebSocket conn, JsonElement id, JsonElement result) {
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.add("id", id);
		msg.add("result", result);
		conn.send(msg.toString());
	}

	private static void sendError(WebSocket conn, JsonElement id, RpcException e) {
		JsonObject error = new JsonObject();
		error.addProperty("code", e.code());
		error.addProperty("message", e.getMessage());
		if (e.data() != null) {
			error.add("data", e.data());
		}
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.add("id", id);
		msg.add("error", error);
		conn.send(msg.toString());
	}
}
