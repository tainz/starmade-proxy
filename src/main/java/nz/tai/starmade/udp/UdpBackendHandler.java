package nz.tai.starmade.udp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UDP backend handler that receives packets from the backend server and forwards them to clients.
 *
 * <p>Uses {@link UdpSessionManager} to look up the original client address for each backend
 * channel and route responses accordingly.
 *
 * <p>Thread safety: All session lookups use thread-safe operations from {@link UdpSessionManager}.
 */
public final class UdpBackendHandler extends SimpleChannelInboundHandler<DatagramPacket> {
  private static final Logger logger = LoggerFactory.getLogger(UdpBackendHandler.class);

  private final Channel proxyChannel;
  private final UdpSessionManager sessionManager;

  public UdpBackendHandler(Channel proxyChannel, UdpSessionManager sessionManager) {
    this.proxyChannel = proxyChannel;
    this.sessionManager = sessionManager;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
    var clientAddr = sessionManager.getClientAddress(ctx.channel().id());
    if (clientAddr != null && proxyChannel.isActive()) {
      proxyChannel.writeAndFlush(
          new DatagramPacket(packet.content().retainedDuplicate(), clientAddr)
      ).addListener((ChannelFutureListener) future -> {
        if (!future.isSuccess()) {
          logger.error("[UDP] Failed to forward packet to client {}: {}",
              clientAddr, future.cause().getMessage());
        }
      });
    } else {
      logger.debug("[UDP] Dropped packet for inactive session (backend channel {})",
          ctx.channel().id());
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    sessionManager.removeSession(ctx.channel().id());
    logger.debug("[UDP] Backend channel closed: {}", ctx.channel().id());
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.error("[UDP Backend] Exception on channel {}: {}",
        ctx.channel().id(), cause.getMessage(), cause);
    ctx.close();
  }
}
