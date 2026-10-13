package dev.haochen.mcmcp.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.Services;
import dev.haochen.mcmcp.arch.BuildingPlan;
import dev.haochen.mcmcp.arch.BuildingRegistry;
import dev.haochen.mcmcp.arch.DesignMetrics;
import dev.haochen.mcmcp.arch.PlanCompiler;
import dev.haochen.mcmcp.arch.PlanCompiler.WorldCell;
import dev.haochen.mcmcp.arch.PlanOps;
import dev.haochen.mcmcp.arch.PlanValidator;
import dev.haochen.mcmcp.arch.SiteAnalyzer;
import dev.haochen.mcmcp.arch.ViewPlanner;
import dev.haochen.mcmcp.jobs.BuildingJob;
import dev.haochen.mcmcp.jobs.PlaceJob.Placement;
import dev.haochen.mcmcp.net.RpcDispatcher;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.view.ViewHook;
import dev.haochen.mcmcp.world.BlockList;
import dev.haochen.mcmcp.world.Blocks;
import dev.haochen.mcmcp.world.Guard;
import dev.haochen.mcmcp.world.Region;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Architecture: site analysis, building plans (validate, preview, place), and the revision loop
 * over registered buildings (inspect from planned viewpoints, revise components, verify).
 */
public final class ArchApi {
	private static final List<String> DEFAULT_VIEWS = List.of("front", "back", "left", "right", "elevated", "interior");
	/** States that players change by using a block; they do not count as deviations. */
	private static final List<Property<?>> TRANSIENT = List.of(BlockStateProperties.OPEN, BlockStateProperties.POWERED,
			BlockStateProperties.OCCUPIED, BlockStateProperties.LIT);

	private ArchApi() {
	}

	public static void register(RpcDispatcher d) {
		d.onMainThread("site.analyze", ArchApi::analyze);
		d.onMainThread("arch.validate", (server, params) -> {
			Compiled c = compile(server, params);
			JsonObject o = c.stats.deepCopy();
			o.add("components", c.compiler.components());
			return o;
		});
		d.onMainThread("arch.get", ArchApi::get);
		d.onMainThread("arch.revise", ArchApi::revise);
		d.async("arch.inspect", ArchApi::inspect);
	}

	// ---------------------------------------------------------------- site

	private static JsonObject analyze(MinecraftServer server, JsonObject p) {
		Services s = McMcpMod.services();
		ServerLevel level = WorldApi.level(server, p);
		BlockPos center;
		if (Params.has(p, "center")) {
			center = Params.pos(p, "center");
		} else {
			ServerPlayer player = server.getPlayerList().getPlayers().stream()
					.filter(pl -> !(pl instanceof dev.haochen.mcmcp.agent.AgentPlayer)).findFirst().orElse(null);
			if (player == null) {
				throw Params.invalid("center is required when no player is online");
			}
			center = player.blockPosition();
		}
		int radius = Params.integer(p, "radius", 24);
		int width = 9, depth = 7;
		if (Params.has(p, "footprint")) {
			var fp = Params.array(p, "footprint");
			if (fp.size() != 2) {
				throw Params.invalid("footprint must be [width, depth]");
			}
			width = Params.integer(fp.get(0), "footprint[0]");
			depth = Params.integer(fp.get(1), "footprint[1]");
		}
		Direction prefer = Params.has(p, "facing") ? Direction.byName(Params.string(p, "facing")) : null;
		if (Params.has(p, "facing") && prefer == null) {
			throw Params.invalid("facing must be north, south, east or west");
		}
		SiteAnalyzer.Request request = new SiteAnalyzer.Request(center, radius, width, depth,
				Params.integer(p, "margin", 2), prefer, Params.integer(p, "top", 3));
		SiteAnalyzer.validate(request);
		Guard.checkChunks(level, Region.of(center.offset(-radius, 0, -radius), center.offset(radius, 0, radius)),
				Params.bool(p, "loadChunks", false));
		return SiteAnalyzer.analyze(level, s.guard(), request);
	}

