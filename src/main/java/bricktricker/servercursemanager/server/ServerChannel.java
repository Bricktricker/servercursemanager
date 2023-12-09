package bricktricker.servercursemanager.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.Utils;
import bricktricker.servercursemanager.handshake.CommonChannel;
import bricktricker.servercursemanager.handshake.HandshakeKeyDerivation;
import bricktricker.servercursemanager.handshake.HandshakeKeyDerivation.KeyMaterial;
import bricktricker.servercursemanager.handshake.PacketType;
import bricktricker.servercursemanager.handshake.ServerHandshakeData;
import bricktricker.servercursemanager.handshake.ServerHandshakeData.ValidationStatus;
import cpw.mods.forge.serverpacklocator.ModAccessor;
import cpw.mods.forge.serverpacklocator.secure.Crypt;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager;
import cpw.mods.forge.serverpacklocator.secure.SignatureValidator;
import cpw.mods.forge.serverpacklocator.secure.WhitelistVerificationHelper;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager.PublicKeyData;
import cpw.mods.forge.serverpacklocator.secure.WhitelistVerificationHelper.AllowedStatus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

public class ServerChannel extends CommonChannel {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final AttributeKey<ServerHandshakeData> HANDSHAKE_DATA = AttributeKey.newInstance("handshake");

    private final byte[] modpackData;
    private final byte[] modpackHash;

    private final SecureRandom rngGenerator;

    public ServerChannel(byte[] modpackData) {
        this.modpackData = modpackData;
        this.modpackHash = Utils.computeSha1(new ByteArrayInputStream(modpackData));
        this.rngGenerator = new SecureRandom();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf packet = (ByteBuf) msg;

        byte packetTypeIdx = packet.readByte();
        PacketType packetType = PacketType.values()[packetTypeIdx];

        try {
            if (packetType == PacketType.CLIENT_HELLO) {
                handleClientHello(ctx, packet);
            
            }else if(packetType == PacketType.ENCRYPTED) {
                ServerHandshakeData handshakeData = ctx.channel().attr(HANDSHAKE_DATA).get();
                if(handshakeData == null) {
                    LOGGER.debug("Ip {} send client certificate without sending a client hello", ctx.channel().remoteAddress());
                    sendError(ctx, "No client hello send");
                    return;
                }
                
                ByteBuf decPacket = this.decryptBuffer(handshakeData, packet);
                packetTypeIdx = decPacket.readByte();
                packetType = PacketType.values()[packetTypeIdx];
                
                if(packetType == PacketType.CERTIFICATE) {
                    handleClientCert(ctx, handshakeData, decPacket);
                }else if(packetType == PacketType.CERTIFICATE_VERIFY) {
                    handleCertValidation(ctx, handshakeData, decPacket);
                }else if(packetType == PacketType.MODPACK_REQUEST) {
                    handleClientRequest(ctx, handshakeData, decPacket);
                }else {
                    LOGGER.warn("Received unkown encrypted packet with type {}", packetType.toString());
                    ctx.close();
                    throw new UncheckedIOException(new IOException("Received unkown encrypted packet with type " + packetType.toString()));   
                }
            
            } else {
                LOGGER.warn("Received unkown packet with type {}", packetType.toString());
                ctx.close();
                throw new UncheckedIOException(new IOException("Received unkown packet with type " + packetType.toString()));
            }
        } finally {
            packet.release();
        }
    }

