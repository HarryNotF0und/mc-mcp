package dev.haochen.mcmcp.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.api.Json;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The palette + layers format shared by perception output and blueprint input (PLAN.md §5):
 * {@code layers[i]} is {@code y = origin.y + i}; row {@code j} is {@code z = origin.z + j};
 * character {@code k} is {@code x = origin.x + k}.
 */
public final class LayerFormat {
	private static final String FALLBACK = "#@$%&*+=-~^!?<>/|:;";

	private LayerFormat() {
	}

	public static JsonObject encode(ServerLevel level, Region region) {
		int sx = region.sizeX(), sy = region.sizeY(), sz = region.sizeZ();
		BlockState[] states = new BlockState[sx * sy * sz];
		Map<BlockState, Integer> counts = new HashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int i = 0;
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					pos.set(region.min().getX() + x, region.min().getY() + y, region.min().getZ() + z);
					BlockState s = level.getBlockState(pos);
					states[i++] = s;
					counts.merge(s, 1, Integer::sum);
				}
			}
		}

		// Most common states pick their letters first, so they get the most readable ones.
		List<BlockState> byFrequency = new ArrayList<>(counts.keySet());
		byFrequency.sort((a, b) -> counts.get(b) - counts.get(a));
		Map<BlockState, Character> chars = assignChars(byFrequency);

		JsonObject palette = new JsonObject();
		chars.forEach((state, c) -> palette.addProperty(String.valueOf(c), Blocks.toString(state)));

		JsonArray layers = new JsonArray();
		i = 0;
		StringBuilder row = new StringBuilder(sx);
		for (int y = 0; y < sy; y++) {
			JsonArray layer = new JsonArray();
			for (int z = 0; z < sz; z++) {
				row.setLength(0);
				for (int x = 0; x < sx; x++) {
					row.append(chars.get(states[i++]));
				}
				layer.add(row.toString());
			}
			layers.add(layer);
		}

		JsonObject out = new JsonObject();
		out.add("origin", Json.blockPos(region.min()));
		JsonArray size = new JsonArray();
		size.add(sx);
		size.add(sy);
		size.add(sz);
		out.add("size", size);
		out.add("palette", palette);
		out.add("layers", layers);
		return out;
	}

	/**
	 * Air is {@code .}; other states prefer the first letter of their name (upper, then lower case),
	 * then other letters of the name, then digits and symbols.
	 */
	static Map<BlockState, Character> assignChars(List<BlockState> states) {
		Map<BlockState, Character> result = new LinkedHashMap<>();
		Set<Character> used = new HashSet<>();
		for (BlockState s : states) {
			if (s.isAir() && used.add('.')) {
				result.put(s, '.');
			}
		}
		for (BlockState s : states) {
			if (result.containsKey(s)) {
				continue;
			}
			String id = Blocks.id(s);
			String name = id.substring(id.indexOf(':') + 1);
			char c = pick(name, used);
			used.add(c);
			result.put(s, c);
		}
		return result;
	}

	private static char pick(String name, Set<Character> used) {
		List<Character> candidates = new ArrayList<>();
		// Initials of each word first: "stone_bricks" -> S, B.
		for (String word : name.split("_")) {
			if (!word.isEmpty() && Character.isLetter(word.charAt(0))) {
				candidates.add(Character.toUpperCase(word.charAt(0)));
				candidates.add(Character.toLowerCase(word.charAt(0)));
			}
		}
		for (char ch : name.toCharArray()) {
			if (Character.isLetter(ch)) {
				candidates.add(Character.toUpperCase(ch));
				candidates.add(Character.toLowerCase(ch));
			}
		}
		for (char ch = 'A'; ch <= 'Z'; ch++) {
			candidates.add(ch);
			candidates.add(Character.toLowerCase(ch));
		}
		for (char ch = '0'; ch <= '9'; ch++) {
			candidates.add(ch);
		}
		for (char ch : FALLBACK.toCharArray()) {
			candidates.add(ch);
		}
		for (char ch : candidates) {
			if (!used.contains(ch)) {
				return ch;
			}
		}
		// More distinct states than printable ASCII: continue with Latin-1 letters and beyond.
		for (char ch = 'À'; ; ch++) {
			if (Character.isLetter(ch) && !used.contains(ch)) {
				return ch;
			}
		}
	}
}
