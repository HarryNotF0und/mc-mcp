package dev.haochen.mcmcp.worldedit;

import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.blueprint.Blueprint;
import dev.haochen.mcmcp.world.BlockPattern;
import dev.haochen.mcmcp.world.Region;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Optional WorldEdit integration (see docs/decisions.md D18). WorldEdit is only used to parse
 * patterns and read/write schematic files; every placement still goes through our job queue,
 * guards and undo history. This interface uses only our own types, so code that calls it never
 * loads WorldEdit classes; the implementation is created reflectively when WorldEdit is present.
 */
public interface WorldEditBridge {
	/** Parses a WorldEdit pattern; failures are error 1003. */
	BlockPattern parsePattern(String input);

	/** Loads {@code <WorldEdit schematics folder>/<name>[.schem|.schematic]}; block entity data is not kept. */
	Blueprint loadSchematic(String name);

	/** Saves the region as a Sponge v3 {@code .schem} in WorldEdit's schematics folder; returns the file. */
	Path saveSchematic(String name, ServerLevel level, Region region);

	/** Schematic file names in WorldEdit's schematics folder. */
	List<String> listSchematics();

	String version();

	/** The bridge, or null when WorldEdit is not installed. */
	static @Nullable WorldEditBridge get() {
		return Holder.INSTANCE;
	}

	final class Holder {
		private static final WorldEditBridge INSTANCE = create();

		private Holder() {
		}

		private static WorldEditBridge create() {
			if (!FabricLoader.getInstance().isModLoaded("worldedit")) {
				return null;
			}
			try {
				return (WorldEditBridge) Class.forName("dev.haochen.mcmcp.worldedit.WorldEditBridgeImpl")
						.getDeclaredConstructor().newInstance();
			} catch (ReflectiveOperationException | LinkageError e) {
				McMcpMod.LOGGER.warn("WorldEdit is installed but its API could not be loaded; integration disabled", e);
				return null;
			}
		}
	}
}
