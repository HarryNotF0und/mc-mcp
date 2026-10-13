package dev.haochen.mcmcp.arch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.api.Params;
import dev.haochen.mcmcp.history.BlockSnapshot;
import dev.haochen.mcmcp.world.Blocks;
import dev.haochen.mcmcp.world.Guard;
import dev.haochen.mcmcp.arch.PlanCompiler.WorldCell;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Buildings the AI has previewed or built, by plan name, so follow-up requests can change them
 * component by component. Keeps the current plan (also saved to {@code mcmcp/buildings/<name>.json}),
 * its compiled blocks, the active preview, and, once built, the world blocks it replaced
 * (so a revision that drops a part can put back what was there). Server thread.
 */
public final class BuildingRegistry {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
	private static final String FORMAT = "mcmcp.registry/1";

	/** Immutable references to one design/baseline; compiled maps are never mutated after publication. */
	public record State(JsonObject plan, ServerLevel level, Long2ObjectLinkedOpenHashMap<WorldCell> cells,
			boolean built, boolean incomplete, String dimension) { }

	public static final class Entry {
		public final String name;
		public JsonObject plan;
		public ServerLevel level;
		public Long2ObjectLinkedOpenHashMap<WorldCell> compiled;
		/** World blocks before the building was first placed, for every position it has touched. */
		public final Long2ObjectOpenHashMap<BlockState> originals = new Long2ObjectOpenHashMap<>();
		public final Long2ObjectOpenHashMap<CompoundTag> originalNbt = new Long2ObjectOpenHashMap<>();
		public State applied;
		public int previewId = -1;
		public int pendingJobId = -1;
		public boolean built;
		public boolean incomplete;
		public int revision;
		/** Revisions since the user last approved continuing. */
		public int rounds;

		Entry(String name) {
			this.name = name;
		}

		public State snapshot() { return new State(plan, level, compiled, built, incomplete, level != null ? level.dimension().location().toString() : null); }
		public void restore(State state) {
			plan = state.plan();
			// Originals are dimension-bound even after undoing the first placement of an imported design.
			if (state.level() != null || originals.isEmpty()) level = state.level();
			compiled = state.cells();
			built = state.built(); incomplete = state.incomplete();
			applied = built || incomplete ? state : null;
		}
		public void requireIdle() {
			if (pendingJobId >= 0) throw Params.invalid("building " + name + " is busy with job " + pendingJobId + "; wait or cancel it first");
		}
		public BlockSnapshot original(BlockPos pos) {
			BlockState state = originals.get(pos.asLong());
			return new BlockSnapshot(state != null ? state : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), originalNbt.get(pos.asLong()));
		}