	// ---------------------------------------------------------------- compile / preview / place

	public record Compiled(JsonObject planJson, BuildingPlan plan, PlanCompiler compiler, JsonObject validation, BlockList blocks,
						   JsonObject stats, ServerLevel level, Long2ObjectLinkedOpenHashMap<WorldCell> frozenCells) {
		Plan toPlan(boolean loadChunks) {
			return new Plan(level, blocks, "building", "build " + plan.name, loadChunks, stats);
		}

		Long2ObjectLinkedOpenHashMap<WorldCell> worldCells() {
			return frozenCells != null ? frozenCells : compiler.worldCells();
		}

		boolean valid() {
			return validation.get("ok").getAsBoolean();
		}
	}

	/** Parses, compiles and validates {@code params.building}. Server thread. */
	public static Compiled compile(MinecraftServer server, JsonObject params) {
		return compile(server, params, Params.object(params, "building"));
	}

	static Compiled compile(MinecraftServer server, JsonObject params, JsonObject planJson) {
		return compile(server, params, planJson, java.util.Set.of());
	}

	/** {@code own}: positions the building already occupies (revisions), not counted as collisions. */
	static Compiled compile(MinecraftServer server, JsonObject params, JsonObject planJson, java.util.Set<Long> own) {
		return compile(server, params, planJson, own, Long2ObjectMaps.emptyMap());
	}

	static Compiled compile(MinecraftServer server, JsonObject params, JsonObject planJson, java.util.Set<Long> own,
			Long2ObjectMap<BlockState> originals) {
		Services s = McMcpMod.services();
		ServerLevel level = WorldApi.level(server, params);
		BuildingPlan plan = BuildingPlan.parse(planJson, s.guard());
		int reach = Math.max(plan.width, plan.depth) + plan.terrainMargin + 8;
		Guard.checkChunks(level, Region.of(plan.origin.offset(-reach, 0, -reach), plan.origin.offset(reach, 0, reach)),
				Params.bool(params, "loadChunks", false));
		PlanCompiler compiler = PlanCompiler.compile(plan, level, originals);
		JsonObject validation = PlanValidator.validate(compiler, s.guard(), own);
		BlockList blocks = compiler.toBlockList();
		JsonObject stats = blocks.stats(12);
		stats.addProperty("building", plan.name);
		stats.addProperty("facing", plan.facing.getName());
		stats.add("origin", Json.blockPos(plan.origin));
		stats.addProperty("floors", plan.floors.size());
		JsonObject terrain = new JsonObject();
		terrain.addProperty("foundationMaxDepth", compiler.foundationDepth());
		stats.add("terrain", terrain);
		stats.add("validation", validation);
		return new Compiled(planJson, plan, compiler, validation, blocks, stats, level, null);
	}

	/** A {@link Plan} for dry runs and the generic blueprint path (no registration). */
	public static Plan plan(MinecraftServer server, JsonObject params, boolean forPlacement) {
		Compiled c = compile(server, params);
		if (forPlacement) {
			requireValid(c, params);
		}
		return c.toPlan(Params.bool(params, "loadChunks", false));
	}

	private static void requireValid(Compiled c, JsonObject params) {
		if (!c.valid() && !Params.bool(params, "ignoreValidation", false)) {
			throw new RpcException(RpcException.BUILDING_INVALID, "the building plan has validation errors; fix them (or preview it, "
					+ "or pass ignoreValidation: true)", c.validation);
		}
	}

	/** Preview a plan and register the building (a new preview of the same name replaces the old one). */
	static JsonObject preview(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		Compiled c = compileRegistered(server, params);
		BuildingRegistry.Entry old = s.buildings().find(BuildingRegistry.nameOf(c.planJson));
		Plan plan = previewPlan(c, old, Params.bool(params, "loadChunks", false));
		plan.validate(s);
		if (old != null && old.previewId >= 0 && s.previews().active(old.previewId)) {
			s.previews().discard(old.previewId);
		}
		JsonObject out = s.previews().create(s, plan);
		BuildingRegistry.Entry e = s.buildings().update(c.planJson, c.level, c.worldCells());
		e.previewId = out.get("previewId").getAsInt();
		out.addProperty("building", e.name);
		return out;
	}

