package dev.haochen.mcmcp.script;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Lets a script on a worker thread read the world. The first read in a chunk copies that chunk's
 * block palettes on the server thread; later reads in the chunk are local.
 */
final class WorldSnapshot {
	/** Thrown when a chunk is not loaded and loading was not requested. */
	static final class ChunkNotLoaded extends RuntimeException {
		ChunkNotLoaded(int cx, int cz) {
			super("chunk at x=" + (cx << 4) + "..." + ((cx << 4) + 15) + ", z=" + (cz << 4) + "..." + ((cz << 4) + 15)
					+ " is not loaded; pass loadChunks=true");
		}
	}

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private final MinecraftServer server;
	private final ServerLevel level;
	private final boolean loadChunks;
	private final int minY;
	private final int maxY;
	private final Long2ObjectOpenHashMap<PalettedContainer<BlockState>[]> chunks = new Long2ObjectOpenHashMap<>();

	WorldSnapshot(MinecraftServer server, ServerLevel level, boolean loadChunks) {
		this.server = server;
		this.level = level;
		this.loadChunks = loadChunks;
		this.minY = level.getMinBuildHeight();
		this.maxY = level.getMaxBuildHeight();
	}

	BlockState get(int x, int y, int z) {
		if (y < minY || y >= maxY) {
			return AIR;
		}
		int cx = x >> 4, cz = z >> 4;
		long key = ChunkPos.asLong(cx, cz);
		PalettedContainer<BlockState>[] sections = chunks.get(key);
		if (sections == null) {
			sections = fetch(cx, cz);
			chunks.put(key, sections);
		}
		return sections[(y - minY) >> 4].get(x & 15, y & 15, z & 15);
	}

	private PalettedContainer<BlockState>[] fetch(int cx, int cz) {
		PalettedContainer<BlockState> @Nullable [] copy;
		try {
			copy = server.submit(() -> {
				LevelChunk chunk = loadChunks ? level.getChunk(cx, cz) : level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					return null;
				}
				LevelChunkSection[] src = chunk.getSections();
				@SuppressWarnings("unchecked")
				PalettedContainer<BlockState>[] out = new PalettedContainer[src.length];
				for (int i = 0; i < src.length; i++) {
					out[i] = src[i].getStates().copy();
				}
				return out;
			}).get(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while reading the world");
		} catch (Exception e) {
			throw new IllegalStateException("could not read chunk " + cx + "," + cz + ": " + e);
		}
		if (copy == null) {
			throw new ChunkNotLoaded(cx, cz);
		}
		return copy;
	}
}
