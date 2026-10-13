package dev.haochen.mcmcp.script;

import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.BlockList;
import dev.haochen.mcmcp.world.Blocks;
import dev.haochen.mcmcp.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.SerializableCallable;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The global {@code b} object scripts build with. Coordinates are relative to {@code b.origin}
 * and floored to integers. Documented in {@code docs/script-api.md}; keep the two in sync.
 */
final class BuilderObject extends ScriptableObject {
	static final int MAX_LOG_LINES = 200;
	private static final int MAX_LOG_LINE_LENGTH = 500;

	private final BlockPos origin;
	private final Guard guard;
	private final WorldSnapshot world;
	private final int maxBlocks;
	private final BlockList blocks = new BlockList();
	private final Map<String, BlockState> parsed = new HashMap<>();
	private final List<String> logs = new ArrayList<>();
	private int droppedLogs;

	BuilderObject(Scriptable scope, BlockPos origin, Guard guard, WorldSnapshot world, int maxBlocks) {
		this.origin = origin;
		this.guard = guard;
		this.world = world;
		this.maxBlocks = maxBlocks;
		setParentScope(scope);

		Scriptable o = Context.getCurrentContext().newObject(scope);
		o.put("x", o, origin.getX());
		o.put("y", o, origin.getY());
		o.put("z", o, origin.getZ());
		((ScriptableObject) o).sealObject();
		defineProperty("origin", o, READONLY | PERMANENT);

		fn(scope, "set", 4, args -> {
			need(args, 4, "b.set(x, y, z, block)");
			set(i(args[0]), i(args[1]), i(args[2]), block(args[3]));
			return Undefined.instance;
		});
		fn(scope, "get", 3, args -> {
			need(args, 3, "b.get(x, y, z)");
			return get(i(args[0]), i(args[1]), i(args[2]));
		});
		fn(scope, "fill", 8, args -> {
			need(args, 7, "b.fill(x1, y1, z1, x2, y2, z2, block, mode?)");
			String mode = args.length > 7 && args[7] != Undefined.instance ? Context.toString(args[7]) : "solid";
			fill(i(args[0]), i(args[1]), i(args[2]), i(args[3]), i(args[4]), i(args[5]), block(args[6]), mode);
			return Undefined.instance;
		});
		fn(scope, "line", 7, args -> {
			need(args, 7, "b.line(x1, y1, z1, x2, y2, z2, block)");
			line(i(args[0]), i(args[1]), i(args[2]), i(args[3]), i(args[4]), i(args[5]), block(args[6]));
			return Undefined.instance;
		});
		fn(scope, "cylinder", 7, args -> {
			need(args, 6, "b.cylinder(x, y, z, radius, height, block, hollow?)");
			cylinder(i(args[0]), i(args[1]), i(args[2]), Context.toNumber(args[3]), i(args[4]), block(args[5]), flag(args, 6));
			return Undefined.instance;
		});
		fn(scope, "sphere", 6, args -> {
			need(args, 5, "b.sphere(x, y, z, radius, block, hollow?)");
			sphere(i(args[0]), i(args[1]), i(args[2]), Context.toNumber(args[3]), block(args[4]), flag(args, 5));
			return Undefined.instance;
		});
		fn(scope, "log", 1, args -> {
			StringBuilder sb = new StringBuilder();
			for (Object a : args) {
				if (!sb.isEmpty()) {
					sb.append(' ');
				}
				sb.append(Context.toString(a));
			}
			log(sb.toString());
			return Undefined.instance;
		});
	}

	@Override
	public String getClassName() {
		return "Builder";
	}

	BlockList blocks() {
		return blocks;
	}

	List<String> logs() {
		List<String> out = new ArrayList<>(logs);
		if (droppedLogs > 0) {
			out.add("... " + droppedLogs + " more log lines dropped");
		}
		return out;
	}

	// ---- operations (relative coordinates) ----

	private void set(int x, int y, int z, BlockState state) {
		blocks.put(origin.offset(x, y, z), state);
		if (blocks.size() > maxBlocks) {
			throw Context.reportRuntimeError("script exceeded the limit of " + maxBlocks + " blocks");
		}
	}

	private String get(int x, int y, int z) {
		BlockPos p = origin.offset(x, y, z);
		BlockState pending = blocks.get(p.asLong());
		if (pending != null) {
			return Blocks.toString(pending);
		}
		try {
			return Blocks.toString(world.get(p.getX(), p.getY(), p.getZ()));
		} catch (WorldSnapshot.ChunkNotLoaded e) {
			throw Context.reportRuntimeError(e.getMessage());
		}
	}