	/** Place a plan for real and register it (remembering what it replaces). */
	static JsonObject place(MinecraftServer server, JsonObject params) {
		Services s = McMcpMod.services();
		Compiled c = compileRegistered(server, params);
		requireValid(c, params);
		return submitBuilding(s, c, Params.bool(params, "loadChunks", false));
	}

	private static Compiled compileRegistered(MinecraftServer server, JsonObject params) {
		JsonObject json = Params.object(params, "building");
		BuildingRegistry.Entry e = McMcpMod.services().buildings().find(BuildingRegistry.nameOf(json));
		if (e == null) return compile(server, params, json);
		e.requireIdle();
		ServerLevel level = WorldApi.level(server, params);
		if ((e.built || e.incomplete || !e.originals.isEmpty()) && e.level != level) throw Params.invalid("a world-bound building cannot change dimension; choose another name");
		return compile(server, params, json, occupied(e), e.originals);
	}

	private static java.util.Set<Long> occupied(BuildingRegistry.Entry e) {
		if (!e.incomplete) return e.applied != null ? e.applied.cells().keySet() : java.util.Set.of();
		var owned = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		if (e.compiled != null) for (long key : e.compiled.keySet()) if (e.originals.containsKey(key)) owned.add(key);
		if (e.applied != null) for (long key : e.applied.cells().keySet()) if (e.originals.containsKey(key)) owned.add(key);
		return owned;
	}

	/** Full display includes restoration of positions removed from an applied building. */
	private static Plan previewPlan(Compiled c, BuildingRegistry.Entry e, boolean loadChunks) {
		BlockList blocks = new BlockList();
		for (var it = c.blocks.placements(); it.hasNext();) { var p = it.next(); blocks.put(p.pos(), p.state()); }
		if (e != null && e.applied != null && e.applied.cells() != null) {
			for (long pos : e.applied.cells().keySet()) if (!c.worldCells().containsKey(pos) && (!e.incomplete || e.originals.containsKey(pos))) blocks.put(BlockPos.of(pos), e.original(BlockPos.of(pos)).state());
		}
		return new Plan(c.level, blocks, "building", "build " + c.plan.name, loadChunks, c.stats);
	}

	/** Submit only the applied-to-desired delta; snapshots are captured by BuildingJob when it writes. */
	private static JsonObject submitBuilding(Services s, Compiled c, boolean loadChunks) {
		String name = BuildingRegistry.nameOf(c.planJson);
		BuildingRegistry.Entry e = s.buildings().find(name);
		if (e != null) e.requireIdle();
		BlockList list = new BlockList();
		var restores = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<dev.haochen.mcmcp.history.BlockSnapshot>();
		if (e == null || e.applied == null || e.incomplete) {
			for (var it = c.blocks.placements(); it.hasNext();) { var p = it.next(); list.put(p.pos(), p.state()); }
		} else {
			BuildingRegistry.diff(e.applied.cells(), c.worldCells()).set().forEach(list::put);
		}
		if (e != null && e.applied != null) for (long key : e.applied.cells().keySet()) {
			if (!c.worldCells().containsKey(key) && (!e.incomplete || e.originals.containsKey(key))) {
				BlockPos pos = BlockPos.of(key); var original = e.original(pos);
				list.put(pos, original.state()); restores.put(key, original);
			}
		}
		if (list.size() == 0) {
			e = s.buildings().update(c.planJson, c.level, c.worldCells());
			e.applied = e.snapshot(); s.buildings().save(e);
			JsonObject out = new JsonObject(); out.addProperty("state", "done"); out.addProperty("changed", 0); out.addProperty("building", name); return out;
		}
		Plan plan = new Plan(c.level, list, "building", "build/revise " + name, loadChunks, list.stats(5));
		plan.validate(s);
		if (e == null) e = s.buildings().update(c.planJson, c.level, c.worldCells());
		var it = list.placements();
		java.util.Iterator<Placement> source = new java.util.Iterator<>() {
			public boolean hasNext() { return it.hasNext(); }
			public Placement next() { var p = it.next(); var original = restores.get(p.pos().asLong()); return original == null ? p : new Placement(p.pos(), original); }
		};
		BuildingJob job = new BuildingJob(s, e, plan, source);
		s.buildings().update(c.planJson, c.level, c.worldCells());
		s.jobs().submit(job); e.pendingJobId = job.id();
		if (e.previewId >= 0 && s.previews().active(e.previewId)) s.previews().discard(e.previewId);
		JsonObject out = job.toJson(); out.addProperty("building", name);
		if (McMcpMod.server().isPaused()) out.addProperty("warning", BuildApi.PAUSED_WARNING);
		return out;
	}

