package bricktricker.servercursemanager.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.Utils;
import cpw.mods.forge.serverpacklocator.ModAccessor;
import cpw.mods.forge.serverpacklocator.secure.Crypt;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager;
import cpw.mods.forge.serverpacklocator.secure.WhitelistVerificationHelper;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager.PublicKeyData;
import cpw.mods.forge.serverpacklocator.secure.WhitelistVerificationHelper.AllowedStatus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

public class ServerChannel extends ChannelInboundHandlerAdapter {

	private static final Logger LOGGER = LogManager.getLogger();

	private static final AttributeKey<UUID> PLAYER_UUID_KEY = AttributeKey.newInstance("playerUUID");
	private static final AttributeKey<byte[]> CHALLENGE_BYTES = AttributeKey.newInstance("challengeBytes");

	private static final byte[] HEADER = { 'S', 'C', 'M', '1' };

	private final byte[] modpackData;
	private final byte[] modpackHash;

	public ServerChannel(byte[] modpackData) {
		this.modpackData = modpackData;
		this.modpackHash = Utils.computeSha1(new ByteArrayInputStream(modpackData));
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		ByteBuf packet = (ByteBuf) msg;

		byte packetType = packet.readByte();

		try {
			if(packetType == 1) {
				handleClientHello(ctx, packet);
			}else if(packetType == 3) {
				handleClientRequest(ctx, packet);
			}else if(packetType == 5) {
				handleClientError(ctx, packet);
			}else {
				LOGGER.warn("Received unkown packet with type {}", (int) packetType);
				ctx.close();
				throw new UncheckedIOException(new IOException("Received unkown packet with type " + (int) packetType));
			}
		}finally {
			packet.release();
		}
	}

	private void handleClientHello(ChannelHandlerContext ctx, ByteBuf packet) {
		LOGGER.debug("handle client hello");
		ByteBuf clientRandom = packet.readBytes(32);

		long mostSigBits = packet.readLong();
		long leastSigBits = packet.readLong();
		UUID playerUUID = new UUID(mostSigBits, leastSigBits);
		ctx.channel().attr(PLAYER_UUID_KEY).set(playerUUID);

		byte[] serverRandom = new byte[32];
		new SecureRandom().nextBytes(serverRandom);

		ByteBuf responseBuf = ctx.alloc().buffer(HEADER.length + 4 + 1 + serverRandom.length);
		writeHeader(responseBuf, serverRandom.length, 2);
		responseBuf.writeBytes(serverRandom);
		ctx.writeAndFlush(responseBuf);

		// xor clientRandom and serverRandom to get the challenge bytes
		for(int i = 0; i < serverRandom.length; i++) {
			serverRandom[i] ^= clientRandom.getByte(i);
		}
		ctx.channel().attr(CHALLENGE_BYTES).set(serverRandom);

		LOGGER.info("Player {} requested the modpack", ModAccessor.resolveName(playerUUID));
	}

	private void handleClientRequest(ChannelHandlerContext ctx, ByteBuf packet) {
		LOGGER.debug("handle client request");
		byte[] publicKeyRaw = readBuffer(packet, 2048);
		PublicKey publicKey = Crypt.byteToPublicKey(publicKeyRaw);

		Instant expireDate = Instant.ofEpochMilli(packet.readLong());

		byte[] mojangSigRaw = readBuffer(packet, 2048);

		final PublicKeyData keyData = new PublicKeyData(publicKey, expireDate, mojangSigRaw);
		UUID playerUUID = ctx.channel().attr(PLAYER_UUID_KEY).get();
		if(playerUUID == null) {
			LOGGER.debug("Ip {} send client request without sending a client hello", ctx.channel().remoteAddress());
			sendError(ctx, "No client hello send");
			return;
		}
		try {
			ProfileKeyPairBasedSecurityManager.getInstance().validatePublicKey(keyData, playerUUID);
		}catch(Exception e) {
			LOGGER.catching(e);
			sendError(ctx, e.getMessage());
			return;
		}

		byte[] challengeBytes = ctx.channel().attr(CHALLENGE_BYTES).get();
		if(challengeBytes == null) {
			sendError(ctx, "No client hello send");
			return;
		}

		byte[] challengeSigRaw = readBuffer(packet, 2048);
		boolean validChallenge = keyData.validator().validate(challengeBytes, challengeSigRaw);
		if(!validChallenge) {
			LOGGER.debug("The challenge signature of player {} is wrong", playerUUID);
			sendError(ctx, "Invalid challenge signature");
			return;
		}

		AllowedStatus whitelistStatus = WhitelistVerificationHelper.getInstance().isAllowed(playerUUID);
		if(whitelistStatus == AllowedStatus.REJECTED) {
			LOGGER.warn("Player {} attempted to download modpack, but is not whitelisted!", playerUUID);
			sendError(ctx, "You are not whitelisted");
			return;
		}else if(whitelistStatus == AllowedStatus.NOT_READY) {
			LOGGER.warn("Player {} attempted to download modpack, whitelist is not loaded yet!", playerUUID);
			sendError(ctx, "Whitelist not ready");
			return;
		}

		byte[] currentPackHash = readBuffer(packet, 32);
		LOGGER.debug("Client send hash: {}, server modpack hash: {}", ByteBufUtil.hexDump(currentPackHash), ByteBufUtil.hexDump(this.modpackHash));
		boolean hashesEqual = Arrays.equals(currentPackHash, this.modpackHash);

		// Send modpack back
		int responseLength = 1/* status */ + (hashesEqual ? 0 : this.modpackData.length + 4);
		ByteBuf response = ctx.alloc().buffer(HEADER.length + 4 + 1 + responseLength);
		writeHeader(response, responseLength, 4);
		response.writeByte(hashesEqual ? 1 : 0); // Status == 0 => modpack included, Status == 1 => you already have the latest version
		if(!hashesEqual) {
			response.writeInt(this.modpackData.length);
			response.writeBytes(this.modpackData);
			ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
		}else {
			ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
		}
		LOGGER.debug("Send modpack to client");
	}

	private static void handleClientError(ChannelHandlerContext ctx, ByteBuf packet) {
		byte[] errorBytes = readBuffer(packet, 256); // Max error length is 256 bytes
		String error = new String(errorBytes, StandardCharsets.UTF_8);
		UUID playerUUID = ctx.channel().attr(PLAYER_UUID_KEY).get();
		LOGGER.warn("Player with UUID {} has send error {}", playerUUID, error);
		ctx.close();
	}

	private static void sendError(ChannelHandlerContext ctx, String msg) {
		byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
		ByteBuf response = ctx.alloc().buffer(HEADER.length + 4 + 1 + 4 + msgBytes.length);
		writeHeader(response, 4 + msgBytes.length, 5);
		response.writeInt(msgBytes.length);
		response.writeBytes(msgBytes);
		ctx.writeAndFlush(msgBytes).addListener(ChannelFutureListener.CLOSE);
	}

	private static void writeHeader(ByteBuf buf, int contentLength, int packetType) {
		buf.writeBytes(HEADER);
		buf.writeInt(contentLength + 1);
		buf.writeByte(packetType);
	}

	private static byte[] readBuffer(ByteBuf buf, int maxLength) {
		int length = buf.readInt();
		if(length > maxLength) {
			LOGGER.warn("tried to read buffer with size {}, but max allowed size is {}", length, maxLength);
			length = maxLength;
		}
		byte[] readBuffer = new byte[length];
		if(length > 0) {
			buf.readBytes(readBuffer);
		}
		return readBuffer;
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		// Close the connection when an exception is raised.
		LOGGER.catching(cause);
		ctx.close();
	}

}
