package dev.haochen.mcmcp.world;

import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.haochen.mcmcp.net.RpcException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Converts between block state strings like {@code minecraft:oak_stairs[facing=north]} and {@link BlockState}. */
public final class Blocks {
	private Blocks() {
	}

	/** Parses a block state string. Unknown blocks or properties become error {@code 1003}. */
	public static BlockState parse(String text) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), text, false).blockState();
		} catch (CommandSyntaxException e) {
			JsonObject data = new JsonObject();
			data.addProperty("block", text);
			throw new RpcException(RpcException.UNKNOWN_BLOCK, "invalid block '" + text + "': " + e.getRawMessage().getString(), data);
		}
	}

	/** Formats a state in the game's native syntax; properties are omitted when the block has none. */
	public static String toString(BlockState state) {
		return BlockStateParser.serialize(state);
	}

	public static String id(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}
}
