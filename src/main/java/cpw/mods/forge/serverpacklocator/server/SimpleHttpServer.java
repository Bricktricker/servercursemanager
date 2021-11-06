package cpw.mods.forge.serverpacklocator.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.server.RequestHandler;
import bricktricker.servercursemanager.server.ServerSideHandler;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple Http Server for serving file and manifest requests to clients.
 * 
 * Based on https://github.com/OrionDevelopment/serverpacklocator/blob/4ed6a61ec664f403a426e52e7862c36bea5c8f0f/src/main/java/cpw/mods/forge/serverpacklocator/server/SimpleHttpServer.java
 * @author cpw, OrionDevelopment
 */
public class SimpleHttpServer {
    private static final Logger LOGGER = LogManager.getLogger();
    
    private SimpleHttpServer() {}

    public static void run(ServerSideHandler handler, byte[] modpackData, String password) {
    	EventLoopGroup masterGroup = new NioEventLoopGroup(1, (Runnable r) -> newDaemonThread("ServerCurseManager Master - ", r));
    	EventLoopGroup slaveGroup = new NioEventLoopGroup(1, (Runnable r) -> newDaemonThread("ServerCurseManager Slave - ", r));

        int port = handler.getPort();
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
                        ch.pipeline().addLast("codec", new HttpServerCodec());
                        ch.pipeline().addLast("aggregator", new HttpObjectAggregator(2 << 19));
                        ch.pipeline().addLast("request", new RequestHandler(modpackData, password));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.bind(port).syncUninterruptibly();
    }

    private static final AtomicInteger COUNT = new AtomicInteger(1);
    static Thread newDaemonThread(final String namepattern, Runnable r) {
        Thread t = new Thread(r);
        t.setName(namepattern +COUNT.getAndIncrement());
        t.setDaemon(true);
        return t;
    }
}