package dev.haochen.mcmcp.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * What a block's states mean, so an AI does not have to guess orientations: every property with
 * its values, and plain-language notes for the blocks whose {@code facing} is easy to get wrong
 * (checked against the 1.21.1 block shapes and placement code).
 */
public final class BlockInfo {
	private BlockInfo() {
	}

	public static JsonObject describe(BlockState state) {
		Block block = state.getBlock();
		JsonObject o = new JsonObject();
		o.addProperty("id", Blocks.id(state));
		o.addProperty("state", Blocks.toString(state));
		o.addProperty("defaultState", Blocks.toString(block.defaultBlockState()));
		JsonObject props = new JsonObject();
		for (Property<?> p : state.getProperties()) {
			JsonArray values = new JsonArray();
			for (Object v : p.getPossibleValues()) {
				values.add(name(p, v));
			}
			props.add(p.getName(), values);
		}
		o.add("properties", props);
		JsonArray notes = new JsonArray();
		notes(block, state).forEach(notes::add);
		o.add("notes", notes);
		o.addProperty("solid", state.isSolid());
		o.addProperty("fullBlock", state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO));
		return o;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static String name(Property p, Object v) {
		return p.getName((Comparable) v);
	}

	private static List<String> notes(Block b, BlockState s) {
		List<String> n = new ArrayList<>();
		if (b instanceof StairBlock) {
			n.add("facing = the side of the tall back part; you walk up the stairs going towards facing "
					+ "(facing=north: climb northwards). As a chair, the backrest is on the facing side and you sit looking the opposite way.");
			n.add("half=bottom is a normal stair; half=top is upside-down (ceilings, arches, eaves).");
			n.add("shape (straight/inner_left/...) is recomputed from neighbours when placed by a player; MC-MCP places it as given.");
		} else if (b instanceof DoorBlock) {
			n.add("facing = the direction a player looks when placing it (and walks when going through from the panel side). "
					+ "The closed panel lies on the edge OPPOSITE to facing: facing=north puts the panel on the south edge of the block.");
			n.add("hinge=left/right is seen looking towards facing; the open door swings to that side.");
			n.add("Needs both halves: half=lower at y and half=upper at y+1 with the same facing/hinge/open.");
		} else if (b instanceof TrapDoorBlock) {
			n.add("Closed: a flat plate, half=bottom on the floor of the block, half=top against its ceiling.");
			n.add("Open: stands upright against the edge OPPOSITE to facing (facing=north: against the south edge). "
					+ "facing points away from the wall it is hinged to.");
		} else if (b instanceof FenceGateBlock) {
			n.add("facing = the direction you walk through it; in_wall=true lowers it to match cobblestone walls.");
		} else if (b instanceof BedBlock) {
			n.add("facing = from the foot towards the head. Needs part=foot and part=head, the head one block towards facing.");
		} else if (b instanceof WallBannerBlock || b instanceof WallSignBlock || b instanceof WallTorchBlock || b instanceof LadderBlock) {
			n.add("facing = the direction its front faces, away from the wall; it hangs on the block on the opposite side "
					+ "(facing=north hangs on the block to its south). Without that block it breaks when updated.");
		} else if (b instanceof BannerBlock || b instanceof StandingSignBlock) {
			n.add("rotation 0-15 in 22.5 degree steps: 0 front faces south, 4 west, 8 north, 12 east.");
		} else if (b instanceof ButtonBlock || b instanceof LeverBlock) {
			n.add("face=floor/wall/ceiling; on a wall, facing points away from the block it is attached to.");
		} else if (b instanceof PistonBaseBlock) {
			n.add("facing = the direction it pushes (the head side); extended=true only while powered.");
		} else if (b instanceof SlabBlock) {
			n.add("type=bottom (lower half), top (upper half) or double (full block).");
		} else if (b instanceof RotatedPillarBlock) {
			n.add("axis=y stands upright; axis=x/z lies along that axis.");
		} else if (b instanceof LanternBlock) {
			n.add("hanging=true hangs under the block above; false stands on the block below.");
		} else if (b instanceof HorizontalDirectionalBlock || s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			n.add("facing = the direction its front (face, opening, keyhole) looks; usually towards the player who placed it.");
		}
		if (s.hasProperty(BlockStateProperties.WATERLOGGED)) {
			n.add("waterlogged=true fills it with water.");
		}
		return n;
	}
}