	private void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state, String mode) {
		boolean hollow = mode.equals("hollow"), outline = mode.equals("outline");
		if (!hollow && !outline && !mode.equals("solid")) {
			throw Context.reportRuntimeError("fill mode must be \"solid\", \"hollow\" or \"outline\", got \"" + mode + "\"");
		}
		int ax = Math.min(x1, x2), bx = Math.max(x1, x2);
		int ay = Math.min(y1, y2), by = Math.max(y1, y2);
		int az = Math.min(z1, z2), bz = Math.max(z1, z2);
		checkVolume((long) (bx - ax + 1) * (by - ay + 1) * (bz - az + 1));
		BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		for (int y = ay; y <= by; y++) {
			for (int z = az; z <= bz; z++) {
				for (int x = ax; x <= bx; x++) {
					boolean shell = x == ax || x == bx || y == ay || y == by || z == az || z == bz;
					if (shell || !outline) {
						set(x, y, z, shell || !hollow ? state : air);
					}
				}
			}
		}
	}

	private void line(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
		int steps = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
		checkVolume(steps + 1L);
		for (int s = 0; s <= steps; s++) {
			double t = steps == 0 ? 0 : (double) s / steps;
			set((int) Math.round(x1 + (x2 - x1) * t), (int) Math.round(y1 + (y2 - y1) * t), (int) Math.round(z1 + (z2 - z1) * t), state);
		}
	}

	/** Vertical cylinder: base centre (x, y, z), {@code height} layers upwards. Diameter is 2 * radius + 1 for whole radii. */
	private void cylinder(int x, int y, int z, double radius, int height, BlockState state, boolean hollow) {
		if (radius < 0 || height < 1) {
			throw Context.reportRuntimeError("cylinder needs radius >= 0 and height >= 1");
		}
		int r = (int) Math.ceil(radius);
		checkVolume((2L * r + 1) * (2L * r + 1) * height);
		double limit = (radius + 0.5) * (radius + 0.5);
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				if (!inside(dx, 0, dz, limit)) {
					continue;
				}
				if (hollow && inside(dx + 1, 0, dz, limit) && inside(dx - 1, 0, dz, limit)
						&& inside(dx, 0, dz + 1, limit) && inside(dx, 0, dz - 1, limit)) {
					continue;
				}
				for (int dy = 0; dy < height; dy++) {
					set(x + dx, y + dy, z + dz, state);
				}
			}
		}
	}

	/** Sphere centred on (x, y, z). Diameter is 2 * radius + 1 for whole radii. */
	private void sphere(int x, int y, int z, double radius, BlockState state, boolean hollow) {
		if (radius < 0) {
			throw Context.reportRuntimeError("sphere needs radius >= 0");
		}
		int r = (int) Math.ceil(radius);
		checkVolume((2L * r + 1) * (2L * r + 1) * (2L * r + 1));
		double limit = (radius + 0.5) * (radius + 0.5);
		for (int dy = -r; dy <= r; dy++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					if (!inside(dx, dy, dz, limit)) {
						continue;
					}
					if (hollow && inside(dx + 1, dy, dz, limit) && inside(dx - 1, dy, dz, limit)
							&& inside(dx, dy + 1, dz, limit) && inside(dx, dy - 1, dz, limit)
							&& inside(dx, dy, dz + 1, limit) && inside(dx, dy, dz - 1, limit)) {
						continue;
					}
					set(x + dx, y + dy, z + dz, state);
				}
			}
		}
	}

	private static boolean inside(int dx, int dy, int dz, double limit) {
		return (double) dx * dx + (double) dy * dy + (double) dz * dz < limit;
	}

	private void log(String line) {
		if (logs.size() >= MAX_LOG_LINES) {
			droppedLogs++;
			return;
		}
		logs.add(line.length() > MAX_LOG_LINE_LENGTH ? line.substring(0, MAX_LOG_LINE_LENGTH) + "..." : line);
	}

	// ---- argument helpers ----

	/** Rejects a single shape call that alone would exceed the block limit, before looping over it. */
	private void checkVolume(long volume) {
		if (volume > maxBlocks) {
			throw Context.reportRuntimeError("shape covers " + volume + " blocks, the limit is " + maxBlocks);
		}
	}

	private BlockState block(Object arg) {
		if (!(arg instanceof CharSequence)) {
			throw Context.reportRuntimeError("block must be a string like \"minecraft:stone\", got " + Context.toString(arg));
		}
		String text = arg.toString();
		BlockState state = parsed.get(text);
		if (state == null) {
			try {
				state = guard.block(text);
			} catch (RpcException e) {
				throw Context.reportRuntimeError(e.getMessage());
			}
			parsed.put(text, state);
		}
		return state;
	}

	private static int i(Object arg) {
		double d = Context.toNumber(arg);
		if (Double.isNaN(d) || Double.isInfinite(d) || Math.abs(d) > 30_000_000) {
			throw Context.reportRuntimeError("coordinate must be a finite number, got " + Context.toString(arg));
		}
		return (int) Math.floor(d);
	}

	private static boolean flag(Object[] args, int index) {
		return args.length > index && Context.toBoolean(args[index]);
	}

	private static void need(Object[] args, int count, String signature) {
		if (args.length < count) {
			throw Context.reportRuntimeError(signature + " needs at least " + count + " arguments, got " + args.length);
		}
	}

	private interface Body {
		Object call(Object[] args);
	}

	private void fn(Scriptable scope, String name, int arity, Body body) {
		SerializableCallable callable = (cx, s, thisObj, args) -> body.call(args);
		defineProperty(name, new LambdaFunction(scope, name, arity, callable), READONLY | PERMANENT);
	}
}