	static JsonObject confirm(MinecraftServer server, JsonObject params, BuildingRegistry.Entry e) {
		e.requireIdle();
		Plan preview = McMcpMod.services().previews().plan(e.previewId);
		if (preview.stats().has("validation") && !preview.stats().getAsJsonObject("validation").get("ok").getAsBoolean()
				&& !Params.bool(params, "ignoreValidation", false)) throw new RpcException(RpcException.BUILDING_INVALID,
				"the building preview has validation errors; revise it before confirming (or pass ignoreValidation: true)", preview.stats().get("validation"));
		preview.validate(McMcpMod.services());
		JsonObject cp = new JsonObject(); cp.addProperty("loadChunks", true); cp.addProperty("dimension", e.level.dimension().location().toString());
		Compiled c = compile(server, cp, e.plan, occupied(e), e.originals);
		requireValid(c, params);
		// Revalidate current conditions, but never silently enlarge the approved preview's terrain work.
		BlockList frozen = new BlockList();
		for (var cell : e.compiled.long2ObjectEntrySet()) frozen.put(BlockPos.of(cell.getLongKey()), cell.getValue().state());
		c = new Compiled(e.plan, c.plan, c.compiler, c.validation, frozen, preview.stats(), c.level, e.compiled);
		JsonObject out = submitBuilding(McMcpMod.services(), c, true);
		if (e.previewId >= 0 && McMcpMod.services().previews().active(e.previewId)) McMcpMod.services().previews().discard(e.previewId);
		return out;
	}

	// ---------------------------------------------------------------- registry

	private static JsonObject get(MinecraftServer server, JsonObject p) {
		Services s = McMcpMod.services();
		JsonObject o = new JsonObject();
		if (!Params.has(p, "name")) {
			o.add("buildings", s.buildings().list());
			return o;
		}
		BuildingRegistry.Entry e = s.buildings().get(Params.string(p, "name"));
		o.add("status", e.status());
		o.add("building", e.plan);
		return o;
	}

