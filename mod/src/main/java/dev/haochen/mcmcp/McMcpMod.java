package dev.haochen.mcmcp;

import dev.haochen.mcmcp.api.BuildApi;
import dev.haochen.mcmcp.api.JobApi;
import dev.haochen.mcmcp.api.SystemApi;
import dev.haochen.mcmcp.api.WorldApi;
import dev.haochen.mcmcp.net.McWebSocketServer;
import dev.haochen.mcmcp.net.RpcDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class McMcpMod implements ModInitializer {
	public static final String MOD_ID = "mcmcp";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static Services services;
	private static McWebSocketServer wsServer;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(McMcpMod::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> onServerStopping());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (services != null) {
				services.jobs().tick(server);
			}
		});
	}

	/** State of the running server; only valid between server start and stop. */
	public static Services services() {
		return services;
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	private static void onServerStarted(MinecraftServer server) {
		// Re-read on every server start so edits apply without restarting the game.
		McMcpConfig config = McMcpConfig.loadOrCreate(FabricLoader.getInstance().getConfigDir().resolve("mcmcp.json"));
		services = Services.create(config, (method, params) -> {
			if (wsServer != null) {
				wsServer.notifyAll(method, params);
			}
		});

		RpcDispatcher dispatcher = new RpcDispatcher(server);
		SystemApi.register(dispatcher);
		WorldApi.register(dispatcher);
		BuildApi.register(dispatcher);
		JobApi.register(dispatcher);

		if (System.getProperty("fabric-api.gametest") != null) {
			// GameTests call the API directly; don't fight a running dev server for the port.
			return;
		}
		wsServer = new McWebSocketServer(new InetSocketAddress(config.host, config.port), config.token, dispatcher);
		wsServer.start();
	}

	private static void onServerStopping() {
		if (wsServer != null) {
			try {
				wsServer.stop(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			wsServer = null;
		}
		services = null;
	}
}
