package nz.tai.starmade.tcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import nz.tai.starmade.common.BackpressureHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TCP frontend handler that accepts client connections and forwards traffic to the backend server.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Creates backend connection on first client connection</li>
 *   <li>Disables AUTO_READ until backend is ready to prevent race conditions</li>
 *   <li>Forwards all TCP frames (with length prefix) to backend</li>
 *   <li>Coordinates backpressure between client and backend channels</li>
 * </ul>
 *
 * <p>Thread safety: All methods are called from the same Netty event loop thread.
 */
public final class TcpFrontendHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(TcpFrontendHandler.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private final String remoteHost;
    private final int remotePort;
    private volatile Channel backendChannel;

    public TcpFrontendHandler(String remoteHost, int remotePort) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        connectToBackend(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        var backend = backendChannel;
        if (backend != null && backend.isActive()) {
            backend.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    logger.error("[TCP] Failed to write to backend for client {}: {}",
                            ctx.channel().remoteAddress(), future.cause().getMessage());
                    ctx.close();
                }
            });
        } else {
            logger.warn("[TCP] Dropped packet for client {} because backend not ready",
                    ctx.channel().remoteAddress());
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.debug("[TCP] Client disconnected: {}", ctx.channel().remoteAddress());
        closeBackendChannel();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("[TCP Frontend] Exception for client {}: {}",
                ctx.channel().remoteAddress(), cause.getMessage(), cause);
        ctx.close();
    }

    /**
     * Establishes connection to backend server.
     *
     * <p>CRITICAL: Disables AUTO_READ on the frontend channel until backend connection succeeds.
     * This prevents the race condition that caused 10s login delays.
     */
    private void connectToBackend(ChannelHandlerContext ctx) {
        var frontendChannel = ctx.channel();
        frontendChannel.config().setAutoRead(false);

        var bootstrap = new Bootstrap();
        bootstrap.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        // Backpressure: pause frontend when backend is not writable
                        pipeline.addLast("backpressure", new BackpressureHandler(frontendChannel));
                        // Relay bytes back to frontend
                        pipeline.addLast("tcpBackend", new TcpBackendHandler(frontendChannel));
                    }
                });

        ChannelFuture connectFuture = bootstrap.connect(remoteHost, remotePort);
        backendChannel = connectFuture.channel();

        connectFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                frontendChannel.config().setAutoRead(true);
                logger.info("[TCP] Backend connection established for client {}",
                        frontendChannel.remoteAddress());
            } else {
                logger.error("[TCP] Failed to connect to backend {}:{} for client {}: {}",
                        remoteHost, remotePort, frontendChannel.remoteAddress(),
                        future.cause().getMessage(), future.cause());
                frontendChannel.close();
            }
        });
    }

    private void closeBackendChannel() {
        if (backendChannel != null) {
            backendChannel.close();
            backendChannel = null;
        }
    }
}