		public JsonObject status() {
			JsonObject o = new JsonObject();
			o.addProperty("name", name);
			o.addProperty("state", pendingJobId >= 0 ? "building" : previewId >= 0 ? "previewing" : incomplete ? "incomplete" : built ? "built" : "planned");
			if (pendingJobId >= 0) o.addProperty("jobId", pendingJobId);
			o.addProperty("built", built);
			o.addProperty("incomplete", incomplete);
			if (level != null) o.addProperty("dimension", level.dimension().location().toString());
			if (previewId >= 0) {
				o.addProperty("previewId", previewId);
			}
			o.addProperty("revision", revision);
			o.addProperty("roundsSinceApproval", rounds);
			if (compiled != null) {
				o.addProperty("blocks", compiled.size());
			}
			return o;
		}
	}

	/** What changed between two compiled versions. */
	public record Diff(Map<BlockPos, BlockState> set, Set<BlockPos> removed, Set<String> components, int unchanged) {
		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("changed", set.size());
			o.addProperty("removed", removed.size());
			o.addProperty("unchanged", unchanged);
			JsonArray c = new JsonArray();
			components.forEach(c::add);
			o.add("components", c);
			return o;
		}
	}

	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final Path dir;
	private final Path legacyDir;
	private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "mcmcp-buildings"); t.setDaemon(true); return t;
	});
	private record Stored(State current, State applied, int revision, int rounds,
			Long2ObjectOpenHashMap<BlockState> originals, Long2ObjectOpenHashMap<CompoundTag> nbt) { }
	private final ConcurrentHashMap<String, Stored> pendingSaves = new ConcurrentHashMap<>();
	private final AtomicBoolean writing = new AtomicBoolean();
	private boolean discovered;

	public BuildingRegistry(Path dir) {
		this(dir, null);
	}

	public BuildingRegistry(Path dir, Path legacyDir) {
		this.dir = dir;
		this.legacyDir = legacyDir;
	}

	public static String nameOf(JsonObject plan) {
		String name = Params.string(plan, "name", "building");
		if (!NAME.matcher(name).matches()) {
			throw Params.invalid("building.name must be 1-64 letters, digits, _ . - (it identifies the building for later changes)");
		}
		return name;
	}

	/** The entry for a name, loading a saved plan from disk if this session has not seen it. */
	public Entry get(String name) {
		Entry e = entries.get(name);
		if (e != null) {
			return e;
		}
		Path file = dir.resolve(name + ".json");
		if (NAME.matcher(name).matches() && !Files.exists(file) && legacyDir != null) file = legacyDir.resolve(name + ".json");
		if (NAME.matcher(name).matches() && Files.exists(file)) {
			try {
				e = new Entry(name);
				JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				if (Params.string(json, "format", "").equals(FORMAT)) {
					e.restore(readState(Params.object(json, "current")));
					if (Params.has(json, "applied")) e.applied = readState(Params.object(json, "applied"));
					e.revision = Params.integer(json, "revision", 0);
					e.rounds = Params.integer(json, "rounds", 0);
					for (var row : Params.array(json, "originals")) {
						var a = row.getAsJsonArray(); long pos = a.get(0).getAsLong();
						e.originals.put(pos, Blocks.parse(a.get(1).getAsString()));
						if (a.size() > 2) e.originalNbt.put(pos, TagParser.parseTag(a.get(2).getAsString()));
					}
					// Previews are transient. A complete applied building wins after restart.
					if (e.applied != null && !e.incomplete) e.restore(e.applied);
				} else {
					e.plan = json; // Old files are unbound designs, never guessed to be built.
				}
				if (!nameOf(e.plan).equals(name)) throw Params.invalid("saved building name does not match its filename");
				entries.put(name, e);
				return e;
			} catch (Exception ex) {
				throw Params.invalid("could not read saved building " + name + ": " + ex.getMessage());
			}
		}
		throw Params.invalid("no building named \"" + name + "\"; preview or place a plan with that name first");
	}

	public @Nullable Entry find(String name) {
		Entry e = entries.get(name);
		if (e == null && NAME.matcher(name).matches() && (Files.isRegularFile(dir.resolve(name + ".json"))
				|| legacyDir != null && Files.isRegularFile(legacyDir.resolve(name + ".json")))) return get(name);
		return e;
	}

	public @Nullable Entry byPreview(int previewId) {
		for (Entry e : entries.values()) {
			if (e.previewId == previewId) {
				return e;
			}
		}
		return null;
	}

	/** All preview removal paths (including automatic eviction) clear the registry handle. */
	public void previewRemoved(int previewId) {
		Entry e = byPreview(previewId);
		if (e != null) {
			e.previewId = -1;
			if (e.pendingJobId < 0 && e.applied != null) e.restore(e.applied);
			save(e);
		}
	}

	public JsonArray list() {
		if (!discovered) {
			for (Path folder : legacyDir == null ? java.util.List.of(dir) : java.util.List.of(dir, legacyDir)) {
				if (!Files.isDirectory(folder)) continue;
				try (var paths = Files.list(folder)) {
					paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
						String name = p.getFileName().toString().replaceFirst("\\.json$", "");
						if (NAME.matcher(name).matches()) {
							try { get(name); } catch (RuntimeException ex) { McMcpMod.LOGGER.warn("Skipping saved building {}: {}", name, ex.getMessage()); }
						}
					});
				} catch (IOException ex) { McMcpMod.LOGGER.warn("Could not list buildings", ex); }
			}
			discovered = true;
		}
		JsonArray a = new JsonArray();
		entries.values().forEach(e -> a.add(e.status()));
		return a;
	}

	/** Records the latest plan and compiled blocks (after a preview, placement or revision). */
	public Entry update(JsonObject plan, ServerLevel level, Long2ObjectLinkedOpenHashMap<WorldCell> compiled) {
		String name = nameOf(plan);
		Entry e = entries.computeIfAbsent(name, Entry::new);
		e.plan = plan.deepCopy();
		e.level = level;
		e.compiled = compiled;
		save(e);
		return e;
	}

	/** Remembers what is in the world now at these positions, unless already remembered. */
	public static void captureOriginal(Entry e, BlockPos pos, BlockSnapshot before) {
		long p = pos.asLong();
		if (!e.originals.containsKey(p)) {
			e.originals.put(p, before.state());
			if (before.nbt() != null) e.originalNbt.put(p, before.nbt());
		}
	}

	public static Diff diff(Long2ObjectLinkedOpenHashMap<WorldCell> before, Long2ObjectLinkedOpenHashMap<WorldCell> after) {
		Map<BlockPos, BlockState> set = new LinkedHashMap<>();
		Set<BlockPos> removed = new LinkedHashSet<>();
		Set<String> comps = new LinkedHashSet<>();
		int same = 0;
		for (var e : after.long2ObjectEntrySet()) {
			WorldCell old = before.get(e.getLongKey());
			if (old != null && old.state() == e.getValue().state()) {
				same++;
				continue;
			}
			set.put(BlockPos.of(e.getLongKey()), e.getValue().state());
			comps.add(e.getValue().component());
			if (old != null) {
				comps.add(old.component());
			}
		}
		for (var e : before.long2ObjectEntrySet()) {
			if (!after.containsKey(e.getLongKey())) {
				removed.add(BlockPos.of(e.getLongKey()));
				comps.add(e.getValue().component());
			}
		}
		return new Diff(set, removed, comps, same);
	}

	public void save(Entry e) {
		pendingSaves.put(e.name, new Stored(e.snapshot(), e.applied, e.revision, e.rounds, e.originals.clone(), e.originalNbt.clone()));
		scheduleWrites();
	}

	private void scheduleWrites() {
		if (!writing.compareAndSet(false, true)) return;
		writer.execute(() -> {
			try {
				while (!pendingSaves.isEmpty()) {
					for (var item : pendingSaves.entrySet()) {
						if (!pendingSaves.remove(item.getKey(), item.getValue())) continue;
						try {
							Files.createDirectories(dir);
							Map<BlockState, String> states = new java.util.HashMap<>();
							JsonObject json = new JsonObject(); Stored data = item.getValue();
							json.addProperty("format", FORMAT); json.add("current", writeState(data.current, states));
							if (data.applied != null && (data.applied.plan != data.current.plan || data.applied.cells != data.current.cells
									|| data.applied.built != data.current.built || data.applied.incomplete != data.current.incomplete)) {
								json.add("applied", writeState(data.applied, states));
							}
							json.addProperty("revision", data.revision); json.addProperty("rounds", data.rounds);
							JsonArray originals = new JsonArray();
							for (var original : data.originals.long2ObjectEntrySet()) {
								JsonArray row = new JsonArray(); row.add(original.getLongKey()); row.add(states.computeIfAbsent(original.getValue(), Blocks::toString));
								CompoundTag nbt = data.nbt.get(original.getLongKey()); if (nbt != null) row.add(nbt.toString());
								originals.add(row);
							}
							json.add("originals", originals);
							Path tmp = dir.resolve(item.getKey() + ".json.tmp"), target = dir.resolve(item.getKey() + ".json");
							Files.writeString(tmp, json.toString(), StandardCharsets.UTF_8);
							try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
							catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
						} catch (Exception ex) { McMcpMod.LOGGER.warn("could not save building {}", item.getKey(), ex); }
					}
				}
			} finally { writing.set(false); if (!pendingSaves.isEmpty()) scheduleWrites(); }
		});
	}

	public void flush() {
		try {
			do { scheduleWritesIfNeeded(); writer.submit(() -> {}).get(); } while (writing.get() || !pendingSaves.isEmpty());
		}
		catch (Exception ex) { if (ex instanceof InterruptedException) Thread.currentThread().interrupt(); throw new IllegalStateException("could not flush buildings", ex); }
	}
	private void scheduleWritesIfNeeded() { if (!pendingSaves.isEmpty()) scheduleWrites(); }
	public void close() {
		entries.values().forEach(this::save);
		flush(); writer.shutdown();
	}

	private static JsonObject writeState(State state, Map<BlockState, String> states) {
		JsonObject json = new JsonObject(); json.add("plan", state.plan);
		if (state.dimension != null) json.addProperty("dimension", state.dimension);
		json.addProperty("built", state.built); json.addProperty("incomplete", state.incomplete);
		JsonArray cells = new JsonArray();
		if (state.cells != null) for (var cell : state.cells.long2ObjectEntrySet()) {
			JsonArray row = new JsonArray(); row.add(cell.getLongKey()); row.add(states.computeIfAbsent(cell.getValue().state(), Blocks::toString)); row.add(cell.getValue().component()); cells.add(row);
		}
		json.add("cells", cells); return json;
	}

	private static State readState(JsonObject json) {
		ServerLevel level = Params.has(json, "dimension") ? Guard.level(McMcpMod.server(), Params.string(json, "dimension")) : null;
		var cells = new Long2ObjectLinkedOpenHashMap<WorldCell>();
		for (var row : Params.array(json, "cells")) {
			var a = row.getAsJsonArray(); cells.put(a.get(0).getAsLong(), new WorldCell(Blocks.parse(a.get(1).getAsString()), a.get(2).getAsString()));
		}
		return new State(Params.object(json, "plan"), level, cells.isEmpty() ? null : cells, Params.bool(json, "built", false), Params.bool(json, "incomplete", false), Params.string(json, "dimension", null));
	}
}
