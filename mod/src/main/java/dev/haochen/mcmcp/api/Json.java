package dev.haochen.mcmcp.api;

import com.google.gson.JsonArray;
import net.minecraft.core.BlockPos;

/** Small JSON helpers shared by the API handlers. */
public final class Json {
	private Json() {
	}

	public static JsonArray blockPos(BlockPos pos) {
		JsonArray a = new JsonArray();
		a.add(pos.getX());
		a.add(pos.getY());
		a.add(pos.getZ());
		return a;
	}
}
