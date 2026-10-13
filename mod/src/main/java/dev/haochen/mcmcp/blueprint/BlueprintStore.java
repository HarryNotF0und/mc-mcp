package dev.haochen.mcmcp.blueprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.haochen.mcmcp.api.Params;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Named blueprints saved as {@code <game dir>/mcmcp/blueprints/<name>.json}, so large exports
 * don't have to pass through the model's context.
 */
public final class BlueprintStore {
	private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,64}");

	private final Path dir;

	public BlueprintStore(Path dir) {
		this.dir = dir;
	}

	public Path save(String name, JsonObject blueprint) {
		Path file = file(name);
		try {
			Files.createDirectories(dir);
			Files.writeString(file, blueprint.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("could not save blueprint " + name, e);
		}
		return file;
	}

	public JsonObject load(String name) {
		try {
			return JsonParser.parseString(Files.readString(file(name), StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (NoSuchFileException e) {
			throw Params.invalid("no saved blueprint named \"" + name + "\"");
		} catch (IOException | RuntimeException e) {
			throw Params.invalid("could not read blueprint \"" + name + "\": " + e.getMessage());
		}
	}

	/** Saved blueprint names, sorted. */
	public java.util.List<String> list() {
		if (!Files.isDirectory(dir)) {
			return java.util.List.of();
		}
		try (var files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(".json"))
					.map(n -> n.substring(0, n.length() - 5))
					.sorted()
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private Path file(String name) {
		if (!NAME.matcher(name).matches()) {
			throw Params.invalid("blueprint names may only contain a-z, 0-9, _ and - (max 64)");
		}
		return dir.resolve(name + ".json");
	}
}
