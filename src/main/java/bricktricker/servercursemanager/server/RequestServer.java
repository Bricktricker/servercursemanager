package bricktricker.servercursemanager.server;

import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;
import javax.security.auth.x500.X500Principal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.CertificateBuilder;
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
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

public class RequestServer {

	private static final Logger LOGGER = LogManager.getLogger();

	private RequestServer() {
	}

	public static void run(ServerSideHandler handler, byte[] modpackData) {
		EventLoopGroup masterGroup = new NioEventLoopGroup(1, (Runnable r) -> newDaemonThread("ServerCurseManager Master - ", r));
		EventLoopGroup slaveGroup = new NioEventLoopGroup(1, (Runnable r) -> newDaemonThread("ServerCurseManager Slave - ", r));
		
		// Generate a self signed cetificate for the server
		KeyPair serverKeypair = generateKeypair();
		
		LocalDateTime beginValid = LocalDateTime.now(TimeZone.getTimeZone("UTC").toZoneId());
        LocalDateTime stopValid = beginValid.plusMonths(12);
		
        X500Principal issuer = new X500Principal("CN=ServerCurseManager");
        
        X509Certificate serverCert = new CertificateBuilder()
		    .version(3)
		    .serialNumber(new BigInteger(64, new SecureRandom()))
		    .validity(beginValid, stopValid)
		    .issuer(issuer)
		    .subject(issuer)
		    .publicKey(serverKeypair.getPublic())
		    .basicConstrains(true, 0)
		    .build((RSAPrivateKey)serverKeypair.getPrivate(), CertificateBuilder.SIG_Sha256WithRSAEncryption);
		    

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
				    try {
                        SslContext sslContext = SslContextBuilder
                                .forServer(serverKeypair.getPrivate(), serverCert)
                                .trustManager(new MojangCertTrustManager())
                                .clientAuth(ClientAuth.REQUIRE)
                                .protocols("TLSv1.3")
                                .build();
                        
                        ch.pipeline().addLast("ssl", sslContext.newHandler(ch.alloc()));
                    } catch (SSLException e) {
                        throw new UncheckedIOException(e);
                    }
				    ch.pipeline().addLast("filter", new PacketFilter(2048));  // Max packet size is 2KiB
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
	
	private static KeyPair generateKeypair() {
	    KeyPairGenerator kpg;
        try {
            kpg = KeyPairGenerator.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        kpg.initialize(2048);
        return kpg.generateKeyPair();
	}
}
