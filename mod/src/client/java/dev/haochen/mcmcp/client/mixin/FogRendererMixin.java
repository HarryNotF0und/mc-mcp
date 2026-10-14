package dev.haochen.mcmcp.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.haochen.mcmcp.client.ScreenshotAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No distance fog on terrain while MC-MCP takes inspection pictures (far walls would be greyed out). */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Inject(method = "setupFog", at = @At("TAIL"))
	private static void mcmcp$noTerrainFog(Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean thickFog,
										   float partialTick, CallbackInfo ci) {
		if (mode == FogRenderer.FogMode.FOG_TERRAIN && ScreenshotAccess.capturing() && camera.getFluidInCamera() == net.minecraft.world.level.material.FogType.NONE) {
			RenderSystem.setShaderFogStart(Float.MAX_VALUE);
		}
	}
}
