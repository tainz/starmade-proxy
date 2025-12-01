package nz.tai.starmade;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import nz.tai.starmade.config.ProxyConfig;
import nz.tai.starmade.protocol.LoginSnifferHandler;
import nz.tai.starmade.tcp.TcpFrontendHandler;
import nz.tai.starmade.udp.UdpFrontendHandler;
import nz.tai.starmade.udp.UdpSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * StarMade TCP+UDP proxy using Netty 4.2.x.
 *
 * <p>This proxy forwards traffic between a frontend port (client-facing) and backend port
 * (StarMade server) for both TCP and UDP protocols. It includes:
 * <ul>
 *   <li>Fixed race condition causing 10s login delay via AUTO_READ control</li>
 *   <li>Backpressure handling for slow receivers</li>
 *   <li>Optimized TCP settings (TCP_NODELAY, SO_KEEPALIVE)</li>
 *   <li>Login packet sniffing for monitoring</li>
 * </ul>
 *
 * <p>Configuration is loaded from environment variables with sensible defaults.
 * See {@link ProxyConfig} for available options.
 */
public final class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final int BOSS_THREADS = 1;
    private static final long SHUTDOWN_QUIET_PERIOD_SECONDS = 2;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 15;

    // Frame decoder constants
    private static final int LENGTH_FIELD_OFFSET = 0;
    private static final int LENGTH_FIELD_LENGTH = 4;
    private static final int LENGTH_ADJUSTMENT = 0;
    private static final int INITIAL_BYTES_TO_STRIP = 0;

    private Main() {
        // Utility class
    }

    public static void main(String[] args) throws InterruptedException {
        var config = ProxyConfig.fromEnvironment();
        logger.info("Starting StarMade proxy with config: {}", config);

        var bossGroup = new MultiThreadIoEventLoopGroup(BOSS_THREADS, NioIoHandler.newFactory());
        var workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        var udpGroup = new MultiThreadIoEventLoopGroup(BOSS_THREADS, NioIoHandler.newFactory());

        // Register shutdown hook for graceful termination
        registerShutdownHook(bossGroup, workerGroup, udpGroup);

        try {
            var tcpFuture = startTcpProxy(config, bossGroup, workerGroup);
            var udpFuture = startUdpProxy(config, udpGroup);

            logger.info("Proxy started successfully");

            // Wait until both proxies are closed
            tcpFuture.channel().closeFuture().sync();
            udpFuture.channel().closeFuture().sync();
        } finally {
            shutdownGracefully(bossGroup, workerGroup, udpGroup);
        }
    }

    private static ChannelFuture startTcpProxy(
            ProxyConfig config,
            EventLoopGroup bossGroup,
            EventLoopGroup workerGroup) throws InterruptedException {

        var bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.AUTO_READ, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // Frame decoder: 4-byte length prefix, keep length field for forwarding
                        pipeline.addLast("frameDecoder",
                                new LengthFieldBasedFrameDecoder(
                                        config.maxFrameSize(),
                                        LENGTH_FIELD_OFFSET,
                                        LENGTH_FIELD_LENGTH,
                                        LENGTH_ADJUSTMENT,
                                        INITIAL_BYTES_TO_STRIP
                                ));

                        // Login packet sniffer (read-only, does not modify buffer)
                        pipeline.addLast("loginSniffer", new LoginSnifferHandler(config));

                        // Forward to backend
                        pipeline.addLast("tcpFrontend",
                                new TcpFrontendHandler(config.backendHost(), config.backendTcpPort()));
                    }
                });

        var future = bootstrap.bind(config.frontendTcpPort()).sync();
        logger.info("TCP proxy listening on localhost:{}", config.frontendTcpPort());
        return future;
    }

    private static ChannelFuture startUdpProxy(
            ProxyConfig config,
            EventLoopGroup udpGroup) throws InterruptedException {

        var sessionManager = new UdpSessionManager();

        var bootstrap = new Bootstrap();
        bootstrap.group(udpGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast("udpFrontend",
                                new UdpFrontendHandler(
                                        config.backendHost(),
                                        config.backendUdpPort(),
                                        sessionManager
                                ));
                    }
                });

        var future = bootstrap.bind(config.frontendUdpPort()).sync();
        logger.info("UDP proxy listening on localhost:{}", config.frontendUdpPort());
        return future;
    }

    private static void registerShutdownHook(EventLoopGroup... groups) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown hook triggered, closing event loops...");
            shutdownGracefully(groups);
        }, "shutdown-hook"));
    }

    private static void shutdownGracefully(EventLoopGroup... groups) {
        for (var group : groups) {
            if (group != null) {
                group.shutdownGracefully(
                        SHUTDOWN_QUIET_PERIOD_SECONDS,
                        SHUTDOWN_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );
            }
        }
    }
}