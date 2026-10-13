package dev.haochen.mcmcp.agent;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A server-side player with no client, controlled by agent tasks. Unlike a real player (whose
 * client simulates movement), it runs its own movement physics every tick from the inputs set
 * by {@link #steer}. It joins like a normal player, so it is visible, takes damage, picks up
 * items and has its inventory saved.
 */
public final class AgentPlayer extends ServerPlayer {
	/** Remaining path waypoints (feet positions); empty when not moving. */
	private List<BlockPos> path = List.of();
	private int waypoint;

	private AgentPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
		super(server, level, profile, ClientInformation.createDefault());
	}

	public static AgentPlayer spawn(MinecraftServer server, String name, ServerLevel level, @Nullable Vec3 pos, GameType mode) {
		GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
		AgentPlayer agent = new AgentPlayer(server, level, profile);
		server.getPlayerList().placeNewPlayer(new FakeConnection(), agent, new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false));
		if (agent.isDeadOrDying()) {
			// Saved while dead: come back with full health.
			agent.setHealth(agent.getMaxHealth());
			agent.getFoodData().setFoodLevel(20);
			agent.deathTime = 0;
		}
		if (pos != null) {
			agent.teleportTo(level, pos.x, pos.y, pos.z, agent.getYRot(), agent.getXRot());
		}
		agent.setGameMode(mode);
		return agent;
	}

	public void despawn() {
		connection.onDisconnect(new DisconnectionDetails(Component.literal("agent despawned")));
	}

	@Override
	public void tick() {
		if (getServer().getTickCount() % 10 == 0) {
			// Real players do this when their movement packets arrive: keep chunks loaded around us.
			connection.resetPosition();
			serverLevel().getChunkSource().move(this);
		}
		super.tick();
		// Movement physics (what the client does for real players).
		doTick();
	}

	// ---- movement ----

	public void setPath(List<BlockPos> path) {
		this.path = path;
		this.waypoint = 0;
	}

	/** Index of the waypoint being walked to. */
	public int waypointIndex() {
		return waypoint;
	}

	public void stop() {
		path = List.of();
		waypoint = 0;
		zza = 0;
		xxa = 0;
		setJumping(false);
	}

	/**
	 * Walks towards the current waypoint. Call once per tick before the agent ticks; returns false
	 * when the path is finished.
	 */
	public boolean steer() {
		while (waypoint < path.size()) {
			BlockPos target = path.get(waypoint);
			double dx = target.getX() + 0.5 - getX(), dz = target.getZ() + 0.5 - getZ();
			double horizontal = Math.sqrt(dx * dx + dz * dz);
			double dy = target.getY() - getY();
			if (horizontal < 0.35 && Math.abs(dy) < 0.6 && (onGround() || isInWater())) {
				waypoint++;
				continue;
			}
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			setYRot(yaw);
			setYHeadRot(yaw);
			setXRot(0);
			// Slow down near the waypoint so we don't overshoot narrow ledges.
			zza = (float) Math.min(1.0, 0.3 + horizontal);
			xxa = 0;
			setJumping((dy > 0.5 && horizontal < 1.6) || isInWater());
			return true;
		}
		stop();
		return false;
	}

	/** Faces a block (for mining or placing). */
	public void lookAt(BlockPos pos) {
		Vec3 eye = getEyePosition();
		double dx = pos.getX() + 0.5 - eye.x, dy = pos.getY() + 0.5 - eye.y, dz = pos.getZ() + 0.5 - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		setYRot(yaw);
		setYHeadRot(yaw);
		setXRot(pitch);
	}
}
