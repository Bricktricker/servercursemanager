package bricktricker.servercursemanager.client;

import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.security.auth.x500.X500Principal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.CertificateBuilder;
import bricktricker.servercursemanager.networking.PacketFilter;
import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager.ProfileKeyPair;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

public class SimpleClient {

	private static final Logger LOGGER = LogManager.getLogger();
	private final ClientSideHandler clientSideHandler;
	private final CompletableFuture<Boolean> downloadJob;

	public SimpleClient(final ClientSideHandler clientSideHandler, byte[] currentModpackHash) {
		this.clientSideHandler = clientSideHandler;
		downloadJob = CompletableFuture.supplyAsync(() -> this.downloadModpack(clientSideHandler.getRemoteServer(), currentModpackHash));
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
		
		var clientKeypair = ProfileKeyPairBasedSecurityManager.getProfileKeyPair();
		
		var clientCert = mojangToX509(clientKeypair, ProfileKeyPairBasedSecurityManager.getInstance().getPlayerUUID());
		
		try {
            LOGGER.debug("Used X509 Cert: {}", Base64.getEncoder().encodeToString(clientCert.getEncoded()));
        } catch (CertificateEncodingException e) {
            LOGGER.catching(e);
        }

		final ChannelFuture remoteConnect = new Bootstrap()
		        .group(new NioEventLoopGroup(1))
		        .channel(NioSocketChannel.class)
		        .remoteAddress(inetAddress, inetPort)
				.option(ChannelOption.SO_KEEPALIVE, true)
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
				.handler(new ChannelInitializer<SocketChannel>() {

					@Override
					protected void initChannel(final SocketChannel ch) {
					    try {
                            SslContext sslContext = SslContextBuilder.forClient()
                                    .keyManager(clientKeypair.privateKey(), clientCert)
                                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                    .clientAuth(ClientAuth.REQUIRE)
                                    .protocols("TLSv1.3")
                                    .build();
                            final SslHandler sslHandler = sslContext.newHandler(ch.alloc());
                            final SSLParameters sslParameters = sslHandler.engine().getSSLParameters();
                            sslParameters.setServerNames(null);
                            sslHandler.engine().setSSLParameters(sslParameters);
                            ch.pipeline().addLast("ssl", sslHandler);
                        } catch (SSLException e) {
                            throw new UncheckedIOException(e);
                        }
					    ch.pipeline().addLast("filter", new PacketFilter(Integer.MAX_VALUE));
						ch.pipeline().addLast("requestHandler", requestHandler);
					}
				})
				.connect();

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
	
	// converts the mojang certificate into an (invalid) X509 certificate
	private static X509Certificate mojangToX509(ProfileKeyPair playerKeys, UUID playerUUid) {
	    
	    LocalDateTime beginValid = LocalDateTime.now(TimeZone.getTimeZone("UTC").toZoneId());
        LocalDateTime stopValid = LocalDateTime.ofInstant(playerKeys.publicKeyData().expiresAt(), ZoneOffset.UTC);
        
        long stopValidMillis = playerKeys.publicKeyData().expiresAt().toEpochMilli();
        
        X500Principal subject = new X500Principal("CN=" + playerUUid.toString());
	    
	    X509Certificate transformedCert = new CertificateBuilder()
	            .version(3)
	            .serialNumber(new BigInteger(64, new SecureRandom()))
	            .validity(beginValid, stopValid)
	            .issuer("CN=Mojang, O=Microsoft, C=US")
	            .subject(subject)
	            .publicKey(playerKeys.publicKeyData().key())
	            //.basicConstrains(true, 0) not a CA certificate
	            .comment(String.valueOf(stopValidMillis))
	            .forceSignature(playerKeys.publicKeyData().publicKeySignature(), CertificateBuilder.SIG_Sha256WithRSAEncryption);
	   
	    return transformedCert;
	}
}
