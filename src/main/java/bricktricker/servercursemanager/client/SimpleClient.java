package bricktricker.servercursemanager.client;

import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.PacketFilter;
import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class SimpleClient {

	private static final Logger LOGGER = LogManager.getLogger();
	private final ClientSideHandler clientSideHandler;
	private final Future<Boolean> downloadJob;

	public SimpleClient(final ClientSideHandler clientSideHandler, byte[] currentModpackHash) {
		this.clientSideHandler = clientSideHandler;
		downloadJob = Executors.newSingleThreadExecutor().submit(() -> this.downloadModpack(clientSideHandler.getRemoteServer(), currentModpackHash));
	}

	private boolean downloadModpack(String server, byte[] currentModpackHash) {
		LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Connecting to server at " + server);

		// URI.create needs a scheme
		if(!server.contains("//")) {
			server = "scm://" + server;
		}

		final URI uri = URI.create(server);
		final InetAddress inetAddress;
		try {
			inetAddress = InetAddress.getByName(uri.getHost());
		}catch(UnknownHostException e) {
			throw new UncheckedIOException(e);
		}
		final int inetPort = uri.getPort() > 0 ? uri.getPort() : 4148;

		final Path modpack = clientSideHandler.getServerpackFolder().resolve("modpack.zip");
		ClientChannel requestHandler = new ClientChannel(currentModpackHash, modpack);

		final ChannelFuture remoteConnect = new Bootstrap().group(new NioEventLoopGroup(1)).channel(NioSocketChannel.class).remoteAddress(inetAddress, inetPort)
				.option(ChannelOption.SO_KEEPALIVE, true).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000).handler(new ChannelInitializer<SocketChannel>() {

					@Override
					protected void initChannel(final SocketChannel ch) {
						ch.pipeline().addLast("filter", new PacketFilter(20 * 1024 * 1024)); // Max packet size is 20MiB
						ch.pipeline().addLast("requestHandler", requestHandler);
					}
				}).connect();

		remoteConnect.awaitUninterruptibly();
		if(remoteConnect.isSuccess()) {
			final String hostName = ((InetSocketAddress) remoteConnect.channel().remoteAddress()).getHostName();
			LOGGER.debug("Connected to {}", hostName);
			LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Connected to server at " + hostName);
		}else {
			LOGGER.debug("Error occured during connection", remoteConnect.cause());
			remoteConnect.channel().eventLoop().shutdownGracefully();
		}
		// Wait for channels to close
		remoteConnect.channel().closeFuture().syncUninterruptibly();
		if(!requestHandler.wasSuccessful()) {
			LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Failed to complete download at " + server);
			LOGGER.error("Failed to receive successful data connection from server.");
			return false;
		}
		LOGGER.debug("Successfully downloaded pack from server");
		LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Downloaded modpack.zip from server");
		return true;
	}

	boolean waitForResult() throws ExecutionException {
		try {
			return downloadJob.get();
		}catch(InterruptedException e) {
			return false;
		}
	}
}
