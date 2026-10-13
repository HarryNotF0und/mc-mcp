package dev.haochen.mcmcp.arch;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Classifies blocks as natural terrain, vegetation or player-built, and reads the ground surface
 * of a column (ignoring trees, plants and snow). Server thread.
 */
public final class Terrain {
	private Terrain() {
	}

	/** What sits on top of a column. */
	public enum Surface {
		GROUND, WATER, LAVA, TREE, BUILT, VOID
	}

	/** One column: ground height (y of the top ground block) and what is on top. */
	public record Column(int groundY, Surface surface, boolean road, int topY) {
	}

	/** Soft natural blocks that are not "ground": vegetation, snow, and the like. */
	public static boolean vegetation(BlockState s) {
		return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)
				|| s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.REPLACEABLE_BY_TREES) || s.is(BlockTags.CAVE_VINES)
				|| s.is(Blocks.SNOW) || s.is(Blocks.VINE) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.BAMBOO)
				|| s.is(Blocks.CACTUS) || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.BROWN_MUSHROOM_BLOCK)
				|| s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.PUMPKIN)
				|| s.is(Blocks.MELON) || s.is(Blocks.BEE_NEST) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.KELP)
				|| s.is(Blocks.KELP_PLANT) || s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS) || s.is(Blocks.LILY_PAD)
				|| s.is(Blocks.BIG_DRIPLEAF) || s.is(Blocks.BIG_DRIPLEAF_STEM) || s.is(Blocks.SMALL_DRIPLEAF)
				|| s.is(Blocks.AZALEA) || s.is(Blocks.FLOWERING_AZALEA) || s.is(Blocks.COCOA) || s.is(Blocks.GLOW_LICHEN);
	}

	/** Natural ground: soil, stone, sand, ores and similar. */
	public static boolean ground(BlockState s) {
		return s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER)
				|| s.is(BlockTags.SAND) || s.is(BlockTags.TERRACOTTA) || s.is(BlockTags.ICE) || s.is(BlockTags.NYLIUM)
				|| s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES)
				|| s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)
				|| s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES) || s.is(Blocks.GRAVEL)
				|| s.is(Blocks.CLAY) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.SANDSTONE)
				|| s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK)
				|| s.is(Blocks.OBSIDIAN) || s.is(Blocks.BEDROCK) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.AMETHYST_BLOCK)
				|| s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.BASALT) || s.is(Blocks.SOUL_SAND) || s.is(Blocks.SOUL_SOIL)
				|| s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND) || s.is(Blocks.MUD) || s.is(Blocks.SCULK);
	}

	/** Not air, fluid, vegetation or natural ground: something a player (or village) built. */
	public static boolean built(BlockState s) {
		return !s.isAir() && s.getFluidState().isEmpty() && !vegetation(s) && !ground(s);
	}

	/** Paths and roads that make a good entrance side. */
	public static boolean road(BlockState s) {
		return s.is(Blocks.DIRT_PATH) || s.is(Blocks.COBBLESTONE)
				|| s.is(Blocks.STONE_BRICKS) || s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.POLISHED_ANDESITE);
	}

	/** Scans down from the surface to the first ground (or built) block, at most {@code maxDepth} blocks. */
	public static Column column(ServerLevel level, int x, int z, int maxDepth) {
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
		Surface surface = null;
		int minY = Math.max(level.getMinBuildHeight(), top - maxDepth);
		for (int y = top; y >= minY; y--) {
			p.setY(y);
			BlockState s = level.getBlockState(p);
			if (s.isAir()) {
				continue;
			}
			if (!s.getFluidState().isEmpty() && s.getCollisionShape(level, p).isEmpty()) {
				if (surface == null) {
					surface = s.getFluidState().is(FluidTags.LAVA) ? Surface.LAVA : Surface.WATER;
				}
				continue;
			}
			if (vegetation(s)) {
				if (surface == null && (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES))) {
					surface = Surface.TREE;
				}
				continue;
			}
			if (ground(s)) {
				return new Column(y, surface == null ? Surface.GROUND : surface, road(s), top);
			}
			// Built block: it is the "ground" here, but flagged.
			return new Column(y, surface == null || surface == Surface.TREE ? Surface.BUILT : surface, road(s), top);
		}
		return new Column(minY, Surface.VOID, false, top);
	}
}
