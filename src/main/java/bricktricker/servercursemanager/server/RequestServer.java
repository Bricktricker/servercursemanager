package bricktricker.servercursemanager.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Date;
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
		
		// Generate self signed cert
        var certPair = handler.getServerCerts();
        if(certPair != null) {
            checkCertificate(certPair.getLeft());
        }
		
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
				        SslContextBuilder sslBuilder;
				        if(certPair == null) {
				            sslBuilder = SslContextBuilder.forServer(serverKeypair.getPrivate(), serverCert);
				        }else {
				            sslBuilder = SslContextBuilder.forServer(certPair.getLeft(), certPair.getRight());
				        }
				        
                        SslContext sslContext = sslBuilder
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
	
	private static void checkCertificate(File file) {
	    try (FileInputStream fis = new FileInputStream(file)) {
	        CertificateFactory cf = CertificateFactory.getInstance("X.509");
	        Collection<? extends Certificate> certs =  cf.generateCertificates(fis);
	        
	        certs
    	        .stream()
    	        .map(X509Certificate.class::cast)
    	        .forEach(cert -> {
    	            try {
    	                cert.checkValidity();
    	            }catch (CertificateExpiredException | CertificateNotYetValidException e) {
    	               LOGGER.warn("Server certificate not valid", e);
    	               return;
    	            }
    	            
    	            Date now = new Date();
    	            Date notAfter = cert.getNotAfter();
    	            long daysLeft = (notAfter.getTime() - now.getTime()) / (1000 * 60 * 60 * 24);
    	            if(daysLeft < 7) {
    	                LOGGER.warn("Server certificate expires in {} days", daysLeft);
    	            }
    	        });

        } catch (IOException | CertificateException e) {
            LOGGER.catching(e);
        }
	}
}
