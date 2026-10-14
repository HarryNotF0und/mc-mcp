package dev.haochen.mcmcp.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.net.RpcDispatcher;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.view.ViewHook;
import dev.haochen.mcmcp.view.ViewHook.Shot;
import dev.haochen.mcmcp.world.Region;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** {@code view.screenshot} and {@code view.orbitShots}; need the game client in the same JVM (single-player). */
public final class ViewApi {
	private static final int MAX_WIDTH = 1920, MAX_HEIGHT = 1080;
	private static final String[] FROM = {"north", "east", "south", "west"};

	private ViewApi() {
	}

	public static void register(RpcDispatcher dispatcher) {
		dispatcher.async("view.screenshot", ViewApi::screenshot);
		dispatcher.async("view.orbitShots", ViewApi::orbitShots);
		dispatcher.onMainThread("view.refresh", (server, params) -> {
			var level = WorldApi.level(server, params);
			Region region = Params.region(params, "region");
			McMcpMod.services().guard().checkVolume(region.volume());
			JsonObject o = new JsonObject();
			o.addProperty("players", McMcpMod.services().previews().refresh(level, region));
			o.addProperty("blocks", region.volume());
			return o;
		});
	}

	private static CompletableFuture<JsonElement> screenshot(MinecraftServer server, JsonObject params) {
		ViewHook hook = hook();
		Size size = size(params);
		boolean hasPos = Params.has(params, "pos");
		String[] warning = new String[1];
		// Defaults come from the first player's eyes, read on the server thread.
		return server.submit(() -> {
			Vec3 pos;
			float yaw, pitch;
			ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
			if (hasPos) {
				pos = Params.vec3(params, "pos");
			} else if (player != null) {
				pos = player.getEyePosition();
			} else {
				throw Params.invalid("pos is required when no player is online");
			}
			if (Params.has(params, "lookAt")) {
				Vec3 d = Params.vec3(params, "lookAt").subtract(pos);
				yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
				pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
			} else {
				yaw = (float) Params.number(params, "yaw", player != null && !hasPos ? player.getYRot() : 0);
				pitch = (float) Params.number(params, "pitch", player != null && !hasPos ? player.getXRot() : 0);
			}
			warning[0] = farWarning(server, hook, Params.has(params, "lookAt") ? Params.vec3(params, "lookAt") : pos, 0);
			return List.of(new Shot(pos.x, pos.y, pos.z, yaw, pitch, "screenshot"));
		}).thenCompose(shots -> capture(hook, shots, size)).thenApply(r -> withWarning(r, warning[0]));
	}

	/**
	 * The client only has (and draws) chunks within its render distance of the player: farther away
	 * pictures show sky and fog, not the build. Null when the target is close enough. Server thread.
	 */
	public static String farWarning(MinecraftServer server, ViewHook hook, Vec3 target, double radius) {
		int drawn = hook.renderDistanceBlocks();
		ServerPlayer player = server.getPlayerList().getPlayers().stream()
				.filter(p -> !(p instanceof dev.haochen.mcmcp.agent.AgentPlayer)).findFirst().orElse(null);
		if (drawn <= 0 || player == null) {
			return null;
		}
		double dx = target.x - player.getX(), dz = target.z - player.getZ();
		double far = Math.sqrt(dx * dx + dz * dz) + radius;
		if (far <= drawn - 8) {
			return null;
		}
		return "the target is up to " + Math.round(far) + " blocks from the player, but the client only draws about " + drawn
				+ " blocks around the player: parts outside will be missing (sky/fog). Ask the player to move closer "
				+ "(near " + Math.round(target.x) + ", " + Math.round(target.z) + ") or raise the render distance, then take the pictures again.";
	}

	static JsonElement withWarning(JsonElement result, String warning) {
		if (warning != null && result.isJsonObject()) {
			result.getAsJsonObject().addProperty("warning", warning);
		}
		return result;
	}

	private static CompletableFuture<JsonElement> orbitShots(MinecraftServer server, JsonObject params) {
		ViewHook hook = hook();
		Size size = size(params);
		return server.submit(() -> {
			Region region;
			if (Params.has(params, "region")) {
				region = Params.region(params, "region");
			} else if (Params.has(params, "building")) {
				region = buildingBounds(Params.string(params, "building"));
			} else {
				throw Params.invalid("pass region {min, max} (the box to photograph) or building (a registered building's name)");
			}
			return region;
		}).thenCompose(region -> orbit(server, hook, size, region, params));
	}

