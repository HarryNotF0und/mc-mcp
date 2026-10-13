package dev.haochen.mcmcp.client;

import dev.haochen.mcmcp.McMcpMod;
import net.fabricmc.api.ClientModInitializer;

public class McMcpClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		McMcpMod.setViewHook(new ScreenshotRenderer());
	}
}
