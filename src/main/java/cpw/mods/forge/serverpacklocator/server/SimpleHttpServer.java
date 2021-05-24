package cpw.mods.forge.serverpacklocator.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.server.RequestHandler;
import bricktricker.servercursemanager.server.ServerSideHandler;

import javax.net.ssl.SSLException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple Http Server for serving file and manifest requests to clients.
 * 
 * copied from https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/server/SimpleHttpServer.java
 * @author cpw
 * 
 * Changes:
 * made constructor public
 * modified port getter
 * modified logging output
 * added byte[] modpackData constructor argument
 */
public class SimpleHttpServer {
    private static final Logger LOGGER = LogManager.getLogger();
    private final ChannelFuture channel;

    private final EventLoopGroup masterGroup;
    private final EventLoopGroup slaveGroup;
    private final ServerCertificateManager certificateManager;

    public SimpleHttpServer(ServerSideHandler handler, byte[] modpackData) {
        masterGroup = new NioEventLoopGroup(1, (Runnable r) -> newDaemonThread("ServerCurseManager Master - ", r));
        slaveGroup = new NioEventLoopGroup(1, (Runnable r) -> newDaemonThread("ServerCurseManager Slave - ", r));

        int port = handler.getPort();
        certificateManager = handler.getCertificateManager();
        final ServerBootstrap bootstrap = new ServerBootstrap()
                .group(masterGroup, slaveGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new ChannelInitializer<ServerSocketChannel>() {
                    @Override
                    protected void initChannel(final ServerSocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(final ChannelHandlerContext ctx) {
                                LOGGER.info("ServerCurseManager server active on port {}", port);
                            }
                        });
                    }
                })
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        try {
                            SslContext sslContext = SslContextBuilder
                                    .forServer(certificateManager.getPrivateKey(), certificateManager.getCertificate())
                                    .trustManager(certificateManager.getCertificate())
                                    .clientAuth(ClientAuth.REQUIRE)
                                    .build();
                            ch.pipeline().addLast("ssl", sslContext.newHandler(ch.alloc()));
                            ch.pipeline().addLast("codec", new HttpServerCodec());
                            ch.pipeline().addLast("aggregator", new HttpObjectAggregator(2 << 19));
                            ch.pipeline().addLast("request", new RequestHandler(modpackData));
                        } catch (SSLException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        channel = bootstrap.bind(port).syncUninterruptibly();
    }

    private static final AtomicInteger COUNT = new AtomicInteger(1);
    static Thread newDaemonThread(final String namepattern, Runnable r) {
        Thread t = new Thread(r);
        t.setName(namepattern +COUNT.getAndIncrement());
        t.setDaemon(true);
        return t;
    }
}