    private void handleClientHello(ChannelHandlerContext ctx, ByteBuf packet) {
        LOGGER.debug("handle client hello");

        ServerHandshakeData handshakeData = new ServerHandshakeData();
        ctx.channel().attr(HANDSHAKE_DATA).set(handshakeData);
        
        handshakeData.addIncommingMessageHash(packet);

        byte[] clientRandom = new byte[32];
        packet.readBytes(clientRandom);

        byte[] cPubKeyRaw = readBuffer(packet, 2048);
        
        byte[] serverRandom = new byte[32];
        this.rngGenerator.nextBytes(serverRandom);
        
        KeyPairGenerator kpg = null;
        try {
            kpg = KeyPairGenerator.getInstance("X25519");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        handshakeData.setEphemeralKeyPair(kpg.generateKeyPair());
        
        byte[] sPubKey = handshakeData.getEphemeralKeyPair().getPublic().getEncoded();
        
        int payloadLen = serverRandom.length + 4 + sPubKey.length;
        ByteBuf buf = writeHeader(ctx.alloc(), payloadLen, PacketType.SERVER_HELLO);
        buf.writeBytes(serverRandom);
        buf.writeInt(sPubKey.length);
        buf.writeBytes(sPubKey);
        handshakeData.addOutgoingMessageHash(buf);
        ctx.writeAndFlush(buf);
        
        KeyMaterial keyMaterial = HandshakeKeyDerivation.deriveKeyData(handshakeData, cPubKeyRaw);
        handshakeData.setKeyMaterial(keyMaterial);
    }

    private void handleClientCert(ChannelHandlerContext ctx, ServerHandshakeData handshakeData,  ByteBuf certificateBuf) {
        LOGGER.debug("handle client certificate");
        
        handshakeData.addIncommingMessageHash(certificateBuf);
        
        long mostSigBits = certificateBuf.readLong();
        long leastSigBits = certificateBuf.readLong();
        UUID playerUUID = new UUID(mostSigBits, leastSigBits);
        
        byte[] publicKeyRaw = readBuffer(certificateBuf, 2048);
        PublicKey publicKey = Crypt.byteToPublicKey(publicKeyRaw);

        Instant expireDate = Instant.ofEpochMilli(certificateBuf.readLong());

        byte[] mojangSigRaw = readBuffer(certificateBuf, 2048);

        final PublicKeyData keyData = new PublicKeyData(publicKey, expireDate, mojangSigRaw);
        
        handshakeData.setPlayerPublicKey(publicKey);
        handshakeData.setPlayerUUID(playerUUID);
        
        LOGGER.info("Player {} requested the modpack", ModAccessor.resolveName(playerUUID));

        try {
            ProfileKeyPairBasedSecurityManager.getInstance().validatePublicKey(keyData, playerUUID);
        } catch (Exception e) {
            LOGGER.catching(e);
            sendError(ctx, e.getMessage());
            handshakeData.setValidationStatus(ValidationStatus.REJECTED);
            return;
        }

        AllowedStatus whitelistStatus = WhitelistVerificationHelper.getInstance().isAllowed(playerUUID);
        if (whitelistStatus == AllowedStatus.REJECTED) {
            LOGGER.warn("Player {} attempted to download modpack, but is not whitelisted!", playerUUID);
            sendError(ctx, "You are not whitelisted");
            handshakeData.setValidationStatus(ValidationStatus.REJECTED);
            return;
        } else if (whitelistStatus == AllowedStatus.NOT_READY) {
            LOGGER.warn("Player {} attempted to download modpack, whitelist is not loaded yet!", playerUUID);
            sendError(ctx, "Whitelist not ready");
            handshakeData.setValidationStatus(ValidationStatus.REJECTED);
            return;
        }
        
        handshakeData.setValidationStatus(ValidationStatus.VALID_CERT);
    }

    private void handleCertValidation(ChannelHandlerContext ctx, ServerHandshakeData handshakeData, ByteBuf validateBuf) {
        LOGGER.debug("handle client certificate validation");
        
        if(handshakeData.getValidationStatus() != ValidationStatus.VALID_CERT) {
            sendError(ctx, "Certificate was rejeted or you are not whitelisted, can't handle certificate validation");
            return;
        }
        
        byte[] messageHash = handshakeData.getMessageHash();
        
        byte[] signature = readBuffer(validateBuf, 2048);
        
        boolean valid = SignatureValidator.from(handshakeData.getPlayerPublicKey(), "SHA256withRSA").validate(messageHash, signature);
        if(!valid) {
            handshakeData.setValidationStatus(ValidationStatus.REJECTED);
        }else {
            handshakeData.setValidationStatus(ValidationStatus.ACCEPTED);   
        }
        
        ByteBuf respbuf = ctx.alloc().buffer(1);
        respbuf.writeBoolean(valid);
        
        this.encAndSendBuf(handshakeData, ctx, respbuf, PacketType.SERVER_ACCEPTANCE);
    }
    
    private void handleClientRequest(ChannelHandlerContext ctx, ServerHandshakeData handshakeData, ByteBuf packHashBuf) {
        LOGGER.debug("handle client modpack request");
        
        if(handshakeData.getValidationStatus() != ValidationStatus.ACCEPTED) {
            sendError(ctx, "Modpack request rejected");
            return;
        }
        
        byte[] currentPackHash = readBuffer(packHashBuf, 32);
        LOGGER.debug("Client send hash: {}, server modpack hash: {}", ByteBufUtil.hexDump(currentPackHash), ByteBufUtil.hexDump(this.modpackHash));
        boolean hashesEqual = Arrays.equals(currentPackHash, this.modpackHash);
        
        // Send modpack back
        int responseLength = 1/* status */ + (hashesEqual ? 0 : this.modpackData.length + 4);
        ByteBuf response = ctx.alloc().buffer(responseLength);
        response.writeByte(hashesEqual ? 1 : 0);
        if(!hashesEqual) {
            response.writeInt(this.modpackData.length);
            response.writeBytes(this.modpackData);
        }
        this.encAndSendBuf(handshakeData, ctx, response, PacketType.MODPACK_RESPONSE).addListener(ChannelFutureListener.CLOSE);
        LOGGER.debug("Send modpack to client");
    }

    private static void sendError(ChannelHandlerContext ctx, String msg) {
        byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
        ByteBuf response = writeHeader(ctx.alloc(), 4 + msgBytes.length, PacketType.ERROR);
        response.writeInt(msgBytes.length);
        response.writeBytes(msgBytes);
        ctx.writeAndFlush(msgBytes).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Close the connection when an exception is raised.
        LOGGER.catching(cause);
        ctx.close();
    }

}
