package dev.haochen.mcmcp.net;

import com.google.gson.JsonElement;

/** A JSON-RPC error to send back to the bridge. Codes are listed in {@code protocol/spec.md}. */
public class RpcException extends RuntimeException {
	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int INTERNAL_ERROR = -32603;
	public static final int UNAUTHORIZED = -32001;

	public static final int OUTSIDE_ALLOWED_REGION = 1001;
	public static final int VOLUME_TOO_LARGE = 1002;
	public static final int UNKNOWN_BLOCK = 1003;
	public static final int CHUNK_NOT_LOADED = 1004;
	public static final int JOB_NOT_FOUND = 1005;
	public static final int SCRIPT_ERROR = 1006;

	private final int code;
	private final JsonElement data;

	public RpcException(int code, String message) {
		this(code, message, null);
	}

	public RpcException(int code, String message, JsonElement data) {
		super(message);
		this.code = code;
		this.data = data;
	}

	public int code() {
		return code;
	}

	public JsonElement data() {
		return data;
	}
}
