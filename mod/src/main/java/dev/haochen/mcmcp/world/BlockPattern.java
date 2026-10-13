package dev.haochen.mcmcp.world;

import net.minecraft.world.level.block.state.BlockState;

/**
 * What to place at a position: a single block state, or (with WorldEdit installed) a WorldEdit
 * pattern such as {@code 50%stone,50%andesite}. Coordinates are world coordinates.
 */
@FunctionalInterface
public interface BlockPattern {
	BlockState at(int x, int y, int z);

	static BlockPattern of(BlockState state) {
		return (x, y, z) -> state;
	}
}
