package dev.haochen.mcmcp.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.haochen.mcmcp.view.ViewHook;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;

import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Renders the world from arbitrary camera positions: the camera entity is swapped for an
 * invisible marker, the main render target (and the level renderer's auxiliary targets) is
 * temporarily resized to the requested resolution, and only the level is rendered (no HUD,
 * hand or block outline). The main target is used rather than a separate one because the level
 * renderer rebinds it part-way through a frame. Runs on the render thread, between frames.
 */
final class ScreenshotRenderer implements ViewHook {
	/** Frames rendered per shot so sections that just became visible get compiled and uploaded. */
	private static final int WARMUP_FRAMES = 6;
	/** While true, terrain fog is switched off (see FogRendererMixin): inspection pictures should show the far side clearly. */
	static volatile boolean capturing;

	@Override
	public int renderDistanceBlocks() {
		return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
	}

	@Override
	public CompletableFuture<List<byte[]>> capture(List<Shot> shots, int width, int height, int fov, boolean daylight) {
		Minecraft mc = Minecraft.getInstance();
		return mc.submit(() -> render(mc, shots, width, height, fov, daylight));
	}

	private static List<byte[]> render(Minecraft mc, List<Shot> shots, int width, int height, int fov, boolean daylight) {
		if (mc.level == null || mc.player == null) {
			throw new IllegalStateException("no world is open in the game client");
		}
		Window window = mc.getWindow();
		int oldWidth = window.getWidth(), oldHeight = window.getHeight();
		Entity oldCamera = mc.getCameraEntity();
		int oldFov = mc.options.fov().get();
		RenderTarget target = mc.getMainRenderTarget();
		Marker camera = new Marker(EntityType.MARKER, mc.level);
		ClientLevel.ClientLevelData levelData = mc.level.getLevelData();
		long oldDayTime = levelData.getDayTime();
		List<byte[]> images = new ArrayList<>(shots.size());
		try {
			capturing = true;
			mc.gameRenderer.setRenderBlockOutline(false);
			mc.gameRenderer.setRenderHand(false);
			if (daylight) {
				// Client-side only and restored below; the server's time is untouched.
				levelData.setDayTime(6000);
			}
			mc.options.fov().set(fov);
			window.setWidth(width);
			window.setHeight(height);
			target.resize(width, height, Minecraft.ON_OSX);
			mc.gameRenderer.resize(width, height);
			mc.setCameraEntity(camera);
			for (Shot shot : shots) {
				// A marker's eye height is 0, so its position is the camera position.
				camera.setPos(shot.x(), shot.y(), shot.z());
				camera.xo = shot.x();
				camera.yo = shot.y();
				camera.zo = shot.z();
				camera.setYRot(shot.yaw());
				camera.setXRot(shot.pitch());
				camera.yRotO = shot.yaw();
				camera.xRotO = shot.pitch();
				for (int frame = 0; frame < WARMUP_FRAMES; frame++) {
					mc.gameRenderer.lightTexture().tick(); // recompute the lightmap for the (possibly changed) time
					// Same preamble as Minecraft.runTick before a frame; between frames the GL state is
					// whatever GUI rendering left behind (e.g. face culling off).
					target.bindWrite(true);
					RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
					FogRenderer.setupNoFog();
					RenderSystem.enableCull();
					mc.gameRenderer.renderLevel(DeltaTracker.ONE);
					try {
						Thread.sleep(15);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				try (NativeImage image = Screenshot.takeScreenshot(target)) {
					images.add(image.asByteArray());
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			return images;
		} finally {
			capturing = false;
			mc.setCameraEntity(oldCamera);
			mc.options.fov().set(oldFov);
			window.setWidth(oldWidth);
			window.setHeight(oldHeight);
			target.resize(oldWidth, oldHeight, Minecraft.ON_OSX);
			mc.gameRenderer.resize(oldWidth, oldHeight);
			mc.gameRenderer.setRenderBlockOutline(true);
			mc.gameRenderer.setRenderHand(true);
			levelData.setDayTime(oldDayTime);
			mc.gameRenderer.lightTexture().tick();
			target.bindWrite(true);
		}
	}
}
