package dev.haochen.mcmcp.arch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.api.Params;
import dev.haochen.mcmcp.arch.PlanCompiler.WorldCell;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Buildings the AI has previewed or built, by plan name, so follow-up requests can change them
 * component by component. Keeps the current plan (also saved to {@code mcmcp/buildings/<name>.json}),
 * its compiled blocks, the active preview, and, once built, the world blocks it replaced
 * (so a revision that drops a part can put back what was there). Server thread.
 */
public final class BuildingRegistry {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

	public static final class Entry {
		public final String name;
		public JsonObject plan;
		public ServerLevel level;
		public Long2ObjectLinkedOpenHashMap<WorldCell> compiled;
		/** World blocks before the building was first placed, for every position it has touched. */
		public final Long2ObjectOpenHashMap<BlockState> originals = new Long2ObjectOpenHashMap<>();
		public int previewId = -1;
		public boolean built;
		public int revision;
		/** Revisions since the user last approved continuing. */
		public int rounds;

		Entry(String name) {
			this.name = name;
		}

		public JsonObject status() {
			JsonObject o = new JsonObject();
			o.addProperty("name", name);
			o.addProperty("state", built ? "built" : previewId >= 0 ? "previewing" : "planned");
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

	public BuildingRegistry(Path dir) {
		this.dir = dir;
	}

	public static String nameOf(JsonObject plan) {
		String name = plan.has("name") ? plan.get("name").getAsString() : "building";
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
		if (NAME.matcher(name).matches() && Files.exists(file)) {
			try {
				e = new Entry(name);
				e.plan = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				entries.put(name, e);
				return e;
			} catch (IOException | RuntimeException ex) {
				throw Params.invalid("could not read saved building " + name + ": " + ex.getMessage());
			}
		}
		throw Params.invalid("no building named \"" + name + "\"; preview or place a plan with that name first");
	}

	public @Nullable Entry find(String name) {
		return entries.get(name);
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
		}
	}

	public JsonArray list() {
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
	public static void captureOriginals(Entry e, ServerLevel level, Iterable<Long> positions) {
		for (long p : positions) {
			if (!e.originals.containsKey(p)) {
				e.originals.put(p, level.getBlockState(BlockPos.of(p)));
			}
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

	private void save(Entry e) {
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(e.name + ".json"), e.plan.toString(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			McMcpMod.LOGGER.warn("could not save building {}", e.name, ex);
		}
	}
}
