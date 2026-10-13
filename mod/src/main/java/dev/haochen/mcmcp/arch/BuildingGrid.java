package dev.haochen.mcmcp.arch;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A compiled building in its local frame: every cell knows its block state, the component that
 * produced it and what role it plays, so validation and later edits can work per component.
 * Also maps local positions to the world ({@link #world}) the same way blueprints are placed.
 */
public final class BuildingGrid {
	public enum Role {
		FOUNDATION, FLOOR, WALL, FRAME, OPENING, STAIRS, ROOF, TOWER, FURNITURE, OUTDOOR, TERRAIN, CLEAR, PATCH
	}

	public record Cell(BlockState state, String component, Role role) {
	}

	public final BuildingPlan plan;
	public final ServerLevel level;
	public final Rotation rotation;
	private final Long2ObjectLinkedOpenHashMap<Cell> cells = new Long2ObjectLinkedOpenHashMap<>();
	/** Positions intentionally left open in walls (doors, windows, arches). */
	public final LongOpenHashSet openingCells = new LongOpenHashSet();
	private int minX, minY, minZ, maxX, maxY, maxZ;
	private boolean empty = true;

	BuildingGrid(BuildingPlan plan, ServerLevel level) {
		this.plan = plan;
		this.level = level;
		this.rotation = switch (plan.facing) {
			case WEST -> Rotation.CLOCKWISE_90;
			case NORTH -> Rotation.CLOCKWISE_180;
			case EAST -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	public static long key(int x, int y, int z) {
		return BlockPos.asLong(x, y, z);
	}

	public void set(int x, int y, int z, BlockState state, String component, Role role) {
		cells.put(key(x, y, z), new Cell(state, component, role));
		if (empty) {
			minX = maxX = x;
			minY = maxY = y;
			minZ = maxZ = z;
			empty = false;
		} else {
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}
	}

	/** Sets only if nothing is there yet. */
	public void setIfAbsent(int x, int y, int z, BlockState state, String component, Role role) {
		if (!cells.containsKey(key(x, y, z))) {
			set(x, y, z, state, component, role);
		}
	}

	public Cell get(int x, int y, int z) {
		return cells.get(key(x, y, z));
	}

	public boolean has(int x, int y, int z) {
		return cells.containsKey(key(x, y, z));
	}

	public Long2ObjectLinkedOpenHashMap<Cell> cells() {
		return cells;
	}

	public int minX() { return minX; }
	public int minY() { return minY; }
	public int minZ() { return minZ; }
	public int maxX() { return maxX; }
	public int maxY() { return maxY; }
	public int maxZ() { return maxZ; }

	/** World position of a local cell (rotation as in {@code Blueprint.place}, footprint min corner at origin). */
	public BlockPos world(int x, int y, int z) {
		int w = plan.width, d = plan.depth;
		int tx, tz;
		switch (rotation) {
			case CLOCKWISE_90 -> { tx = d - 1 - z; tz = x; }
			case CLOCKWISE_180 -> { tx = w - 1 - x; tz = d - 1 - z; }
			case COUNTERCLOCKWISE_90 -> { tx = z; tz = w - 1 - x; }
			default -> { tx = x; tz = z; }
		}
		return plan.origin.offset(tx, y, tz);
	}

	/** The world block at a local position as it is now (before building). */
	public BlockState worldState(int x, int y, int z) {
		return level.getBlockState(world(x, y, z));
	}

	/** The block that will be there after building: the compiled cell, or the untouched world. */
	public BlockState finalState(int x, int y, int z) {
		Cell c = get(x, y, z);
		return c != null ? c.state() : worldState(x, y, z);
	}

	/** A local direction as a world direction. */
	public Direction worldDirection(Direction local) {
		return rotation.rotate(local);
	}
}
