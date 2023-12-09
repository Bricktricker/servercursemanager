package bricktricker.servercursemanager.server;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.networking.PacketFilter;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class RequestServer {

	private static final Logger LOGGER = LogManager.getLogger();

	private RequestServer() {
	}

	public static void run(ServerSideHandler handler, byte[] modpackData) {
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
					ch.pipeline().addLast("filter", new PacketFilter(2048)); // Max packet size is 2KiB
					ch.pipeline().addLast("request", new ServerChannel(modpackData));
				}
			})
			.option(ChannelOption.SO_BACKLOG, 128)
			.childOption(ChannelOption.SO_KEEPALIVE, true);
		bootstrap.bind(port).syncUninterruptibly();
	}

	private static final AtomicInteger COUNT = new AtomicInteger(1);

	private static Thread newDaemonThread(final String namepattern, Runnable r) {
		Thread t = new Thread(r);
		t.setName(namepattern + COUNT.getAndIncrement());
		t.setDaemon(true);
		return t;
	}
}
