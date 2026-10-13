package dev.haochen.mcmcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/** Contents of {@code config/mcmcp.json}. Missing fields fall back to the defaults below. */
public final class McMcpConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public String host = "127.0.0.1";
	public int port = 25580;
	public String token = "";
	public List<Region> allowedRegions = List.of(
			new Region("minecraft:overworld", new int[]{-256, -64, -256}, new int[]{256, 320, 256}));
	public int maxVolumePerOp = 262144;
	public int blocksPerTick = 2000;
	public List<String> blockBlacklist = List.of("minecraft:command_block", "minecraft:tnt");
	public int scriptTimeoutMs = 5000;
	public int historySize = 50;

	public record Region(String dimension, int[] min, int[] max) {
	}

	/** Loads the config, creating it (with a fresh random token) if it does not exist. */
	public static McMcpConfig loadOrCreate(Path file) {
		McMcpConfig config = null;
		if (Files.exists(file)) {
			try {
				config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), McMcpConfig.class);
			} catch (IOException | JsonParseException e) {
				McMcpMod.LOGGER.error("Failed to read {}, using defaults", file, e);
			}
		}
		if (config == null) {
			config = new McMcpConfig();
		}
		if (config.token == null || config.token.isBlank()) {
			config.token = newToken();
			config.save(file);
		}
		return config;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			McMcpMod.LOGGER.error("Failed to write {}", file, e);
		}
	}

	private static String newToken() {
		byte[] bytes = new byte[24];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}
}
