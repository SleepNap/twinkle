package org.gms.net.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import org.gms.diagnostics.PacketTrace;
import org.gms.net.encryption.CipherPair;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.HandlerRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证抓包器实际挂在 NetworkSession 的收包分发前与发包加密前。 */
public final class NetworkSessionPacketTraceTest {

    @Test
    public void capturesInboundAndOutboundPlainProtocolPayloads() {
        HandlerRegistry registry = new HandlerRegistry();
        registry.register(RecvOpcode.ITEM_MOVE, (session, packet) -> {
            ByteArrayOutPacket response = new ByteArrayOutPacket();
            response.writeShort(SendOpcode.PING.getValue());
            response.writeInt(77);
            session.send(response);
        });
        NetworkSession session = new NetworkSession(registry, new CipherPair((short) 83));
        EmbeddedChannel channel = new EmbeddedChannel(session);
        try {
            session.startPacketTrace(new PacketTrace.Config(PacketTrace.FilterMode.EXCLUDE,
                    Set.of(PacketTrace.Direction.INBOUND, PacketTrace.Direction.OUTBOUND),
                    Set.of(), 1024));

            ByteArrayOutPacket request = new ByteArrayOutPacket();
            request.writeShort(RecvOpcode.ITEM_MOVE.getValue());
            request.writeInt(1234);
            channel.writeInbound(new ByteArrayInPacket(request.getBytes()));

            PacketTrace.Snapshot snapshot = session.packetTraceSnapshot(0, 20);
            assertThat(snapshot.events()).extracting(PacketTrace.Event::direction)
                    .containsExactly(PacketTrace.Direction.INBOUND, PacketTrace.Direction.OUTBOUND);
            assertThat(snapshot.events()).extracting(PacketTrace.Event::opcodeName)
                    .containsExactly("ITEM_MOVE", "PING");
            assertThat(snapshot.events()).allSatisfy(event ->
                    assertThat(event.payloadHex()).startsWith(
                            event.direction() == PacketTrace.Direction.INBOUND ? "47 00" : "11 00"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