	/** The bounding box of a registered building's compiled blocks. Server thread. */
	static Region buildingBounds(String name) {
		var e = McMcpMod.services().buildings().get(name);
		if (e.compiled == null || e.compiled.isEmpty()) {
			throw Params.invalid("building " + name + " has no compiled blocks yet; call inspect_building once, or pass region");
		}
		Region r = null;
		for (long key : e.compiled.keySet()) {
			net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.of(key);
			r = r == null ? new Region(p, p) : r.include(p);
		}
		return r;
	}

	private static CompletableFuture<JsonElement> orbit(MinecraftServer server, ViewHook hook, Size size, Region region, JsonObject params) {
		int count = Params.integer(params, "count", 4);
		if (count < 1 || count > 8) {
			throw Params.invalid("count must be 1 to 8");
		}
		double pitch = Params.number(params, "pitch", 30);
		Vec3 center = new Vec3((region.min().getX() + region.max().getX() + 1) / 2.0,
				(region.min().getY() + region.max().getY() + 1) / 2.0,
				(region.min().getZ() + region.max().getZ() + 1) / 2.0);
		double radius = 0.5 * Math.sqrt((double) region.sizeX() * region.sizeX()
				+ (double) region.sizeY() * region.sizeY() + (double) region.sizeZ() * region.sizeZ());
		double fitDistance = radius / Math.tan(Math.toRadians(size.fov / 2.0)) * 1.1;
		double distance = Params.number(params, "distance", Math.max(4, fitDistance));

		List<Shot> shots = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			double yaw = i * 360.0 / count;
			Vec3 look = Vec3.directionFromRotation((float) pitch, (float) yaw);
			Vec3 cam = center.subtract(look.scale(distance));
			String label = count == 4 ? "from " + FROM[i] : "yaw " + Math.round(yaw);
			shots.add(new Shot(cam.x, cam.y, cam.z, (float) yaw, (float) pitch, label));
		}
		String warning = farWarning(server, hook, center, radius);
		return capture(hook, shots, size).thenApply(r -> withWarning(r, warning));
	}

	private static CompletableFuture<JsonElement> capture(ViewHook hook, List<Shot> shots, Size size) {
		return hook.capture(shots, size.width, size.height, size.fov, size.daylight).thenApply(images -> {
			JsonArray out = new JsonArray();
			for (int i = 0; i < shots.size(); i++) {
				Shot s = shots.get(i);
				JsonObject o = new JsonObject();
				o.addProperty("label", s.label());
				JsonArray pos = new JsonArray();
				pos.add(Math.round(s.x() * 100) / 100.0);
				pos.add(Math.round(s.y() * 100) / 100.0);
				pos.add(Math.round(s.z() * 100) / 100.0);
				o.add("pos", pos);
				o.addProperty("yaw", Math.round(s.yaw() * 10) / 10.0);
				o.addProperty("pitch", Math.round(s.pitch() * 10) / 10.0);
				o.addProperty("png", Base64.getEncoder().encodeToString(images.get(i)));
				out.add(o);
			}
			JsonObject result = new JsonObject();
			result.addProperty("width", size.width);
			result.addProperty("height", size.height);
			result.add("shots", out);
			return result;
		});
	}

	private record Size(int width, int height, int fov, boolean daylight) {
	}

	private static Size size(JsonObject params) {
		int w = Params.integer(params, "width", 854), h = Params.integer(params, "height", 480);
		int fov = Params.integer(params, "fov", 70);
		if (w < 16 || h < 16 || w > MAX_WIDTH || h > MAX_HEIGHT) {
			throw Params.invalid("width/height must be between 16x16 and " + MAX_WIDTH + "x" + MAX_HEIGHT);
		}
		if (fov < 30 || fov > 110) {
			throw Params.invalid("fov must be 30 to 110");
		}
		return new Size(w, h, fov, Params.bool(params, "daylight", true));
	}

	private static ViewHook hook() {
		ViewHook hook = McMcpMod.viewHook();
		if (hook == null) {
			throw new RpcException(RpcException.INTERNAL_ERROR,
					"screenshots need the game client: open the world in single-player (not available on a dedicated server)");
		}
		return hook;
	}
}
