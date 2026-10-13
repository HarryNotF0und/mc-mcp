package dev.haochen.mcmcp.history;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** A block state plus its block entity data (chest contents, sign text, ...), if any. */
public record BlockSnapshot(BlockState state, @Nullable CompoundTag nbt) {
	/**
	 * Notify clients but touch nothing else: no neighbour block updates (no physics, no redstone),
	 * no neighbour shape updates (blocks outside the operation never change, so undo is exact),
	 * no drops. See docs/decisions.md D6.
	 */
	public static final int SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	public static BlockSnapshot capture(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
		return new BlockSnapshot(state, be == null ? null : be.saveWithFullMetadata(level.registryAccess()));
	}

	/** Writes this snapshot to the world exactly, without recomputing any shapes. */
	public void restore(ServerLevel level, BlockPos pos) {
		Clearable.tryClear(level.getBlockEntity(pos));
		level.setBlock(pos, state, SET_FLAGS);
		if (nbt != null) {
			BlockEntity be = level.getBlockEntity(pos);
			if (be != null) {
				be.loadWithComponents(nbt, level.registryAccess());
				be.setChanged();
			}
		}
	}
}
