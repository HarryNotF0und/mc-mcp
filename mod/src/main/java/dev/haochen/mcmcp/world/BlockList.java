package dev.haochen.mcmcp.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.jobs.PlaceJob.Placement;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Blocks to place, in world coordinates, produced by a script or blueprint. Setting a position
 * twice keeps the last state (at the position's first slot in the order).
 */
public final class BlockList {
	private final Long2ObjectLinkedOpenHashMap<BlockState> blocks = new Long2ObjectLinkedOpenHashMap<>();
	private @Nullable Region bounds;

	public void put(BlockPos pos, BlockState state) {
		if (blocks.put(pos.asLong(), state) == null) {
			bounds = bounds == null ? new Region(pos.immutable(), pos.immutable()) : bounds.include(pos);
		}
	}

	public @Nullable BlockState get(long pos) {
		return blocks.get(pos);
	}

	public int size() {
		return blocks.size();
	}

	public @Nullable Region bounds() {
		return bounds;
	}

	public Iterator<Placement> placements() {
		var it = blocks.long2ObjectEntrySet().fastIterator();
		return new Iterator<>() {
			@Override
			public boolean hasNext() {
				return it.hasNext();
			}

			@Override
			public Placement next() {
				var e = it.next();
				return new Placement(BlockPos.of(e.getLongKey()), e.getValue());
			}
		};
	}

	/** {@code {"blocks": n, "bounds": {...}, "counts": [{"block": ..., "count": n}, ...]}} (states, most common first). */
	public JsonObject stats(int top) {
		Map<BlockState, Integer> counts = new HashMap<>();
		for (BlockState s : blocks.values()) {
			counts.merge(s, 1, Integer::sum);
		}
		List<Map.Entry<BlockState, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort((a, b) -> b.getValue() - a.getValue());
		JsonArray list = new JsonArray();
		for (var e : sorted.subList(0, Math.min(top, sorted.size()))) {
			JsonObject o = new JsonObject();
			o.addProperty("block", Blocks.toString(e.getKey()));
			o.addProperty("count", e.getValue());
			list.add(o);
		}
		JsonObject o = new JsonObject();
		o.addProperty("blocks", blocks.size());
		if (bounds != null) {
			o.add("bounds", bounds.toJson());
		}
		o.add("counts", list);
		if (sorted.size() > top) {
			o.addProperty("otherStates", sorted.size() - top);
		}
		return o;
	}
}
