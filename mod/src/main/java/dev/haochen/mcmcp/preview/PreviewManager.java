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
	private int nextId = 1;

	/** Validates the plan like a real write, then shows it. Returns {@code {previewId, players, ...stats}}. */
	public JsonObject create(Services s, Plan plan) {
		plan.validate(s);
		if (previews.size() >= MAX_PREVIEWS) {
			Iterator<Preview> oldest = previews.values().iterator();
			Preview p = oldest.next();
			oldest.remove();
			send(p.plan(), false);
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
		Preview p = take(id);
		try {
			return p.plan().submit(s);
		} catch (RuntimeException e) {
			send(p.plan(), false);
			throw e;
		}
	}

	/** Shows the real blocks again. */
	public void discard(int id) {
		send(take(id).plan(), false);
	}

	private Preview take(int id) {
		Preview p = previews.remove(id);
		if (p == null) {
			throw new RpcException(RpcException.INVALID_PARAMS, "no active preview with id " + id);
		}
		return p;
	}

	/**
	 * Sends one section update packet per affected chunk section to every player in the level,
	 * with either the planned states ({@code fake}) or the real ones. Returns the player count.
	 */
	private static int send(Plan plan, boolean fake) {
		ServerLevel level = plan.level();
		Long2ObjectOpenHashMap<List<Placement>> bySection = new Long2ObjectOpenHashMap<>();
		for (Iterator<Placement> it = plan.blocks().placements(); it.hasNext(); ) {
			Placement p = it.next();
			if (level.isOutsideBuildHeight(p.pos())) {
				continue;
			}
			bySection.computeIfAbsent(SectionPos.asLong(p.pos()), k -> new ArrayList<>()).add(p);
		}
		List<ServerPlayer> players = level.players();
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
