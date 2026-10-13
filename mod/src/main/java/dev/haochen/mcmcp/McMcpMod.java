package dev.haochen.mcmcp;

import dev.haochen.mcmcp.api.SystemApi;
import dev.haochen.mcmcp.api.WorldApi;
import dev.haochen.mcmcp.net.McWebSocketServer;
import dev.haochen.mcmcp.net.RpcDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class McMcpMod implements ModInitializer {
	public static final String MOD_ID = "mcmcp";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static McMcpConfig config;
	private static McWebSocketServer wsServer;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(McMcpMod::startWebSocket);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopWebSocket());
	}

	public static McMcpConfig config() {
		return config;
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	private static void startWebSocket(MinecraftServer server) {
		// Re-read on every server start so edits apply without restarting the game.
		config = McMcpConfig.loadOrCreate(FabricLoader.getInstance().getConfigDir().resolve("mcmcp.json"));

		RpcDispatcher dispatcher = new RpcDispatcher(server);
		SystemApi.register(dispatcher);
		WorldApi.register(dispatcher);

		wsServer = new McWebSocketServer(new InetSocketAddress(config.host, config.port), config.token, dispatcher);
		wsServer.start();
	}

	private static void stopWebSocket() {
		if (wsServer == null) {
			return;
		}
		try {
			wsServer.stop(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		wsServer = null;
	}
}