	/**
	 * Applies component edits ({@code ops}) or a whole new plan ({@code building}) to a registered
	 * building and changes only the blocks that differ: in its preview, or (with {@code confirm})
	 * in the world as one undoable change.
	 */
	private static JsonObject revise(MinecraftServer server, JsonObject p) {
		Services s = McMcpMod.services();
		BuildingRegistry.Entry e = s.buildings().get(Params.string(p, "name"));
		e.requireIdle();
		if (e.incomplete) throw Params.invalid("building is incomplete; undo it or place the full plan again before revising");
		int rounds = Params.bool(p, "userApproved", false) ? 0 : e.rounds;
		int limit = s.config().maxRevisionRounds;
		if (rounds >= limit) {
			throw new RpcException(RpcException.INVALID_PARAMS, "revision limit reached: " + e.rounds + " revisions of " + e.name
					+ " since the user last approved (maxRevisionRounds = " + limit + "). Show the user the current state and ask; "
					+ "if they want more changes, call again with userApproved: true");
		}
		JsonObject newPlan;
		if (Params.has(p, "building")) {
			newPlan = Params.object(p, "building").deepCopy();
		} else {
			newPlan = PlanOps.apply(e.plan, Params.array(p, "ops"));
		}
		if (!BuildingRegistry.nameOf(newPlan).equals(e.name)) {
			throw Params.invalid("building.name must stay \"" + e.name + "\"");
		}
		JsonObject compileParams = new JsonObject();
		compileParams.addProperty("loadChunks", true);
		if (e.level != null) {
			compileParams.addProperty("dimension", e.level.dimension().location().toString());
		}
		Long2ObjectLinkedOpenHashMap<WorldCell> before = e.compiled != null ? e.compiled
				: compile(server, compileParams, e.plan).worldCells();
		Compiled now = compile(server, compileParams, newPlan, e.applied != null ? e.applied.cells().keySet() : java.util.Set.of(),
				e.built ? e.originals : Long2ObjectMaps.emptyMap());
		BuildingRegistry.Diff diff = BuildingRegistry.diff(before, now.worldCells());

		JsonObject out = new JsonObject();
		out.addProperty("building", e.name);
		out.add("diff", diff.toJson());
		out.add("validation", now.validation);
		String applied;
		if (e.previewId >= 0 && s.previews().active(e.previewId)) {
			s.previews().update(s, e.previewId, previewPlan(now, e, true));
			applied = "preview";
		} else if (e.built) {
			if (!Params.bool(p, "confirm", false)) {
				out.addProperty("applied", "nothing");
				out.addProperty("note", "this building is built; applying changes " + (diff.set().size() + diff.removed().size())
						+ " blocks in the world. Ask the user, then call again with confirm: true (or preview first).");
				return out;
			}
			requireValid(now, p);
			JsonObject job = submitBuilding(s, now, true);
			if (job.has("jobId")) out.add("job", job);
			applied = "world";
		} else {
			applied = "plan";
		}
		s.buildings().update(newPlan, now.level, now.worldCells());
		e.revision++;
		e.rounds = rounds + 1;
		if (e.pendingJobId < 0 && e.built && e.previewId < 0) e.applied = e.snapshot();
		s.buildings().save(e);
		out.addProperty("applied", applied);
		out.addProperty("revision", e.revision);
		out.addProperty("roundsLeft", Math.max(0, limit - e.rounds));
		return out;
	}

	// ---------------------------------------------------------------- inspection

