package bricktricker.servercursemanager.client;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.authlib.yggdrasil.response.KeyPairResponse;

import cpw.mods.forge.serverpacklocator.secure.Crypt;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class ClientChannel extends ChannelInboundHandlerAdapter {

	private static final Logger LOGGER = LogManager.getLogger();

	private static final byte[] HEADER = { 'S', 'C', 'M', '1' };

	private final byte[] clientRandom;
	private final KeyPairResponse playerKeys;
	private final UUID playerUUID;
	private final byte[] currentModpackHash;
	private final Path modpackPath;

	private boolean downloadSuccessful = false;

	public ClientChannel(/* Nullable */byte[] currentModpackHash, Path modpackPath) {
		clientRandom = new byte[32];
		new SecureRandom().nextBytes(clientRandom);

		this.playerKeys = ProfileKeyPairBasedSecurityManager.getKeyPair();
		this.playerUUID = ProfileKeyPairBasedSecurityManager.getInstance().getPlayerUUID();

		this.currentModpackHash = currentModpackHash;
		this.modpackPath = modpackPath;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		LOGGER.debug("Sending client hello");
		ByteBuf requestBuf = ctx.alloc().buffer(HEADER.length + 4 + 1 + clientRandom.length + 16);
		writeHeader(requestBuf, clientRandom.length + 16, 1);
		requestBuf.writeBytes(clientRandom);
		requestBuf.writeLong(playerUUID.getMostSignificantBits());
		requestBuf.writeLong(playerUUID.getLeastSignificantBits());
		ctx.writeAndFlush(requestBuf);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		ByteBuf packet = (ByteBuf) msg;
		byte packetType = packet.readByte();

		try {
			if(packetType == 2) {
				handleServerChallenge(ctx, packet);
			}else if(packetType == 4) {
				handleModpack(ctx, packet);
			}else if(packetType == 5) {
				handleServerError(ctx, packet);
			}else {
				LOGGER.warn("Received unkown packet with type {}", (int) packetType);
				ctx.close();
				throw new UncheckedIOException(new IOException("Received unkown packet with type " + (int) packetType));
			}
		}finally {
			packet.release();
		}
	}

	private void handleServerChallenge(ChannelHandlerContext ctx, ByteBuf packet) {
		LOGGER.debug("Received server challenge, sending key data");
		// read server random and xor with client random to get challenge bytes
		byte[] challenge = new byte[32];
		packet.readBytes(challenge);
		for(int i = 0; i < challenge.length; i++) {
			challenge[i] ^= this.clientRandom[i];
		}

		// Public key
		PublicKey publicKey = Crypt.stringToRsaPublicKey(this.playerKeys.getPublicKey());
		byte[] publicKeyRaw = publicKey.getEncoded();

		long expireDate = Instant.parse(this.playerKeys.getExpiresAt()).toEpochMilli();

		byte[] mojangSigRaw = this.playerKeys.getPublicKeySignature().array();

		// Sign the challenge
		byte[] signatureBytes = ProfileKeyPairBasedSecurityManager.getInstance().getSigningHandler().signer().sign(challenge);

		int contentLength = 4/* publicKeyLength */ + publicKeyRaw.length + 8/* expireDate */
				+ 4/* mojangSigLength */ + mojangSigRaw.length + 4/* signatureBytesLength */ + signatureBytes.length + 4/* currentPackHashLength */;
		if(this.currentModpackHash != null && this.currentModpackHash.length > 0) {
			contentLength += this.currentModpackHash.length;
		}

		ByteBuf response = ctx.alloc().buffer(HEADER.length + 4 + 1 + contentLength);
		writeHeader(response, contentLength, 3);

		response.writeInt(publicKeyRaw.length);
		response.writeBytes(publicKeyRaw);

		response.writeLong(expireDate);

		response.writeInt(mojangSigRaw.length);
		response.writeBytes(mojangSigRaw);

		response.writeInt(signatureBytes.length);
		response.writeBytes(signatureBytes);

		if(this.currentModpackHash != null && this.currentModpackHash.length > 0) {
			response.writeInt(this.currentModpackHash.length);
			response.writeBytes(this.currentModpackHash);
		}else {
			// No hash, hash length of 0
			response.writeInt(0);
		}

		ctx.writeAndFlush(response);
		LOGGER.debug("Send key reponse");
	}

	private void handleModpack(ChannelHandlerContext ctx, ByteBuf packet) {
		LOGGER.debug("Received the modpack");
		byte status = packet.readByte();
		if(status == 0) {
			int packLength = packet.readInt();
			byte[] modpack = new byte[packLength];
			packet.readBytes(modpack);
			try(OutputStream os = Files.newOutputStream(this.modpackPath)) {
				os.write(modpack);
			}catch(IOException e) {
				LOGGER.catching(e);
			}
		}
		this.downloadSuccessful = true;
		ctx.close();
	}

	private void handleServerError(ChannelHandlerContext ctx, ByteBuf packet) {
		int errorLength = packet.readInt();
		byte[] errorBytes = new byte[errorLength];
		packet.readBytes(errorBytes);
		String error = new String(errorBytes, StandardCharsets.UTF_8);
		LOGGER.error("Received error {}", error);
		ctx.close();
		this.downloadSuccessful = false;
		throw new UncheckedIOException(new IOException(error));
	}

	private static void writeHeader(ByteBuf buf, int contentLength, int packetType) {
		buf.writeBytes(HEADER);
		buf.writeInt(contentLength + 1);
		buf.writeByte(packetType);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		// Close the connection when an exception is raised.
		LOGGER.catching(cause);
		ctx.close();
	}

	public boolean wasSuccessful() {
		return this.downloadSuccessful;
	}

}
