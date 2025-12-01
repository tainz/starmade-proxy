package nz.tai.starmade.tcp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TCP backend handler that receives responses from the backend server and forwards them to clients.
 *
 * <p>This handler treats the backend stream as opaque bytes (no framing) and simply relays
 * everything back to the original client connection.
 *
 * <p>Thread safety: All methods are called from the same Netty event loop thread.
 */
public final class TcpBackendHandler extends ChannelInboundHandlerAdapter {
  private static final Logger logger = LoggerFactory.getLogger(TcpBackendHandler.class);

  private final Channel frontendChannel;

  public TcpBackendHandler(Channel frontendChannel) {
    this.frontendChannel = frontendChannel;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (frontendChannel.isActive()) {
      frontendChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
        if (!future.isSuccess()) {
          logger.error("[TCP] Failed to write to frontend client {}: {}",
              frontendChannel.remoteAddress(), future.cause().getMessage());
          ctx.close();
        }
      });
    } else {
      ReferenceCountUtil.release(msg);
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    logger.debug("[TCP] Backend connection closed");
    if (frontendChannel.isActive()) {
      frontendChannel.close();
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.error("[TCP Backend] Exception: {}", cause.getMessage(), cause);
    ctx.close();
  }
}
