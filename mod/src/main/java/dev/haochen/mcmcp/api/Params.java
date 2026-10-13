package dev.haochen.mcmcp.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.Region;
import net.minecraft.core.BlockPos;

/** Typed accessors for JSON-RPC params; every malformed value becomes a {@code -32602} error. */
public final class Params {
	private Params() {
	}

	public static RpcException invalid(String message) {
		return new RpcException(RpcException.INVALID_PARAMS, message);
	}

	public static JsonElement require(JsonObject params, String name) {
		JsonElement e = params.get(name);
		if (e == null || e.isJsonNull()) {
			throw invalid("missing parameter: " + name);
		}
		return e;
	}

	public static String string(JsonObject params, String name) {
		JsonElement e = require(params, name);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			throw invalid(name + " must be a string");
		}
		return e.getAsString();
	}

	public static String string(JsonObject params, String name, String fallback) {
		return has(params, name) ? string(params, name) : fallback;
	}

	public static int integer(JsonObject params, String name, int fallback) {
		if (!has(params, name)) {
			return fallback;
		}
		JsonElement e = params.get(name);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw invalid(name + " must be an integer");
		}
		double d = e.getAsDouble();
		if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
			throw invalid(name + " must be an integer");
		}
		return (int) d;
	}

	public static boolean bool(JsonObject params, String name, boolean fallback) {
		if (!has(params, name)) {
			return fallback;
		}
		JsonElement e = params.get(name);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw invalid(name + " must be true or false");
		}
		return e.getAsBoolean();
	}

	public static JsonArray array(JsonObject params, String name) {
		JsonElement e = require(params, name);
		if (!e.isJsonArray()) {
			throw invalid(name + " must be an array");
		}
		return e.getAsJsonArray();
	}

	public static JsonObject object(JsonObject params, String name) {
		JsonElement e = require(params, name);
		if (!e.isJsonObject()) {
			throw invalid(name + " must be an object");
		}
		return e.getAsJsonObject();
	}

	public static BlockPos pos(JsonObject params, String name) {
		return pos(require(params, name), name);
	}

	/** Parses {@code [x, y, z]}. */
	public static BlockPos pos(JsonElement e, String what) {
		if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) {
			throw invalid(what + " must be [x, y, z]");
		}
		JsonArray a = e.getAsJsonArray();
		return new BlockPos(coord(a.get(0), what), coord(a.get(1), what), coord(a.get(2), what));
	}

	/** Parses {@code {"min":[x,y,z],"max":[x,y,z]}}; the corners may be given in any order. */
	public static Region region(JsonObject params, String name) {
		JsonObject r = object(params, name);
		return Region.of(pos(r, "min"), pos(r, "max"));
	}

	public static boolean has(JsonObject params, String name) {
		return params.has(name) && !params.get(name).isJsonNull();
	}

	private static int coord(JsonElement e, String what) {
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw invalid(what + " must contain integer coordinates");
		}
		double d = e.getAsDouble();
		if (d != Math.rint(d) || Math.abs(d) > 30_000_000) {
			throw invalid(what + " must contain integer coordinates within the world border");
		}
		return (int) d;
	}
}
