package bricktricker.servercursemanager.server;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.Utils;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager;
import cpw.mods.forge.serverpacklocator.secure.WhitelistVerificationHelper.AllowedStatus;
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
import io.netty.util.AttributeKey;

/**
 * Based on https://github.com/OrionDevelopment/serverpacklocator/blob/4ed6a61ec664f403a426e52e7862c36bea5c8f0f/src/main/java/cpw/mods/forge/serverpacklocator/server/RequestHandler.java
 * @author cpw, OrionDevelopment
 */
public class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger LOGGER = LogManager.getLogger();
	private static final AttributeKey<byte[]> CHALLENGE_ATTRUBUTE_KEY = AttributeKey.newInstance("ClientNonce");

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
			sendReply(ctx, HttpResponseStatus.BAD_REQUEST, "Bad request");
		}
	}

	private void handleGet(final ChannelHandlerContext ctx, final FullHttpRequest msg) {
		byte[] challenge = null;

    	if (Objects.equals("/challenge", msg.uri())) {
    		if(!ctx.channel().hasAttr(CHALLENGE_ATTRUBUTE_KEY) || ctx.channel().attr(CHALLENGE_ATTRUBUTE_KEY).get() == null) {
        		challenge = new byte[16];
        		new SecureRandom().nextBytes(challenge);
        		ctx.channel().attr(CHALLENGE_ATTRUBUTE_KEY).set(challenge);
        	}else {
        		challenge = ctx.channel().attr(CHALLENGE_ATTRUBUTE_KEY).get();
        	}
    		String challengeEnc = Base64.getEncoder().encodeToString(challenge);
    		sendReply(ctx, HttpResponseStatus.OK, challengeEnc);
    		LOGGER.debug("Send challenge {} to client {}", challengeEnc, ctx.channel().remoteAddress());
    		return;
    	}

    	challenge = ctx.channel().attr(CHALLENGE_ATTRUBUTE_KEY).get();
		if(challenge == null) {
			LOGGER.warn("Client {} tried to make a request, without requesting a challenge first. Request was {}",
					ctx.channel().remoteAddress(),
					ctx.channel().id().asShortText(),
					msg.uri());
			sendReply(ctx, HttpResponseStatus.BAD_REQUEST, "Request challenge first");
    		return;
    	}
		
		if (!msg.headers().contains("Authentication")) {
            LOGGER.warn("Received unauthenticated request.");
            sendReply(ctx, HttpResponseStatus.BAD_REQUEST, "No Authentication");
            return;
        }
		
		AllowedStatus isAuthenticated = ProfileKeyPairBasedSecurityManager.getInstance().onServerConnectionRequest(msg, challenge);
		if(isAuthenticated == AllowedStatus.REJECTED) {
			LOGGER.warn("Unauthenticated request from {}", ctx.channel().remoteAddress());
			sendReply(ctx, HttpResponseStatus.UNAUTHORIZED, "Invalid Authentication");
			return;
		}else if(isAuthenticated == AllowedStatus.NOT_READY) {
			LOGGER.info("Server not yet ready");
			sendReply(ctx, new HttpResponseStatus(425, "Too Early"), "Too Early");
			return;
		}
		
		QueryStringDecoder decoder = new QueryStringDecoder(msg.uri());
		if(Objects.equals("/modpack.zip", decoder.path())) {
			LOGGER.info("Modpack request for client {}", ctx.channel().remoteAddress());
			if(decoder.parameters().containsKey("hash") && decoder.parameters().get("hash").get(0).equals(this.modpackHash)) {
				sendReply(ctx, HttpResponseStatus.NOT_MODIFIED, "Modpack not modified");
			}else {
				sendModpack(ctx);
			}
		}else {
			LOGGER.debug("Failed to understand message {}", msg);
			sendReply(ctx, HttpResponseStatus.NOT_FOUND, "Not Found");
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

	private void sendReply(final ChannelHandlerContext ctx, final HttpResponseStatus status, final String message) {
		final ByteBuf content = Unpooled.copiedBuffer(message, StandardCharsets.UTF_8);
		FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
		HttpUtil.setKeepAlive(resp, status.code() == 200);
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
		HttpUtil.setContentLength(resp, content.writerIndex());
		ctx.writeAndFlush(resp);
	}

}
