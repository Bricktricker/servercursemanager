package bricktricker.servercursemanager.server;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.Utils;
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

/**
 * Based on https://github.com/OrionDevelopment/serverpacklocator/blob/4ed6a61ec664f403a426e52e7862c36bea5c8f0f/src/main/java/cpw/mods/forge/serverpacklocator/server/RequestHandler.java
 * @author cpw, OrionDevelopment
 */
public class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger LOGGER = LogManager.getLogger();

	private final byte[] modpackData;
	private final String modpackHash;

	public RequestHandler(final byte[] modpackData) {
		this.modpackData = modpackData;
		this.modpackHash = Utils.computeSha1(new ByteArrayInputStream(modpackData));
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
		/*
		if (!msg.headers().contains("Authentication")) {
            LOGGER.warn("Received unauthenticated request.");
            sendErrorReply(ctx, HttpResponseStatus.FORBIDDEN, "No Authentication");
            return;
        }
		
		Replace password auth with something better
		var hash = msg.headers().get("Authentication");
        if (!hash.equals(this.passwordHash)) {
            LOGGER.warn("Received unauthorized request.");
            sendErrorReply(ctx, HttpResponseStatus.FORBIDDEN, "No Authentication");
            return;
        }
        */
		
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
