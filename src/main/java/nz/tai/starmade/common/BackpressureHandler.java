package nz.tai.starmade.common;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backpressure handler that controls reading from a source channel based on destination channel
 * writability.
 *
 * <p>When the destination channel becomes non-writable (buffers full), this handler pauses reading
 * from the source channel. When the destination becomes writable again, reading resumes. This
 * prevents memory exhaustion when forwarding data to slow receivers.
 *
 * <p>Thread safety: All methods are called from the same Netty event loop thread.
 */
public final class BackpressureHandler extends ChannelInboundHandlerAdapter {
  private static final Logger logger = LoggerFactory.getLogger(BackpressureHandler.class);

  private final Channel sourceChannel;

  public BackpressureHandler(Channel sourceChannel) {
    this.sourceChannel = sourceChannel;
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    boolean writable = ctx.channel().isWritable();
    sourceChannel.config().setAutoRead(writable);

    if (writable) {
      logger.debug("[Backpressure] Resumed reading from {}", sourceChannel.remoteAddress());
    } else {
      logger.info("[Backpressure] Paused reading from {} due to slow receiver",
          sourceChannel.remoteAddress());
    }

    super.channelWritabilityChanged(ctx);
  }
}
