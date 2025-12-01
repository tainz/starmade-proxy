package nz.tai.starmade.udp;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * UDP frontend handler that receives packets from clients and forwards them to the backend server.
 *
 * <p>For each unique client address, a dedicated backend {@link DatagramChannel} is created
 * and managed via {@link UdpSessionManager}. This ensures proper routing of backend responses
 * back to the correct client.
 *
 * <p>Thread safety: All session lookups and registrations use thread-safe operations from
 * {@link UdpSessionManager}.
 */
public final class UdpFrontendHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private static final Logger logger = LoggerFactory.getLogger(UdpFrontendHandler.class);

    private final String backendHost;
    private final int backendPort;
    private final UdpSessionManager sessionManager;

    public UdpFrontendHandler(String backendHost, int backendPort, UdpSessionManager sessionManager) {
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.sessionManager = sessionManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        var clientAddr = packet.sender();
        var proxyChannel = ctx.channel();
        var backendAddr = new InetSocketAddress(backendHost, backendPort);

        var backendChannel = sessionManager.getBackendChannel(clientAddr);
        if (backendChannel == null || !backendChannel.isActive()) {
            createBackendChannel(clientAddr, proxyChannel, backendAddr, packet.content());
        } else {
            forwardToBackend(backendChannel, backendAddr, packet.content());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("[UDP Frontend] Exception: {}", cause.getMessage(), cause);
    }

    /**
     * Creates a new backend channel for the given client and forwards the initial packet.
     */
    private void createBackendChannel(
            InetSocketAddress clientAddr,
            Channel proxyChannel,
            InetSocketAddress backendAddr,
            ByteBuf content) {

        var bootstrap = new Bootstrap();
        bootstrap.group(proxyChannel.eventLoop())
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast("udpBackend",
                                new UdpBackendHandler(proxyChannel, sessionManager));
                    }
                });

        var connectFuture = bootstrap.connect(backendAddr);
        var backendChannel = connectFuture.channel();

        // Register session before connection completes to avoid race
        sessionManager.register(clientAddr, backendChannel);

        // Forward the packet once connected
        ByteBuf contentCopy = content.retainedDuplicate();
        connectFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                logger.debug("[UDP] Backend channel created for client {}", clientAddr);
                future.channel().writeAndFlush(new DatagramPacket(contentCopy, backendAddr));
            } else {
                logger.error("[UDP] Failed to connect to backend {} for client {}: {}",
                        backendAddr, clientAddr, future.cause().getMessage(), future.cause());
                contentCopy.release();
                sessionManager.removeSession(backendChannel.id());
            }
        });
    }

    /**
     * Forwards a packet to an existing backend channel.
     */
    private void forwardToBackend(Channel backendChannel, InetSocketAddress backendAddr, ByteBuf content) {
        backendChannel.writeAndFlush(
                new DatagramPacket(content.retainedDuplicate(), backendAddr)
        ).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                logger.error("[UDP] Failed to forward packet to backend: {}",
                        future.cause().getMessage());
            }
        });
    }
}