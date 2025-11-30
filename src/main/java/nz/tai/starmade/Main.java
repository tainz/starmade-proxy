package nz.tai.starmade;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StarMade TCP+UDP proxy proof-of-concept using Netty 4.
 *
 * Frontend listen: TCP/UDP  localhost:4243
 * Backend target:  TCP/UDP  localhost:4242
 */
public class Main {

    // TCP config
    private static final String BACKEND_HOST = "localhost";
    private static final int BACKEND_TCP_PORT = 4242;
    private static final int FRONTEND_TCP_PORT = 4243;

    // UDP config
    private static final int BACKEND_UDP_PORT = 4242;
    private static final int FRONTEND_UDP_PORT = 4243;

    // Protocol constants
    private static final byte SIGNATURE = 0x2A; // 42
    private static final byte LOGIN_COMMAND_ID = 0;
    private static final int MAX_FRAME_SIZE = 10 * 1024 * 1024; // 10 MB

    public static void main(String[] args) throws InterruptedException {
        // TCP event loops
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        // UDP event loop
        EventLoopGroup udpGroup = new NioEventLoopGroup(1);

        try {
            // Start TCP proxy
            ServerBootstrap tcpBootstrap = new ServerBootstrap();
            tcpBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            // TCP framing: 4-byte length field, big-endian (client -> server)
                            p.addLast("frameDecoder",
                                    new LengthFieldBasedFrameDecoder(
                                            MAX_FRAME_SIZE,
                                            0, // length field offset
                                            4, // length field length
                                            0, // length adjustment
                                            0  // do NOT strip length; keep it for forwarding
                                    ));
                            // Login sniffer (does not modify buffer, only peeks)
                            p.addLast("loginSniffer", new LoginSnifferHandler());
                            // Forward everything to backend
                            p.addLast("tcpFrontend", new TcpFrontendHandler(BACKEND_HOST, BACKEND_TCP_PORT));
                        }
                    });

            ChannelFuture tcpBindFuture = tcpBootstrap.bind(FRONTEND_TCP_PORT).sync();
            System.out.println("TCP proxy listening on localhost:" + FRONTEND_TCP_PORT);

            // Shared UDP mapping: client address -> backend channel
            Map<InetSocketAddress, Channel> clientToBackend = new ConcurrentHashMap<>();
            // Reverse mapping: backend channel id -> client address
            Map<ChannelId, InetSocketAddress> backendToClient = new ConcurrentHashMap<>();

            // Start UDP proxy
            Bootstrap udpBootstrap = new Bootstrap();
            udpBootstrap.group(udpGroup)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(DatagramChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast("udpFrontend", new UdpFrontendHandler(
                                    BACKEND_HOST,
                                    BACKEND_UDP_PORT,
                                    clientToBackend,
                                    backendToClient
                            ));
                        }
                    });

            ChannelFuture udpBindFuture = udpBootstrap.bind(FRONTEND_UDP_PORT).sync();
            System.out.println("UDP proxy listening on localhost:" + FRONTEND_UDP_PORT);

            // Wait until the proxy channels are closed.
            tcpBindFuture.channel().closeFuture().sync();
            udpBindFuture.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            udpGroup.shutdownGracefully();
        }
    }

    /**
     * Inbound handler on the frontend TCP channel:
     * - Creates a backend TCP connection on first use
     * - Forwards all frames (with length prefix) to backend
     */
    private static class TcpFrontendHandler extends ChannelInboundHandlerAdapter {

        private final String remoteHost;
        private final int remotePort;
        private volatile Channel backendChannel;

        TcpFrontendHandler(String remoteHost, int remotePort) {
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            // Create outbound connection to backend using same event loop
            Bootstrap b = new Bootstrap();
            b.group(ctx.channel().eventLoop())
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            // Backend side: treat stream as opaque, no framing; just relay bytes
                            p.addLast("tcpBackend", new TcpBackendHandler(ctx.channel()));
                        }
                    });

            ChannelFuture f = b.connect(remoteHost, remotePort);
            backendChannel = f.channel();

            f.addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    System.err.println("Failed to connect to backend TCP " +
                            remoteHost + ":" + remotePort);
                    ctx.close();
                }
            });
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel backend = backendChannel;
            if (backend != null && backend.isActive()) {
                backend.writeAndFlush(msg);
            } else {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (backendChannel != null) {
                backendChannel.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    /**
     * Inbound handler on the backend TCP channel:
     * - Forwards all bytes back to the original client.
     */
    private static class TcpBackendHandler extends ChannelInboundHandlerAdapter {

        private final Channel frontendChannel;

        TcpBackendHandler(Channel frontendChannel) {
            this.frontendChannel = frontendChannel;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (frontendChannel.isActive()) {
                frontendChannel.writeAndFlush(msg);
            } else {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (frontendChannel.isActive()) {
                frontendChannel.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    /**
     * Login sniffer:
     * - Runs after framing, sees full packet (length + header + payload).
     * - Peeks into the buffer without changing readerIndex, so forwarding stays intact.
     * - If commandId == 0, parses param 0 as username and logs it.
     */
    @ChannelHandler.Sharable
    private static class LoginSnifferHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                try {
                    // Need at least: 4-byte length + 5-byte header
                    if (buf.readableBytes() >= 4 + 5) {
                        int baseIdx = buf.readerIndex();

                        // Length (we don't really need it for sniffing)
                        int frameLength = buf.getInt(baseIdx);

                        int headerStart = baseIdx + 4;
                        short signature = buf.getUnsignedByte(headerStart);

                        if (signature == (SIGNATURE & 0xFF)) {
                            // short packetId = buf.getShort(headerStart + 1); // not used
                            short commandId = buf.getUnsignedByte(headerStart + 3);

                            if (commandId == (LOGIN_COMMAND_ID & 0xFF)) {
                                // Login payload begins after 5-byte header
                                int payloadStart = headerStart + 5;
                                int minSize = (payloadStart - baseIdx) + 4; // paramCount

                                if (buf.readableBytes() >= minSize) {
                                    int paramCount = buf.getInt(payloadStart);
                                    if (paramCount > 0) {
                                        int p0TypeIdx = payloadStart + 4;
                                        if (buf.readableBytes() >= (p0TypeIdx - baseIdx) + 1 + 2) {
                                            int p0Type = buf.getUnsignedByte(p0TypeIdx);
                                            if (p0Type == 4) { // string
                                                int p0LenIdx = p0TypeIdx + 1;
                                                int strLen = buf.getUnsignedShort(p0LenIdx);

                                                int totalNeeded = (p0LenIdx - baseIdx) + 2 + strLen;
                                                if (buf.readableBytes() >= totalNeeded) {
                                                    int strStart = p0LenIdx + 2;
                                                    byte[] nameBytes = new byte[strLen];
                                                    buf.getBytes(strStart, nameBytes);
                                                    String username = new String(nameBytes, StandardCharsets.UTF_8);
                                                    System.out.println("[TCP] Login Detected: " + username);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Swallow sniffing errors but do not break proxying
                    e.printStackTrace();
                }

                // Pass the original buffer downstream unchanged
                ctx.fireChannelRead(msg);
            } else {
                ctx.fireChannelRead(msg);
            }
        }
    }

    /**
     * UDP frontend handler:
     * - Receives packets from clients on the proxy port.
     * - Maintains a mapping from client address to backend DatagramChannel.
     * - Forwards packets to backend using a per-client backend channel.
     */
    private static class UdpFrontendHandler extends SimpleChannelInboundHandler<DatagramPacket> {

        private final String backendHost;
        private final int backendPort;
        private final Map<InetSocketAddress, Channel> clientToBackend;
        private final Map<ChannelId, InetSocketAddress> backendToClient;

        UdpFrontendHandler(String backendHost,
                           int backendPort,
                           Map<InetSocketAddress, Channel> clientToBackend,
                           Map<ChannelId, InetSocketAddress> backendToClient) {
            this.backendHost = backendHost;
            this.backendPort = backendPort;
            this.clientToBackend = clientToBackend;
            this.backendToClient = backendToClient;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            InetSocketAddress clientAddr = packet.sender();
            Channel proxyChannel = ctx.channel(); // the listening UDP channel
            InetSocketAddress backendAddr = new InetSocketAddress(backendHost, backendPort);

            // Get or create backend channel for this client
            Channel backendChannel = clientToBackend.get(clientAddr);

            if (backendChannel == null || !backendChannel.isActive()) {
                // Create new backend DatagramChannel for this client
                Bootstrap b = new Bootstrap();
                b.group(proxyChannel.eventLoop())
                        .channel(NioDatagramChannel.class)
                        .option(ChannelOption.SO_REUSEADDR, true)
                        .handler(new ChannelInitializer<DatagramChannel>() {
                            @Override
                            protected void initChannel(DatagramChannel ch) {
                                ch.pipeline().addLast("udpBackend",
                                        new UdpBackendHandler(
                                                proxyChannel,
                                                clientToBackend,
                                                backendToClient
                                        ));
                            }
                        });

                ChannelFuture cf = b.connect(backendAddr);
                backendChannel = cf.channel();

                // Store mappings preemptively (will be removed on failure/close)
                clientToBackend.put(clientAddr, backendChannel);
                backendToClient.put(backendChannel.id(), clientAddr);

                // Forward the current packet once the backend is connected
                ByteBuf contentCopy = packet.content().retainedDuplicate();

                cf.addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        future.channel().writeAndFlush(
                                new DatagramPacket(contentCopy, backendAddr)
                        );
                    } else {
                        System.err.println("Failed to connect to backend UDP " +
                                backendAddr + " for client " + clientAddr);
                        contentCopy.release();
                        clientToBackend.remove(clientAddr, future.channel());
                        backendToClient.remove(future.channel().id());
                    }
                });
            } else {
                // Backend channel already exists; just forward
                backendChannel.writeAndFlush(
                        new DatagramPacket(packet.content().retainedDuplicate(), backendAddr)
                );
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    /**
     * UDP backend handler:
     * - Receives packets from backend server.
     * - Looks up the original client address and forwards packets back.
     * - Cleans up mappings when backend channel closes.
     */
    private static class UdpBackendHandler extends SimpleChannelInboundHandler<DatagramPacket> {

        private final Channel proxyChannel;
        private final Map<InetSocketAddress, Channel> clientToBackend;
        private final Map<ChannelId, InetSocketAddress> backendToClient;

        UdpBackendHandler(Channel proxyChannel,
                          Map<InetSocketAddress, Channel> clientToBackend,
                          Map<ChannelId, InetSocketAddress> backendToClient) {
            this.proxyChannel = proxyChannel;
            this.clientToBackend = clientToBackend;
            this.backendToClient = backendToClient;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            InetSocketAddress clientAddr = backendToClient.get(ctx.channel().id());
            if (clientAddr != null && proxyChannel.isActive()) {
                proxyChannel.writeAndFlush(
                        new DatagramPacket(packet.content().retainedDuplicate(), clientAddr)
                );
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            InetSocketAddress clientAddr = backendToClient.remove(ctx.channel().id());
            if (clientAddr != null) {
                clientToBackend.remove(clientAddr, ctx.channel());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}