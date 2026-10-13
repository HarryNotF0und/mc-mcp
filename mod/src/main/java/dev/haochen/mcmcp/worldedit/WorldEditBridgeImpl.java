package dev.haochen.mcmcp.worldedit;

import com.google.gson.JsonObject;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import dev.haochen.mcmcp.api.Params;
import dev.haochen.mcmcp.blueprint.Blueprint;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.BlockPattern;
import dev.haochen.mcmcp.world.Region;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Only loaded when WorldEdit is installed; see {@link WorldEditBridge}. */
final class WorldEditBridgeImpl implements WorldEditBridge {
	private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_\\-. ]{1,100}");
	private static final List<String> EXTENSIONS = List.of(".schem", ".schematic");

	@Override
	public BlockPattern parsePattern(String input) {
		ParserContext context = new ParserContext();
		context.setRestricted(false);
		context.setTryLegacy(false);
		context.setPreferringWildcard(false);
		Pattern pattern;
		try {
			pattern = WorldEdit.getInstance().getPatternFactory().parseFromInput(input, context);
		} catch (InputParseException e) {
			JsonObject data = new JsonObject();
			data.addProperty("pattern", input);
			throw new RpcException(RpcException.UNKNOWN_BLOCK,
					"invalid block or WorldEdit pattern '" + input + "': " + e.getMessage(), data);
		}
		return (x, y, z) -> FabricAdapter.adapt(pattern.applyBlock(BlockVector3.at(x, y, z)).toImmutableState());
	}

	@Override
	public Blueprint loadSchematic(String name) {
		Path file = find(name);
		ClipboardFormat format = ClipboardFormats.findByFile(file.toFile());
		if (format == null) {
			throw Params.invalid("unknown schematic format: " + file.getFileName());
		}
		Clipboard clipboard;
		try (InputStream in = Files.newInputStream(file); ClipboardReader reader = format.getReader(in)) {
			clipboard = reader.read();
		} catch (IOException e) {
			throw Params.invalid("could not read schematic " + file.getFileName() + ": " + e.getMessage());
		}
		BlockVector3 min = clipboard.getRegion().getMinimumPoint();
		BlockVector3 size = clipboard.getDimensions();
		int sx = size.x(), sy = size.y(), sz = size.z();
		BlockState[] cells = new BlockState[sx * sy * sz];
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					cells[(y * sz + z) * sx + x] = FabricAdapter.adapt(clipboard.getBlock(min.add(x, y, z)));
				}
			}
		}
		return Blueprint.of(sx, sy, sz, cells);
	}

	@Override
	public Path saveSchematic(String name, ServerLevel level, Region region) {
		Path dir = schematicsDir();
		String fileName = name.endsWith(".schem") ? name : name + ".schem";
		Path file = resolve(dir, fileName);
		BlockVector3 min = FabricAdapter.adapt(region.min());
		BlockArrayClipboard clipboard = new BlockArrayClipboard(new CuboidRegion(min, FabricAdapter.adapt(region.max())));
		clipboard.setOrigin(min);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = region.min().getY(); y <= region.max().getY(); y++) {
			for (int z = region.min().getZ(); z <= region.max().getZ(); z++) {
				for (int x = region.min().getX(); x <= region.max().getX(); x++) {
					pos.set(x, y, z);
					clipboard.setBlock(BlockVector3.at(x, y, z), FabricAdapter.adapt(level.getBlockState(pos)));
				}
			}
		}
		try {
			Files.createDirectories(dir);
			try (OutputStream out = Files.newOutputStream(file);
				 ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(out)) {
				writer.write(clipboard);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("could not write schematic " + fileName, e);
		}
		return file;
	}

	@Override
	public List<String> listSchematics() {
		Path dir = schematicsDir();
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			files.map(p -> p.getFileName().toString())
					.filter(n -> EXTENSIONS.stream().anyMatch(n::endsWith))
					.sorted()
					.forEach(names::add);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return names;
	}

	@Override
	public String version() {
		return FabricLoader.getInstance().getModContainer("worldedit")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
	}

	private static Path schematicsDir() {
		WorldEdit we = WorldEdit.getInstance();
		return we.getWorkingDirectoryPath(we.getConfiguration().saveDir);
	}

	/** {@code name} with or without extension, inside the schematics folder. */
	private static Path find(String name) {
		Path dir = schematicsDir();
		Path exact = resolve(dir, name);
		if (Files.isRegularFile(exact)) {
			return exact;
		}
		for (String ext : EXTENSIONS) {
			Path p = resolve(dir, name + ext);
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		throw Params.invalid("no schematic named \"" + name + "\" in " + dir.toAbsolutePath());
	}

	private static Path resolve(Path dir, String name) {
		if (!NAME.matcher(name).matches() || name.contains("..")) {
			throw Params.invalid("schematic names may only contain letters, digits, space, _ - and .");
		}
		return dir.resolve(name);
	}
}
