package dev.haochen.mcmcp.world;

import com.google.gson.JsonObject;
import dev.haochen.mcmcp.api.Json;
import net.minecraft.core.BlockPos;

/** An axis-aligned box of blocks; both corners are inclusive and {@code min <= max} on every axis. */
public record Region(BlockPos min, BlockPos max) {
	public static Region of(BlockPos a, BlockPos b) {
		return new Region(
				new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
				new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
	}

	public int sizeX() {
		return max.getX() - min.getX() + 1;
	}

	public int sizeY() {
		return max.getY() - min.getY() + 1;
	}

	public int sizeZ() {
		return max.getZ() - min.getZ() + 1;
	}

	public long volume() {
		return (long) sizeX() * sizeY() * sizeZ();
	}

	public boolean contains(BlockPos p) {
		return p.getX() >= min.getX() && p.getX() <= max.getX()
				&& p.getY() >= min.getY() && p.getY() <= max.getY()
				&& p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
	}

	public boolean contains(Region other) {
		return contains(other.min) && contains(other.max);
	}

	/** True if {@code p} is on the outer shell of this region. */
	public boolean onShell(BlockPos p) {
		return p.getX() == min.getX() || p.getX() == max.getX()
				|| p.getY() == min.getY() || p.getY() == max.getY()
				|| p.getZ() == min.getZ() || p.getZ() == max.getZ();
	}

	/** Grows this region to include {@code p}. */
	public Region include(BlockPos p) {
		return Region.of(
				new BlockPos(Math.min(min.getX(), p.getX()), Math.min(min.getY(), p.getY()), Math.min(min.getZ(), p.getZ())),
				new BlockPos(Math.max(max.getX(), p.getX()), Math.max(max.getY(), p.getY()), Math.max(max.getZ(), p.getZ())));
	}

	/**
	 * Splits this region into boxes of at most {@code maxVolume} blocks, bottom layers first: whole
	 * horizontal layers when one fits, otherwise rows of a layer.
	 */
	public java.util.List<Region> split(long maxVolume) {
		java.util.List<Region> out = new java.util.ArrayList<>();
		long layer = (long) sizeX() * sizeZ();
		if (layer <= maxVolume) {
			int layers = (int) Math.max(1, maxVolume / layer);
			for (int y = min.getY(); y <= max.getY(); y += layers) {
				out.add(new Region(new BlockPos(min.getX(), y, min.getZ()), new BlockPos(max.getX(), Math.min(max.getY(), y + layers - 1), max.getZ())));
			}
			return out;
		}
		if (sizeX() > maxVolume) {
			throw new IllegalArgumentException("a single row is larger than " + maxVolume);
		}
		int rows = (int) (maxVolume / sizeX());
		for (int y = min.getY(); y <= max.getY(); y++) {
			for (int z = min.getZ(); z <= max.getZ(); z += rows) {
				out.add(new Region(new BlockPos(min.getX(), y, z), new BlockPos(max.getX(), y, Math.min(max.getZ(), z + rows - 1))));
			}
		}
		return out;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.add("min", Json.blockPos(min));
		o.add("max", Json.blockPos(max));
		return o;
	}
}
