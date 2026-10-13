package dev.haochen.mcmcp.arch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.api.Params;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed building plan ({@code "format": "mcmcp.building/1"}, see docs/building-plan.md).
 *
 * <p>Local frame: x runs from the left wall (0) to the right wall (width-1) as seen from outside
 * looking at the front; z runs from the back wall (0) to the front wall (depth-1); y is up, with 0
 * the ground floor's floor layer. The frame is rotated so the front faces {@link #facing}, and
 * {@link #origin} is the world position of the rotated footprint's minimum corner at y = 0.
 */
public final class BuildingPlan {
	public static final String FORMAT = "mcmcp.building/1";

	public enum Side {
		FRONT(Direction.SOUTH), BACK(Direction.NORTH), LEFT(Direction.WEST), RIGHT(Direction.EAST);

		/** Outward direction of this wall in the local frame. */
		public final Direction out;

		Side(Direction out) {
			this.out = out;
		}

		static Side parse(String s, String where) {
			return switch (s) {
				case "front" -> FRONT;
				case "back" -> BACK;
				case "left" -> LEFT;
				case "right" -> RIGHT;
				default -> throw Params.invalid(where + " must be front, back, left or right");
			};
		}
	}

	public record Floor(String id, int index, int base, int height) {
		/** y of the floor's walking surface (feet level). */
		public int feet() {
			return base + 1;
		}
	}

	public record Room(String id, Floor floor, String function, int minX, int minZ, int maxX, int maxZ) {
	}

	public record Partition(String id, Floor floor, int x1, int z1, int x2, int z2) {
		public boolean alongX() {
			return z1 == z2;
		}
	}

	public record Opening(String id, String type, Floor floor, Side wall, Partition partition, int at, int width, int height, int sill) {
	}

	public record Stairs(String id, Floor from, Floor to, int x, int z, Side direction, int width) {
	}

	public record Roof(String id, String type, boolean ridgeAlongX, int overhang) {
	}

	public record Tower(String id, int x, int z, int radius, int height, String top) {
	}

	public record Outdoor(String id, String type, int length, int margin) {
	}

	public record Patch(String id, String component, List<int[]> positions, List<BlockState> states) {
	}

	public final String name;
	public final BlockPos origin;
	public final Direction facing;
	public final int width, depth;
	public final Map<String, BlockState> style = new LinkedHashMap<>();
	public final List<Floor> floors = new ArrayList<>();
	public final List<Room> rooms = new ArrayList<>();
	public final List<Partition> partitions = new ArrayList<>();
	public final List<Opening> openings = new ArrayList<>();
	public final List<Stairs> stairs = new ArrayList<>();
	public final Roof roof;
	public final List<Tower> towers = new ArrayList<>();
	public final List<Outdoor> outdoor = new ArrayList<>();
	public final List<Patch> patches = new ArrayList<>();
	public final int terrainMargin, maxFoundationDepth;
	public final boolean furnish, replaceExisting;
	private final Set<String> ids = new HashSet<>();

	public static final Map<String, String> DEFAULT_STYLE = new LinkedHashMap<>();

	static {
		DEFAULT_STYLE.put("foundation", "minecraft:cobblestone");
		DEFAULT_STYLE.put("floor", "minecraft:spruce_planks");
		DEFAULT_STYLE.put("wall", "minecraft:oak_planks");
		DEFAULT_STYLE.put("frame", "minecraft:oak_log");
		DEFAULT_STYLE.put("roofStairs", "minecraft:dark_oak_stairs");
		DEFAULT_STYLE.put("roofSlab", "minecraft:dark_oak_slab");
		DEFAULT_STYLE.put("roofBlock", "minecraft:dark_oak_planks");
		DEFAULT_STYLE.put("stairs", "minecraft:spruce_stairs");
		DEFAULT_STYLE.put("window", "minecraft:glass_pane");
		DEFAULT_STYLE.put("door", "minecraft:spruce_door");
		DEFAULT_STYLE.put("ground", "minecraft:grass_block");
		DEFAULT_STYLE.put("path", "minecraft:dirt_path");
		DEFAULT_STYLE.put("light", "minecraft:lantern");
		DEFAULT_STYLE.put("fence", "minecraft:oak_fence");
		DEFAULT_STYLE.put("gate", "minecraft:oak_fence_gate");
		DEFAULT_STYLE.put("tower", "minecraft:stone_bricks");
		DEFAULT_STYLE.put("trim", "minecraft:stone_brick_stairs");
	}

	private BuildingPlan(JsonObject j, Guard guard) {
		String format = Params.string(j, "format", FORMAT);
		if (!format.equals(FORMAT)) {
			throw Params.invalid("building.format must be \"" + FORMAT + "\"");
		}
		name = Params.string(j, "name", "building");
		origin = Params.pos(j, "origin");
		facing = direction(Params.string(j, "facing", "south"), "building.facing");
		JsonArray fp = Params.array(j, "footprint");
		if (fp.size() != 2) {
			throw Params.invalid("building.footprint must be [width, depth]");
		}
		width = fp.get(0).getAsInt();
		depth = fp.get(1).getAsInt();
		if (width < 3 || depth < 3 || width > 48 || depth > 48) {
			throw Params.invalid("building.footprint must be between 3 and 48 on each side");
		}

		JsonObject st = Params.has(j, "style") ? Params.object(j, "style") : new JsonObject();
		for (var e : st.entrySet()) {
			if (!DEFAULT_STYLE.containsKey(e.getKey())) {
				throw Params.invalid("building.style." + e.getKey() + " is not a style key; known: " + DEFAULT_STYLE.keySet());
			}
		}
		for (var e : DEFAULT_STYLE.entrySet()) {
			String text = st.has(e.getKey()) ? st.get(e.getKey()).getAsString() : e.getValue();
			style.put(e.getKey(), guard.block(text));
		}
		requireBlock("door", DoorBlock.class);
		requireBlock("stairs", StairBlock.class);
		requireBlock("roofStairs", StairBlock.class);
		requireBlock("roofSlab", SlabBlock.class);
		requireBlock("trim", StairBlock.class);

		JsonArray fl = Params.array(j, "floors");
		if (fl.isEmpty() || fl.size() > 6) {
			throw Params.invalid("building.floors must have 1 to 6 entries");
		}
		int base = 0;
		for (int i = 0; i < fl.size(); i++) {
			JsonObject f = fl.get(i).getAsJsonObject();
			String id = id(f, "building.floors[" + i + "]");
			int h = Params.integer(f, "height", 4);
			if (h < 3 || h > 12) {
				throw Params.invalid("building.floors[" + i + "].height must be 3 to 12 (floor layer included; 4 gives 3 blocks of headroom)");
			}
			floors.add(new Floor(id, i, base, h));
			base += h;
		}

		for (JsonObject r : objects(j, "rooms")) {
			String where = "building.rooms[" + rooms.size() + "]";
			int[] a = xz(r, "min", where), b = xz(r, "max", where);
			rooms.add(new Room(id(r, where), floor(Params.string(r, "floor"), where), Params.string(r, "function", "room"),
					Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])));
		}
		for (JsonObject p : objects(j, "partitions")) {
			String where = "building.partitions[" + partitions.size() + "]";
			int[] a = xz(p, "from", where), b = xz(p, "to", where);
			if (a[0] != b[0] && a[1] != b[1]) {
				throw Params.invalid(where + " must be a straight line along x or z");
			}
			partitions.add(new Partition(id(p, where), floor(Params.string(p, "floor"), where),
					Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])));
		}
		for (JsonObject o : objects(j, "openings")) {
			String where = "building.openings[" + openings.size() + "]";
			String type = Params.string(o, "type");
			if (!type.equals("door") && !type.equals("window") && !type.equals("arch")) {
				throw Params.invalid(where + ".type must be door, window or arch");
			}
			Floor floor = floor(Params.string(o, "floor"), where);
			Side wall = null;
			Partition part = null;
			if (Params.has(o, "partition")) {
				String pid = Params.string(o, "partition");
				part = partitions.stream().filter(p -> p.id().equals(pid)).findFirst()
						.orElseThrow(() -> Params.invalid(where + ".partition: no partition " + pid));
			} else {
				wall = Side.parse(Params.string(o, "wall"), where + ".wall");
			}
			int w = Params.integer(o, "width", type.equals("arch") ? 3 : type.equals("window") ? 2 : 1);
			int h = Params.integer(o, "height", type.equals("arch") ? 3 : 2);
			int sill = Params.integer(o, "sill", type.equals("window") ? 1 : 0);
			if (type.equals("door") && (w < 1 || w > 2 || h != 2)) {
				throw Params.invalid(where + ": doors are 1 or 2 wide and 2 high");
			}
			if (w < 1 || h < 1 || sill < 0 || sill + h > floor.height() - 1) {
				throw Params.invalid(where + ": sill + height must fit below the ceiling (" + (floor.height() - 1) + " blocks)");
			}
			openings.add(new Opening(id(o, where), type, floor, wall, part, Params.integer(o, "at", 1), w, h, sill));
		}
		for (JsonObject s : objects(j, "stairs")) {
			String where = "building.stairs[" + stairs.size() + "]";
			Floor from = floor(Params.string(s, "from"), where);
			if (from.index() + 1 >= floors.size()) {
				throw Params.invalid(where + ".from: there is no floor above " + from.id());
			}
			int[] start = xz(s, "start", where);
			int w = Params.integer(s, "width", 1);
			if (w < 1 || w > 2) {
				throw Params.invalid(where + ".width must be 1 or 2");
			}
			stairs.add(new Stairs(id(s, where), from, floors.get(from.index() + 1), start[0], start[1],
					Side.parse(Params.string(s, "direction"), where + ".direction"), w));
		}
		if (Params.has(j, "roof")) {
			JsonObject r = Params.object(j, "roof");
			String type = Params.string(r, "type", "gable");
			if (!List.of("gable", "hip", "flat", "none").contains(type)) {
				throw Params.invalid("building.roof.type must be gable, hip, flat or none");
			}
			String ridge = Params.string(r, "ridge", width >= depth ? "width" : "depth");
			if (!ridge.equals("width") && !ridge.equals("depth")) {
				throw Params.invalid("building.roof.ridge must be width or depth");
			}
			int overhang = Params.integer(r, "overhang", 1);
			if (overhang < 0 || overhang > 2) {
				throw Params.invalid("building.roof.overhang must be 0 to 2");
			}
			roof = new Roof(id(r, "building.roof", "roof"), type, ridge.equals("width"), overhang);
		} else {
			roof = new Roof(addId("roof", "building.roof"), "gable", width >= depth, 1);
		}
		for (JsonObject c : objects(j, "components")) {
			String where = "building.components[" + (towers.size()) + "]";
			String type = Params.string(c, "type");
			if (!type.equals("tower")) {
				throw Params.invalid(where + ".type must be tower (doors, windows and arches go in openings)");
			}
			int[] at = xz(c, "at", where);
			int radius = Params.integer(c, "radius", 2);
			int height = Params.integer(c, "height", floors.get(floors.size() - 1).base() + floors.get(floors.size() - 1).height() + 3);
			if (radius < 1 || radius > 8 || height < 3 || height > 64) {
				throw Params.invalid(where + ": radius 1-8, height 3-64");
			}
			String top = Params.string(c, "top", "battlement");
			if (!List.of("battlement", "cone", "flat").contains(top)) {
				throw Params.invalid(where + ".top must be battlement, cone or flat");
			}
			towers.add(new Tower(id(c, where), at[0], at[1], radius, height, top));
		}
		for (JsonObject o : objects(j, "outdoor")) {
			String where = "building.outdoor[" + outdoor.size() + "]";
			String type = Params.string(o, "type");
			if (!List.of("path", "fence", "lanterns").contains(type)) {
				throw Params.invalid(where + ".type must be path, fence or lanterns");
			}
			outdoor.add(new Outdoor(id(o, where), type, Params.integer(o, "length", 5), Params.integer(o, "margin", 2)));
		}
		JsonObject t = Params.has(j, "terrain") ? Params.object(j, "terrain") : new JsonObject();
		terrainMargin = Params.integer(t, "margin", 2);
		maxFoundationDepth = Params.integer(t, "maxFoundationDepth", 12);
		if (terrainMargin < 0 || terrainMargin > 6 || maxFoundationDepth < 1 || maxFoundationDepth > 32) {
			throw Params.invalid("building.terrain: margin 0-6, maxFoundationDepth 1-32");
		}
		for (Outdoor o : outdoor) {
			if (o.type().equals("fence") && (o.margin() < 1 || o.margin() > terrainMargin)) {
				throw Params.invalid("building.outdoor " + o.id() + ": fence margin must be 1 to terrain.margin (" + terrainMargin + ")");
			}
		}
		furnish = Params.bool(j, "furnish", true);
		replaceExisting = Params.bool(j, "replaceExisting", false);
		for (JsonObject p : objects(j, "patches")) {
			String where = "building.patches[" + patches.size() + "]";
			List<int[]> pos = new ArrayList<>();
			List<BlockState> states = new ArrayList<>();
			JsonArray blocks = Params.array(p, "blocks");
			for (int i = 0; i < blocks.size(); i++) {
				JsonArray b = blocks.get(i).getAsJsonArray();
				if (b.size() != 4) {
					throw Params.invalid(where + ".blocks[" + i + "] must be [x, y, z, \"block\"] in local coordinates");
				}
				pos.add(new int[]{b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt()});
				states.add(guard.block(b.get(3).getAsString()));
			}
			patches.add(new Patch(id(p, where), Params.string(p, "component", "patch"), pos, states));
		}
	}

	public static BuildingPlan parse(JsonObject json, Guard guard) {
		try {
			return new BuildingPlan(json, guard);
		} catch (IllegalStateException | ClassCastException | UnsupportedOperationException | NumberFormatException e) {
			throw Params.invalid("malformed building plan: " + e.getMessage());
		}
	}

	/** Total height of the floors (the roof starts here). */
	public int wallTop() {
		Floor last = floors.get(floors.size() - 1);
		return last.base() + last.height();
	}

	public Floor floorById(String id) {
		return floors.stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
	}

	private Floor floor(String id, String where) {
		Floor f = floorById(id);
		if (f == null) {
			throw Params.invalid(where + ".floor: no floor with id " + id);
		}
		return f;
	}

	private void requireBlock(String key, Class<?> type) {
		if (!type.isInstance(style.get(key).getBlock())) {
			throw Params.invalid("building.style." + key + " must be a " + type.getSimpleName().replace("Block", "").toLowerCase() + " block");
		}
	}

	private String id(JsonObject o, String where) {
		return addId(Params.string(o, "id"), where);
	}

	private String id(JsonObject o, String where, String fallback) {
		return addId(Params.string(o, "id", fallback), where);
	}

	private String addId(String id, String where) {
		if (!id.matches("[A-Za-z0-9_.-]{1,48}")) {
			throw Params.invalid(where + ".id must be 1-48 letters, digits, _ . -");
		}
		if (!ids.add(id)) {
			throw Params.invalid(where + ".id \"" + id + "\" is used twice; component ids must be unique");
		}
		return id;
	}

	private static int[] xz(JsonObject o, String key, String where) {
		JsonArray a = Params.array(o, key);
		if (a.size() != 2) {
			throw Params.invalid(where + "." + key + " must be [x, z] in local coordinates");
		}
		return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()};
	}

	private static List<JsonObject> objects(JsonObject j, String key) {
		List<JsonObject> out = new ArrayList<>();
		if (!Params.has(j, key)) {
			return out;
		}
		for (JsonElement e : Params.array(j, key)) {
			if (!e.isJsonObject()) {
				throw Params.invalid("building." + key + " entries must be objects");
			}
			out.add(e.getAsJsonObject());
		}
		return out;
	}

	static Direction direction(String s, String where) {
		Direction d = Direction.byName(s);
		if (d == null || d.getAxis() == Direction.Axis.Y) {
			throw new RpcException(RpcException.INVALID_PARAMS, where + " must be north, south, east or west");
		}
		return d;
	}
}
