package bricktricker.servercursemanager.client;

import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import cpw.mods.forge.serverpacklocator.client.ClientCertificateManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Based on https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/client/SimpleHttpClient.java
 * @author cpw
 */
public class SimpleHttpClient {
    private static final Logger LOGGER = LogManager.getLogger();
    private final ClientSideHandler clientSideHandler;
    private final Future<Boolean> downloadJob;
    private boolean downloadSuccessful = false;
    private final String currentModpackHash;

    public SimpleHttpClient(final ClientSideHandler clientSideHandler, String currentModpackHash) {
    	this.clientSideHandler = clientSideHandler;
    	this.currentModpackHash = currentModpackHash;
        downloadJob = Executors.newSingleThreadExecutor().submit(() -> this.connectAndDownload(clientSideHandler.getRemoteServer()));
    }

    private boolean connectAndDownload(final String server) {
        final URI uri = URI.create(server);
        final InetAddress inetAddress;
        try {
            inetAddress = InetAddress.getByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new UncheckedIOException(e);
        }
        final int inetPort = uri.getPort() > 0 ? uri.getPort() : 8443;
        LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Connecting to server at "+uri.getHost());
        final ClientCertificateManager clientCertificateManager = this.clientSideHandler.getCertManager();
        final ChannelFuture remoteConnect = new Bootstrap()
                .group(new NioEventLoopGroup(1))
                .channel(NioSocketChannel.class)
                .remoteAddress(inetAddress, inetPort)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        try {
                            SslContext sslContext = SslContextBuilder.forClient()
                                    .keyManager(clientCertificateManager.getKeyPair().getPrivate(), clientCertificateManager.getCerts())
                                    .trustManager(clientCertificateManager.getCerts())
                                    .clientAuth(ClientAuth.REQUIRE)
                                    .build();
                            final SslHandler sslHandler = sslContext.newHandler(ch.alloc(), uri.getHost(), inetPort);
                            final SSLParameters sslParameters = sslHandler.engine().getSSLParameters();
                            sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
                            sslHandler.engine().setSSLParameters(sslParameters);
                            ch.pipeline().addLast("ssl", sslHandler);
                            ch.pipeline().addLast("codec", new HttpClientCodec());
                            ch.pipeline().addLast("aggregator", new HttpObjectAggregator(Integer.MAX_VALUE));
                            ch.pipeline().addLast("responseHandler", new ChannelMessageHandler());
                        } catch (SSLException e) {
                            throw new RuntimeException(e);
                        }
                    }
                })
                .connect();
        remoteConnect.awaitUninterruptibly();
        if (remoteConnect.isSuccess()) {
            final String hostName = ((InetSocketAddress) remoteConnect.channel().remoteAddress()).getHostName();
            LOGGER.debug("Connected to {}", hostName);
            LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Connected to server at "+hostName);
        } else {
            LOGGER.debug("Error occured during connection", remoteConnect.cause());
            remoteConnect.channel().eventLoop().shutdownGracefully();
        }
        // Wait for channels to close
        remoteConnect.channel().closeFuture().syncUninterruptibly();
        if (!downloadSuccessful) {
            LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Failed to complete transaction at " + uri.getHost());
            LOGGER.error("Failed to receive successful data connection from server.");
            return false;
        }
        LOGGER.debug("Successfully downloaded pack from server");
        LaunchEnvironmentHandler.INSTANCE.addProgressMessage("All mods downloaded successfully from server");
        return true;
    }

    private void requestModpack(final Channel channel) {
        final DefaultFullHttpRequest defaultFullHttpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/modpack.zip?hash=" + currentModpackHash);
        final ChannelFuture channelFuture = channel.writeAndFlush(defaultFullHttpRequest);
        channelFuture.awaitUninterruptibly();
        if (!channelFuture.isSuccess()) {
            LOGGER.debug("Error sending request packet: " + channelFuture.cause());
            channel.close();
            channel.eventLoop().shutdownGracefully();
        }
    }

    boolean waitForResult() throws ExecutionException {
        try {
            return downloadJob.get();
        } catch (InterruptedException e) {
            return false;
        }
    }

    private class ChannelMessageHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    	
    	@Override
        protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpResponse msg) {
    		if (msg.status().code() == 200) {
                LOGGER.debug("Receiving 'modpack.zip' of size {}", msg.content().readableBytes());
                LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Receiving modpack.zip");
                final Path modpack = clientSideHandler.getServerpackFolder().resolve("modpack.zip");
                try (OutputStream os = Files.newOutputStream(modpack)) {
                    msg.content().readBytes(os, msg.content().readableBytes());
                    downloadSuccessful = true;
                } catch (IOException e) {
                    LOGGER.catching(e);
                }
    		}else if(msg.status().code() == HttpResponseStatus.NOT_MODIFIED.code()) {
    			LOGGER.info("Recieved 'NOT_MODIFIED' status. Latest modpack is installed");
    			downloadSuccessful = true;
            } else {
                LOGGER.debug("Recieved {} error for 'modpack.zip'", msg.status());
            }
    		
    		ctx.close();
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
            if (evt instanceof SslHandshakeCompletionEvent) {
                final SslHandshakeCompletionEvent sslHandshake = (SslHandshakeCompletionEvent) evt;
                if (!sslHandshake.isSuccess()) {
                    final Optional<CertificateException> maybeCertException = Optional.ofNullable(sslHandshake.cause().getCause())
                            .map(Throwable::getCause)
                            .filter(t -> t instanceof CertificateException)
                            .map(CertificateException.class::cast);
                    if (maybeCertException.isPresent()) {
                        certificateError(maybeCertException.get(), (SslHandler) ctx.pipeline().get("ssl"));
                    } else {
                        sslHandshake.cause().printStackTrace();
                    }
                } else {
                    LOGGER.debug("SSL handshake complete. Requesting manifest");
                    requestModpack(ctx.channel());
                }
            }
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
            LOGGER.debug("Error in client");
            if (!(cause.getCause() instanceof SSLHandshakeException)) {
                LOGGER.catching(cause.getCause());
            }
            ctx.channel().close();
            ctx.channel().eventLoop().shutdownGracefully();
        }
    }
    
    private void certificateError(final CertificateException cert, final SslHandler ssl) {
        final Pattern errorparser = Pattern.compile("No name matching (.*) found");
        final Matcher matcher = errorparser.matcher(cert.getMessage());
        final String hostname = matcher.find() ? matcher.group(1) : "WEIRD ERROR MESSAGE " + cert.getMessage();
        LOGGER.debug("CERTIFICATE PROBLEM : Hostname {} does not match the server certificate", hostname);
        LaunchEnvironmentHandler.INSTANCE.addProgressMessage("CERTIFICATE PROBLEM: the remote host does not match it's name");
    }
}