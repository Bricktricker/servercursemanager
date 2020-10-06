package com.walnutcrasher.servercursemanager.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import javax.net.ssl.SSLException;
import sun.security.x509.X500Name;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.forge.cursepacklocator.HashChecker;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

/**
 * Based on https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/server/RequestHandler.java
 * @author cpw
 */
public class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger LOGGER = LogManager.getLogger();

	private final byte[] modpackData;
	private final String modpackHash;

	public RequestHandler(byte[] modpackData) {
		this.modpackData = modpackData;
		this.modpackHash = String.valueOf(HashChecker.computeHash(modpackData));
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
		if(Objects.equals(HttpMethod.GET, msg.method())) {
			handleGet(ctx, msg);
		}else {
			sendErrorReply(ctx, HttpResponseStatus.BAD_REQUEST, "Bad request");
		}
	}

	private void handleGet(final ChannelHandlerContext ctx, final FullHttpRequest msg) {
		QueryStringDecoder decoder = new QueryStringDecoder(msg.uri());
		if(Objects.equals("/modpack.zip", decoder.path())) {
			LOGGER.info("Modpack request for client {}", ctx.channel().remoteAddress());
			if(decoder.parameters().containsKey("hash") && decoder.parameters().get("hash").get(0).equals(this.modpackHash)) {
				sendErrorReply(ctx, HttpResponseStatus.NOT_MODIFIED, "Modpack not modified");
			}else {
				sendModpack(ctx);
			}
		}else {
			LOGGER.debug("Failed to understand message {}", msg);
			sendErrorReply(ctx, HttpResponseStatus.NOT_FOUND, "Not Found");
		}
	}

	@Override
	public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
		if(evt instanceof SslHandshakeCompletionEvent) {
			if(((SslHandshakeCompletionEvent) evt).isSuccess()) {
				SslHandler sslhandler = (SslHandler) ctx.channel().pipeline().get("ssl");
				try {
					X500Name name = (X500Name) sslhandler.engine().getSession().getPeerCertificateChain()[0].getSubjectDN();
					LOGGER.debug("Connection from {} @ {}", name.getCommonName(), ctx.channel().remoteAddress());
				}catch(IOException e) {
					LOGGER.warn("Illegal state in connection", e);
					ctx.close();
				}
			}else {
				LOGGER.warn("Disconnected unauthenticated peer at {} : {}", ctx.channel().remoteAddress(), ((SslHandshakeCompletionEvent) evt).cause().getMessage());
			}
		}
	}

	@Override
	public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
		if(!(cause.getCause() instanceof SSLException)) {
			LOGGER.warn("Error in request handler code", cause);
		}else {
			LOGGER.trace("SSL error in handling code", cause.getCause());
		}
	}

	private void sendModpack(final ChannelHandlerContext ctx) {
		final ByteBuf content = Unpooled.copiedBuffer(this.modpackData);
		FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
		HttpUtil.setKeepAlive(resp, false);
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/zip");
		resp.headers().set("filename", "modpack.zip");
		HttpUtil.setContentLength(resp, content.writerIndex());
		ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
	}

	private void sendErrorReply(final ChannelHandlerContext ctx, final HttpResponseStatus status, final String message) {
		final ByteBuf content = Unpooled.copiedBuffer(message, StandardCharsets.UTF_8);
		FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
		HttpUtil.setKeepAlive(resp, false);
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
		HttpUtil.setContentLength(resp, content.writerIndex());
		ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
	}

}
