package dev.haochen.mcmcp.agent;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.ProtocolInfo;
import org.jetbrains.annotations.Nullable;

/** A connection with no socket for {@link AgentPlayer}: packets to the "client" are dropped. */
final class FakeConnection extends Connection {
	FakeConnection() {
		super(PacketFlow.SERVERBOUND);
	}

	@Override
	public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) {
	}

	@Override
	public boolean isConnected() {
		return true;
	}

	// Everything below would touch the (absent) netty channel.

	@Override
	public void flushChannel() {
	}

	@Override
	public void tick() {
	}

	@Override
	public void setupCompression(int threshold, boolean validate) {
	}

	@Override
	public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T listener) {
	}

	@Override
	public void setupOutboundProtocol(ProtocolInfo<?> protocolInfo) {
	}

	@Override
	public void setReadOnly() {
	}

	@Override
	public void handleDisconnection() {
	}

	@Override
	public void disconnect(DisconnectionDetails details) {
	}
}
