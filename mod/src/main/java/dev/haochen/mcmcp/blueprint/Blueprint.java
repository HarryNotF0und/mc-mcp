package dev.haochen.mcmcp.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.api.Params;
import dev.haochen.mcmcp.world.BlockList;
import dev.haochen.mcmcp.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * A parsed blueprint in the palette + layers format (see {@code LayerFormat}): {@code cells} is
 * indexed {@code (y * sz + z) * sx + x}, {@code null} meaning "palette character maps to air".
 */
public final class Blueprint {
	private final int sx, sy, sz;
	private final BlockState[] cells;
	private final @Nullable BlockPos origin;

	private Blueprint(int sx, int sy, int sz, BlockState[] cells, @Nullable BlockPos origin) {
		this.sx = sx;
		this.sy = sy;
		this.sz = sz;
		this.cells = cells;
		this.origin = origin;
	}

	/** A blueprint from already-parsed cells, indexed {@code (y * sz + z) * sx + x}. */
	public static Blueprint of(int sx, int sy, int sz, BlockState[] cells) {
		if (cells.length != sx * sy * sz) {
			throw new IllegalArgumentException("cells do not match size");
		}
		return new Blueprint(sx, sy, sz, cells, null);
	}

	/** Every distinct state, for blacklist checks. */
	public java.util.Set<BlockState> states() {
		return new java.util.HashSet<>(java.util.Arrays.asList(cells));
	}

	public @Nullable BlockPos origin() {
		return origin;
	}

	/** Parses and validates; every palette entry passes {@link Guard#block} (unknown/blacklisted -> 1003). */
	public static Blueprint parse(JsonObject json, Guard guard) {
		JsonObject palette = Params.object(json, "palette");
		Map<Character, BlockState> states = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : palette.entrySet()) {
			if (e.getKey().length() != 1) {
				throw Params.invalid("palette keys must be single characters, got \"" + e.getKey() + "\"");
			}
			if (!e.getValue().isJsonPrimitive()) {
				throw Params.invalid("palette value for '" + e.getKey() + "' must be a block string");
			}
			states.put(e.getKey().charAt(0), guard.block(e.getValue().getAsString()));
		}

		JsonArray layers = Params.array(json, "layers");
		int sy = layers.size();
		if (sy == 0) {
			throw Params.invalid("layers is empty");
		}
		int sz = -1, sx = -1;
		for (int y = 0; y < sy; y++) {
			if (!layers.get(y).isJsonArray()) {
				throw Params.invalid("layers[" + y + "] must be an array of strings");
			}
			JsonArray rows = layers.get(y).getAsJsonArray();
			if (sz == -1) {
				sz = rows.size();
			} else if (rows.size() != sz) {
				throw Params.invalid("layers[" + y + "] has " + rows.size() + " rows, expected " + sz);
			}
			for (int z = 0; z < rows.size(); z++) {
				int len = rows.get(z).getAsString().length();
				if (sx == -1) {
					sx = len;
				} else if (len != sx) {
					throw Params.invalid("layers[" + y + "][" + z + "] has " + len + " characters, expected " + sx);
				}
			}
		}
		if (sz <= 0 || sx <= 0) {
			throw Params.invalid("layers must contain non-empty rows");
		}
		if (Params.has(json, "size")) {
			BlockPos size = Params.pos(json, "size");
			if (size.getX() != sx || size.getY() != sy || size.getZ() != sz) {
				throw Params.invalid("size " + size.toShortString() + " does not match layers (" + sx + ", " + sy + ", " + sz + ")");
			}
		}

		BlockState[] cells = new BlockState[sx * sy * sz];
		for (int y = 0; y < sy; y++) {
			JsonArray rows = layers.get(y).getAsJsonArray();
			for (int z = 0; z < sz; z++) {
				String row = rows.get(z).getAsString();
				for (int x = 0; x < sx; x++) {
					char c = row.charAt(x);
					BlockState s = states.get(c);
					if (s == null) {
						throw Params.invalid("layers[" + y + "][" + z + "][" + x + "]: character '" + c + "' is not in the palette");
					}
					cells[(y * sz + z) * sx + x] = s;
				}
			}
		}
		BlockPos origin = Params.has(json, "origin") ? Params.pos(json, "origin") : null;
		return new Blueprint(sx, sy, sz, cells, origin);
	}

	public static Rotation rotation(int degrees) {
		return switch (degrees) {
			case 0 -> Rotation.NONE;
			case 90 -> Rotation.CLOCKWISE_90;
			case 180 -> Rotation.CLOCKWISE_180;
			case 270 -> Rotation.COUNTERCLOCKWISE_90;
			default -> throw Params.invalid("rotation must be 0, 90, 180 or 270");
		};
	}

	public static Mirror mirror(String axis) {
		return switch (axis) {
			case "none" -> Mirror.NONE;
			case "x" -> Mirror.FRONT_BACK; // flips along x: east <-> west
			case "z" -> Mirror.LEFT_RIGHT; // flips along z: north <-> south
			default -> throw Params.invalid("mirror must be \"none\", \"x\" or \"z\"");
		};
	}

	/**
	 * World placements: mirror first, then rotate clockwise seen from above (north -> east) around
	 * the vertical axis. The transformed footprint's minimum corner lands on {@code at}.
	 * Block states (facing, axis, rail shape, ...) are transformed the same way.
	 */
	public BlockList place(BlockPos at, Rotation rotation, Mirror mirror, boolean skipAir) {
		BlockList out = new BlockList();
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					BlockState s = cells[(y * sz + z) * sx + x];
					if (skipAir && s.isAir()) {
						continue;
					}
					int mx = mirror == Mirror.FRONT_BACK ? sx - 1 - x : x;
					int mz = mirror == Mirror.LEFT_RIGHT ? sz - 1 - z : z;
					int tx, tz;
					switch (rotation) {
						case CLOCKWISE_90 -> { tx = sz - 1 - mz; tz = mx; }
						case CLOCKWISE_180 -> { tx = sx - 1 - mx; tz = sz - 1 - mz; }
						case COUNTERCLOCKWISE_90 -> { tx = mz; tz = sx - 1 - mx; }
						default -> { tx = mx; tz = mz; }
					}
					out.put(at.offset(tx, y, tz), s.mirror(mirror).rotate(rotation));
				}
			}
		}
		return out;
	}
}