	/** Validation, design metrics, requirements, world verification and pictures from planned viewpoints. */
	private static CompletableFuture<JsonElement> inspect(MinecraftServer server, JsonObject p) {
		String name = Params.string(p, "name");
		List<String> views = new ArrayList<>();
		if (Params.has(p, "views")) {
			Params.array(p, "views").forEach(v -> views.add(v.getAsString()));
		} else {
			views.addAll(DEFAULT_VIEWS);
		}
		int width = Params.integer(p, "width", 640), height = Params.integer(p, "height", 400), fov = Params.integer(p, "fov", 70);
		if (width < 16 || height < 16 || width > 1920 || height > 1080 || fov < 30 || fov > 110) {
			throw Params.invalid("width/height 16-1920 x 16-1080, fov 30-110");
		}
		boolean images = Params.bool(p, "images", true);
		boolean daylight = Params.bool(p, "daylight", true);
		return server.submit(() -> {
			Services s = McMcpMod.services();
			BuildingRegistry.Entry e = s.buildings().get(name);
			JsonObject cp = new JsonObject();
			cp.addProperty("loadChunks", true);
			if (e.level != null) {
				cp.addProperty("dimension", e.level.dimension().location().toString());
			}
			Compiled c = compile(server, cp, e.plan, occupied(e),
					e.built ? e.originals : Long2ObjectMaps.emptyMap());
			JsonObject out = new JsonObject();
			out.add("status", e.status());
			out.add("validation", c.validation);
			out.add("design", DesignMetrics.measure(c.compiler.grid(), e.plan));
			if (e.built || e.incomplete) {
				out.add("verification", verify(c.level, e.incomplete ? e.compiled : e.applied != null ? e.applied.cells() : e.compiled));
			}
			List<ViewPlanner.View> planned = ViewPlanner.plan(c.compiler.grid(), c.worldCells(), views, views.contains("interior") ? Math.max(fov, 85) : fov);
			return new Object[]{out, planned};
		}).thenCompose(arr -> {
			JsonObject out = (JsonObject) arr[0];
			@SuppressWarnings("unchecked")
			List<ViewPlanner.View> planned = (List<ViewPlanner.View>) arr[1];
			ViewHook hook = McMcpMod.viewHook();
			if (!images || hook == null) {
				if (hook == null) {
					out.addProperty("note", "no pictures: screenshots need the game client (single-player)");
				}
				JsonArray v = new JsonArray();
				planned.forEach(view -> v.add(ViewPlanner.describe(view)));
				out.add("views", v);
				return CompletableFuture.completedFuture((JsonElement) out);
			}
			List<ViewHook.Shot> shots = planned.stream().map(ViewPlanner.View::shot).toList();
			int captureFov = views.contains("interior") ? Math.max(fov, 85) : fov;
			return hook.capture(shots, width, height, captureFov, daylight).thenApply(pngs -> {
				JsonArray shotsJson = new JsonArray();
				for (int i = 0; i < planned.size(); i++) {
					JsonObject o = ViewPlanner.describe(planned.get(i));
					ViewHook.Shot sh = planned.get(i).shot();
					o.addProperty("label", sh.label());
					JsonArray pos = new JsonArray();
					pos.add(Math.round(sh.x() * 10) / 10.0);
					pos.add(Math.round(sh.y() * 10) / 10.0);
					pos.add(Math.round(sh.z() * 10) / 10.0);
					o.add("pos", pos);
					o.addProperty("png", Base64.getEncoder().encodeToString(pngs.get(i)));
					shotsJson.add(o);
				}
				out.addProperty("width", width);
				out.addProperty("height", height);
				out.addProperty("fov", captureFov);
				out.add("shots", shotsJson);
				return (JsonElement) out;
			});
		});
	}

	/** Compares the world with the plan, per component (ignoring doors being open etc.). */
	private static JsonObject verify(ServerLevel level, Long2ObjectLinkedOpenHashMap<WorldCell> cells) {
		Map<String, Integer> wrong = new LinkedHashMap<>();
		JsonArray samples = new JsonArray();
		int total = 0, bad = 0;
		for (var e : cells.long2ObjectEntrySet()) {
			total++;
			BlockPos pos = BlockPos.of(e.getLongKey());
			BlockState want = e.getValue().state(), have = level.getBlockState(pos);
			// Panes, fences, walls etc. connect to whatever is next to them in the world.
			if (normalize(want) == normalize(have)
					|| normalize(net.minecraft.world.level.block.Block.updateFromNeighbourShapes(want, level, pos)) == normalize(have)) {
				continue;
			}
			bad++;
			wrong.merge(e.getValue().component(), 1, Integer::sum);
			if (samples.size() < 10) {
				JsonObject o = new JsonObject();
				o.add("pos", Json.blockPos(pos));
				o.addProperty("component", e.getValue().component());
				o.addProperty("expected", Blocks.toString(want));
				o.addProperty("found", Blocks.toString(have));
				samples.add(o);
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("blocks", total);
		o.addProperty("matching", total - bad);
		JsonObject comps = new JsonObject();
		wrong.forEach(comps::addProperty);
		o.add("mismatchesByComponent", comps);
		o.add("samples", samples);
		return o;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static BlockState normalize(BlockState s) {
		for (Property prop : TRANSIENT) {
			if (s.hasProperty(prop)) {
				s = s.setValue(prop, (Comparable) s.getBlock().defaultBlockState().getValue(prop));
			}
		}
		return s;
	}
}
