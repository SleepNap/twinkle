package org.gms.net.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.gms.net.encryption.CipherPair;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.HandlerRegistry;
import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** 在真实 Netty 会话上控制写完成时机，覆盖换线终态和失败清理。 */
public class NetworkSessionRedirectTest {
    @Test public void redirectIsTheLastPacketEvenWhileItsWriteIsPending() {
        var writes = new HeldWrites();
        var session = new NetworkSession(new HandlerRegistry(), new CipherPair((short) 83));
        var channel = new EmbeddedChannel(writes, session);
        try {
            ReferenceCountUtil.release(channel.readOutbound());
            var redirect = new ByteArrayOutPacket(); redirect.writeShort(0x10);
            var completion = session.redirect(redirect);
            session.send(new ByteArrayOutPacket());
            assertThat(writes.count).isEqualTo(1);
            assertThat(completion).isNotDone();
            assertThat(session.redirect(redirect)).isCompletedExceptionally();
            writes.pending.setSuccess();
            channel.runPendingTasks();
            assertThat(completion).isCompleted();
            assertThat(channel.isActive()).isTrue();
            session.send(new ByteArrayOutPacket());
            assertThat(writes.count).isEqualTo(1);
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test public void failedRedirectClosesTheSocket() {
        var writes = new HeldWrites();
        var session = new NetworkSession(new HandlerRegistry(), new CipherPair((short) 83));
        var channel = new EmbeddedChannel(writes, session);
        try {
            ReferenceCountUtil.release(channel.readOutbound());
            var completion = session.redirect(new ByteArrayOutPacket());
            writes.pending.setFailure(new IllegalStateException("模拟写失败"));
            channel.runPendingTasks();
            assertThat(completion).isCompletedExceptionally();
            assertThat(channel.isActive()).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test public void timeoutIsTerminalAndLateSuccessCannotRestoreConnection() {
        var writes = new HeldWrites();
        var session = new NetworkSession(new HandlerRegistry(), new CipherPair((short) 83));
        var channel = new EmbeddedChannel(writes, session);
        try {
            ReferenceCountUtil.release(channel.readOutbound());
            var completion = session.redirect(new ByteArrayOutPacket());
            channel.advanceTimeBy(11, TimeUnit.SECONDS); channel.runScheduledPendingTasks();
            assertThat(completion).isCompletedExceptionally();
            assertThat(completion.handle((ok, error) -> error).join()).isInstanceOf(TimeoutException.class);
            assertThat(channel.isActive()).isFalse();
            writes.pending.trySuccess(); channel.runPendingTasks();
            assertThat(completion).isCompletedExceptionally();
        } finally { channel.finishAndReleaseAll(); }
    }

    private static final class HeldWrites extends ChannelOutboundHandlerAdapter {
        private ChannelPromise pending;
        private int count;
        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (message instanceof OutPacket) { pending = promise; count++; }
            else context.write(message, promise);
        }
    }
}
