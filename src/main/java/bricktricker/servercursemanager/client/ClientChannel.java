package bricktricker.servercursemanager.client;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.authlib.yggdrasil.response.KeyPairResponse;

import bricktricker.servercursemanager.networking.CommonChannel;
import bricktricker.servercursemanager.networking.NetworkData;
import bricktricker.servercursemanager.networking.HandshakeKeyDerivation;
import bricktricker.servercursemanager.networking.PacketType;
import bricktricker.servercursemanager.networking.HandshakeKeyDerivation.KeyMaterial;
import cpw.mods.forge.serverpacklocator.secure.Crypt;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

public class ClientChannel extends CommonChannel {

    private static final Logger LOGGER = LogManager.getLogger();

    private final KeyPairResponse playerKeys;
    private final UUID playerUUID;
    private final byte[] currentModpackHash;
    private final Path modpackPath;

    private NetworkData networkData;

    private boolean downloadSuccessful = false;

    public ClientChannel(/* Nullable */byte[] currentModpackHash, Path modpackPath) {
        this.playerKeys = ProfileKeyPairBasedSecurityManager.getKeyPair();
        this.playerUUID = ProfileKeyPairBasedSecurityManager.getInstance().getPlayerUUID();

        this.currentModpackHash = currentModpackHash;
        this.modpackPath = modpackPath;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        LOGGER.debug("Sending client hello");
        this.networkData = new NetworkData();
        sendClientHello(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf packet = (ByteBuf) msg;
        byte packetTypeIdx = packet.readByte();
        PacketType packetType = PacketType.values()[packetTypeIdx];

        try {
            if (packetType == PacketType.SERVER_HELLO) {
                handleServerHello(ctx, packet);
            } else if(packetType == PacketType.ENCRYPTED) {
                ByteBuf decPacket = this.decryptBuffer(networkData, packet);
                packetTypeIdx = decPacket.readByte();
                packetType = PacketType.values()[packetTypeIdx];
                
                if(packetType == PacketType.SERVER_ACCEPTANCE) {
                    handleAcceptance(ctx, decPacket);
                }else if(packetType == PacketType.MODPACK_RESPONSE) {
                    handleModpack(ctx, decPacket);
                }else {
                    LOGGER.warn("Received unkown encrypted packet with type {}", packetType.toString());
                    ctx.close();
                    throw new UncheckedIOException(
                            new IOException("Received unkown packet with type " + packetType.toString()));
                }
                
                
            } else if (packetType == PacketType.ERROR) {
                handleServerError(ctx, packet);
            } else {
                LOGGER.warn("Received unkown packet with type {}", packetType.toString());
                ctx.close();
                throw new UncheckedIOException(
                        new IOException("Received unkown encrypted packet with type " + packetType.toString()));
            }
        } finally {
            packet.release();
        }
    }

    private void sendClientHello(ChannelHandlerContext ctx) {
        KeyPairGenerator kpg = null;
        try {
            kpg = KeyPairGenerator.getInstance("X25519");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        this.networkData.setEphemeralKeyPair(kpg.generateKeyPair());
        byte[] cPubKey = this.networkData.getEphemeralKeyPair().getPublic().getEncoded();

        byte[] clientRandom = new byte[32];
        new SecureRandom().nextBytes(clientRandom);

        int payloadLen = clientRandom.length + 4 + cPubKey.length;
        ByteBuf buf = writeHeader(ctx.alloc(), payloadLen, PacketType.CLIENT_HELLO);
        buf.writeBytes(clientRandom);
        buf.writeInt(cPubKey.length);
        buf.writeBytes(cPubKey);
        this.networkData.addOutgoingMessageHash(buf);
        ctx.writeAndFlush(buf);
    }

    private void handleServerHello(ChannelHandlerContext ctx, ByteBuf packet) {
        this.networkData.addIncommingMessageHash(packet);

        byte[] serverRandom = new byte[32];
        packet.readBytes(serverRandom);

        byte[] sPubKeyRaw = readBuffer(packet, 2048);

        KeyMaterial keyMaterial = HandshakeKeyDerivation.deriveKeyData(this.networkData, sPubKeyRaw);
        this.networkData.setKeyMaterial(keyMaterial);

        // Send client certificate to server

        int contentLen = 16; // 16 bytes for the UUID

        // Public key
        PublicKey publicKey = Crypt.stringToRsaPublicKey(this.playerKeys.getPublicKey());
        byte[] publicKeyRaw = publicKey.getEncoded();
        contentLen += 4; // int for the public key len
        contentLen += publicKeyRaw.length;

        long expireDate = Instant.parse(this.playerKeys.getExpiresAt()).toEpochMilli();
        contentLen += 8; // expire date

        byte[] mojangSigRaw = this.playerKeys.getPublicKeySignature().array();
        contentLen += 4; // Mojang signature len
        contentLen += mojangSigRaw.length;

        ByteBuf certificateResp = ctx.alloc().heapBuffer(contentLen);

        certificateResp.writeLong(playerUUID.getMostSignificantBits());
        certificateResp.writeLong(playerUUID.getLeastSignificantBits());

        certificateResp.writeInt(publicKeyRaw.length);
        certificateResp.writeBytes(publicKeyRaw);

        certificateResp.writeLong(expireDate);

        certificateResp.writeInt(mojangSigRaw.length);
        certificateResp.writeBytes(mojangSigRaw);
        
        this.networkData.addOutgoingMessageHash(certificateResp, true);
        
        LOGGER.debug("Send client certificate to server");
        this.encAndSendBuf(this.networkData, ctx, certificateResp, PacketType.CERTIFICATE);

        byte[] messageHash = this.networkData.getMessageHash();
        byte[] signatureBytes = ProfileKeyPairBasedSecurityManager.getInstance().getSigningHandler().signer()
                .sign(messageHash);

        ByteBuf verifyContent = ctx.alloc().heapBuffer(4 + signatureBytes.length);
        verifyContent.writeInt(signatureBytes.length);
        verifyContent.writeBytes(signatureBytes);

        this.encAndSendBuf(networkData, ctx, verifyContent, PacketType.CERTIFICATE_VERIFY);

        LOGGER.debug("Send certificate verify to server");
    }

    private void handleAcceptance(ChannelHandlerContext ctx, ByteBuf response) {
        boolean valid = response.readBoolean();

        if (!valid) {
            LOGGER.error("The server could not validate the certificate. Can't download modpack");
            ctx.close();
            return;
        }

        LOGGER.debug("The server could validate the certificate, requesting modpack");

        ByteBuf reqBuf;
        if (this.currentModpackHash != null && this.currentModpackHash.length > 0) {
            reqBuf = ctx.alloc().buffer(4 + this.currentModpackHash.length);
            reqBuf.writeInt(this.currentModpackHash.length);
            reqBuf.writeBytes(currentModpackHash);
        } else {
            // No hash, hash length of 0
            reqBuf = ctx.alloc().buffer(4);
            reqBuf.writeInt(0);
        }

        this.encAndSendBuf(networkData, ctx, reqBuf, PacketType.MODPACK_REQUEST);
    }

    private void handleModpack(ChannelHandlerContext ctx, ByteBuf response) {
        LOGGER.debug("Received the modpack");

        byte status = response.readByte();
        if (status == 0) {
            int packLength = response.readInt();
            byte[] modpack = new byte[packLength];
            response.readBytes(modpack);
            try (OutputStream os = Files.newOutputStream(this.modpackPath)) {
                os.write(modpack);
            } catch (IOException e) {
                LOGGER.catching(e);
            }
        }
        this.downloadSuccessful = true;
        ctx.close();
    }

    private void handleServerError(ChannelHandlerContext ctx, ByteBuf packet) {
        byte[] errorBytes = readBuffer(packet, 2048);
        String error = new String(errorBytes, StandardCharsets.UTF_8);
        LOGGER.error("Received error {}", error);
        ctx.close();
        this.downloadSuccessful = false;
        throw new UncheckedIOException(new IOException(error));
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
