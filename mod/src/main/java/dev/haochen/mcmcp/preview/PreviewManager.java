package dev.haochen.mcmcp.preview;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.Services;
import dev.haochen.mcmcp.api.Plan;
import dev.haochen.mcmcp.jobs.PlaceJob.Placement;
import dev.haochen.mcmcp.net.RpcException;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * "Fake block" previews: the planned blocks are sent to players as block update packets only,
 * the world is untouched. A client that reloads the chunks sees the real world again.
 * Server thread only.
 */
public final class PreviewManager {
	private static final int MAX_PREVIEWS = 5;

	private record Preview(int id, Plan plan) {
	}

	private final Map<Integer, Preview> previews = new LinkedHashMap<>();
	private final IntConsumer onRemoved;
	private int nextId = 1;

	public PreviewManager(IntConsumer onRemoved) {
		this.onRemoved = onRemoved;
	}

	/** Validates the plan like a real write, then shows it. Returns {@code {previewId, players, ...stats}}. */
	public JsonObject create(Services s, Plan plan) {
		plan.validate(s);
		if (previews.size() >= MAX_PREVIEWS) {
			Iterator<Preview> oldest = previews.values().iterator();
			Preview p = oldest.next();
			oldest.remove();
			onRemoved.accept(p.id());
			sendVisible(p.plan());
		}
		Preview preview = new Preview(nextId++, plan);
		previews.put(preview.id(), preview);
		int players = send(plan, true);
		JsonObject o = plan.stats().deepCopy();
		o.addProperty("previewId", preview.id());
		o.addProperty("players", players);
		return o;
	}

	/** Places the previewed blocks for real (one undoable job). */
	public JsonObject confirm(Services s, int id) {
		// Submit before removing: a failed safety check must leave the design available.
		JsonObject job = plan(id).submit(s);
		discard(id);
		return job;
	}

	/** Shows the real blocks again. */
	public void discard(int id) {
		sendVisible(take(id).plan());
	}

	private Preview take(int id) {
		Preview p = previews.remove(id);
		if (p == null) {
			throw new RpcException(RpcException.INVALID_PARAMS, "no active preview with id " + id);
		}
		onRemoved.accept(id);
		return p;
	}

	/**
	 * Sends one section update packet per affected chunk section to every player in the level,
	 * with either the planned states ({@code fake}) or the real ones. Returns the player count.
	 */
	private static int send(Plan plan, boolean fake) {
		return send(plan.level(), plan.blocks().placements(), fake);
	}

	/**
	 * Changes an active preview in place: only {@code changed} blocks are re-sent, and positions in
	 * {@code removed} (no longer part of the design) show the real world again.
	 */
	public void update(Services s, int id, Plan plan) {
		if (!previews.containsKey(id)) {
			throw new RpcException(RpcException.INVALID_PARAMS, "no active preview with id " + id);
		}
		plan.validate(s);
		if (previews.get(id).plan().level() != plan.level()) {
			throw new RpcException(RpcException.INVALID_PARAMS, "a preview cannot change dimension");
		}
		Plan before = previews.get(id).plan();
		Map<BlockPos, BlockState> changed = new LinkedHashMap<>();
		List<BlockPos> removed = new ArrayList<>();
		for (var it = plan.blocks().placements(); it.hasNext();) {
			Placement p = it.next(); if (before.blocks().get(p.pos().asLong()) != p.state()) changed.put(p.pos(), p.state());
		}
		for (var it = before.blocks().placements(); it.hasNext();) {
			Placement p = it.next(); if (plan.blocks().get(p.pos().asLong()) == null) removed.add(p.pos());
		}
		previews.put(id, new Preview(id, plan));
		List<Placement> fake = new ArrayList<>();
		changed.forEach((pos, state) -> fake.add(new Placement(pos, state)));
		sendVisible(plan.level(), fake.iterator());
		List<Placement> real = new ArrayList<>();
		removed.forEach(pos -> real.add(new Placement(pos, (BlockState) null)));
		sendVisible(plan.level(), real.iterator());
	}

	/** The plan behind an active preview. */
	public Plan plan(int id) {
		Preview p = previews.get(id);
		if (p == null) {
			throw new RpcException(RpcException.INVALID_PARAMS, "no active preview with id " + id);
		}
		return p.plan();
	}

	public boolean active(int id) {
		return previews.containsKey(id);
	}

	/**
	 * Re-sends what players should see in {@code region} (the real blocks, or an active preview's) to
	 * clear stale client blocks, e.g. leftovers of an old preview. Returns the number of players.
	 */
	public int refresh(ServerLevel level, dev.haochen.mcmcp.world.Region region) {
		List<Placement> all = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(region.min(), region.max())) {
			all.add(new Placement(pos.immutable(), (BlockState) null));
		}
		sendVisible(level, all.iterator());
		return level.players().size();
	}

	/** Resolve affected cells against the newest remaining preview before restoring the display. */
	private void sendVisible(Plan plan) { sendVisible(plan.level(), plan.blocks().placements()); }
	private void sendVisible(ServerLevel level, Iterator<Placement> positions) {
		if (level.players().isEmpty()) return;
		List<Placement> visible = new ArrayList<>();
		while (positions.hasNext()) {
			BlockPos pos = positions.next().pos();
			visible.add(new Placement(pos, visibleState(level, pos)));
		}
		send(level, visible.iterator(), true);
	}

	/** Server-thread view of the composed preview display; newest overlapping preview wins. */
	public BlockState visibleState(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		for (Preview p : previews.values()) {
			if (p.plan().level() != level) continue;
			BlockState fake = p.plan().blocks().get(pos.asLong());
			if (fake != null) state = fake;
		}
		return state;
	}

	private static int send(ServerLevel level, Iterator<Placement> placements, boolean fake) {
		List<ServerPlayer> players = level.players();
		if (players.isEmpty()) return 0;
		Long2ObjectOpenHashMap<List<Placement>> bySection = new Long2ObjectOpenHashMap<>();
		for (Iterator<Placement> it = placements; it.hasNext(); ) {
			Placement p = it.next();
			if (level.isOutsideBuildHeight(p.pos())) {
				continue;
			}
			bySection.computeIfAbsent(SectionPos.asLong(p.pos()), k -> new ArrayList<>()).add(p);
		}
		Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
		for (var e : bySection.long2ObjectEntrySet()) {
			SectionPos sectionPos = SectionPos.of(e.getLongKey());
			LevelChunk chunk = level.getChunk(sectionPos.x(), sectionPos.z());
			LevelChunkSection real = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionPos.y()));
			// The packet only reads the listed positions, so a fresh section holding just the planned
			// states is enough. (Writing into a copy of the real palette would resize the original.)
			LevelChunkSection section = fake ? new LevelChunkSection(biomes) : real;
			ShortOpenHashSet positions = new ShortOpenHashSet();
			for (Placement p : e.getValue()) {
				BlockPos pos = p.pos();
				positions.add(SectionPos.sectionRelativePos(pos));
				if (fake) {
					BlockState state = p.state();
					section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
				}
			}
			ClientboundSectionBlocksUpdatePacket packet = new ClientboundSectionBlocksUpdatePacket(sectionPos, positions, section);
			for (ServerPlayer player : players) {
				player.connection.send(packet);
			}
		}
		return players.size();
	}
}
