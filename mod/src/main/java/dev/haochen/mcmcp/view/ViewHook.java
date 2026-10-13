package dev.haochen.mcmcp.view;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Screenshots need the game client, which a dedicated server does not have. The client
 * entrypoint registers an implementation via {@code McMcpMod.setViewHook}; common code only
 * talks to this interface so it never loads client classes.
 */
public interface ViewHook {
	/**
	 * Camera at eye position (x, y, z). yaw/pitch use Minecraft's convention:
	 * yaw 0 looks south (+z), 90 west, 180 north, 270 east; pitch positive looks down.
	 */
	record Shot(double x, double y, double z, float yaw, float pitch, String label) {
	}

	/**
	 * Renders each shot at width x height with the given vertical FOV and returns PNG bytes, in order.
	 * {@code daylight} renders as if it were noon (client-side only).
	 */
	CompletableFuture<List<byte[]>> capture(List<Shot> shots, int width, int height, int fov, boolean daylight);
}
