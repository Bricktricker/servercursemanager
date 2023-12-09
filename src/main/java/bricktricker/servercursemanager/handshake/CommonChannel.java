package bricktricker.servercursemanager.handshake;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraftforge.api.distmarker.Dist;

public abstract class CommonChannel extends ChannelInboundHandlerAdapter {
    
    protected static final byte[] HEADER = { 'S', 'C', 'M', '1' };
    
    private static final Logger LOGGER = LogManager.getLogger();
    
    private final Dist currentDist;
    
    protected CommonChannel() {
        this.currentDist = LaunchEnvironmentHandler.INSTANCE.getDist();
    }

    protected static ByteBuf writeHeader(ByteBufAllocator alloc, int contentLength, PacketType packetType) {
        ByteBuf buf = alloc.buffer(HEADER.length + 4 + 1 + contentLength);
        buf.writeBytes(HEADER);
        buf.writeInt(contentLength + 1);
        buf.writeByte(packetType.ordinal());
        return buf;
    }
    
    protected static byte[] readBuffer(ByteBuf buf, int maxLength) {
        int length = buf.readInt();
        if (length > maxLength) {
            LOGGER.warn("tried to read buffer with size {}, but max allowed size is {}", length, maxLength);
            length = maxLength;
        }
        byte[] readBuffer = new byte[length];
        if (length > 0) {
            buf.readBytes(readBuffer);
        }
        return readBuffer;
    }
    
    protected ChannelFuture encAndSendBuf(HandshakeData handshakeData, ChannelHandlerContext ctx, ByteBuf payload, PacketType packetType) {
        ByteBuf encBuf = this.encryptBuffer(handshakeData, payload, ctx.alloc(), packetType);
        ByteBuf sendBuf = writeHeader(ctx.alloc(), encBuf.writerIndex(), PacketType.ENCRYPTED);
        sendBuf.writeBytes(encBuf);
        return ctx.writeAndFlush(sendBuf);
    }
    
    protected ByteBuf encryptBuffer(HandshakeData handshakeData, ByteBuf buffer, ByteBufAllocator alloc, PacketType packetType) {
        Cipher cipher;
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new RuntimeException(e);
        }
        
        var keyMaterial = handshakeData.getKeyMaterial();

        byte[] ivOrig = this.currentDist.isClient() ? keyMaterial.clientIV() : keyMaterial.serverIV();
        int sequenceNumber = this.currentDist.isClient() ? handshakeData.getClientSequenceNumber() : handshakeData.getServerSequenceNumber();
        byte[] iv = Arrays.copyOf(ivOrig, ivOrig.length);
        for(int i = 0; i < iv.length; i++) {
            iv[i] ^= sequenceNumber & 0xff;   
        }
        if(this.currentDist.isClient()) {
            handshakeData.incClientSequenceNumber();
        }else {
            handshakeData.incServerSequenceNumber();
        }
        
        GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv); //128 bit auth tag length
        try {
            Key key = this.currentDist.isClient() ? keyMaterial.clientKey() : keyMaterial.serverKey();
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new RuntimeException(e);
        }
        
        // AEAD 
        byte[] aeadData = new byte[HEADER.length + 1];
        System.arraycopy(HEADER, 0, aeadData, 0, HEADER.length);
        aeadData[HEADER.length] = (byte) (sequenceNumber & 0xff);
        cipher.updateAAD(aeadData);
        
        byte[] content = new byte[buffer.writerIndex() + 1];
        content[0] = (byte) (packetType.ordinal() & 0xff);
        buffer.readBytes(content, 1, content.length - 1);
        
        byte[] cipherText;
        try {
            cipherText = cipher.doFinal(content);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new RuntimeException(e);
        }
        
        return Unpooled.wrappedBuffer(cipherText);
    }
    
    protected ByteBuf decryptBuffer(HandshakeData handshakeData, ByteBuf buffer) {
        Cipher cipher;
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new RuntimeException(e);
        }
        
        var keyMaterial = handshakeData.getKeyMaterial();

        byte[] ivOrig = this.currentDist.isDedicatedServer() ? keyMaterial.clientIV() : keyMaterial.serverIV();
        int sequenceNumber = this.currentDist.isDedicatedServer() ? handshakeData.getClientSequenceNumber() : handshakeData.getServerSequenceNumber();
        byte[] iv = Arrays.copyOf(ivOrig, ivOrig.length);
        for(int i = 0; i < iv.length; i++) {
            iv[i] ^= sequenceNumber & 0xff;   
        }
        if(this.currentDist.isDedicatedServer()) {
            handshakeData.incClientSequenceNumber();
        }else {
            handshakeData.incServerSequenceNumber();
        }
        
        GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv); //128 bit auth tag length
        try {
            Key key = this.currentDist.isClient() ? keyMaterial.serverKey() : keyMaterial.clientKey();
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new RuntimeException(e);
        }
        
        // AEAD 
        byte[] aeadData = new byte[HEADER.length + 1];
        System.arraycopy(HEADER, 0, aeadData, 0, HEADER.length);
        aeadData[HEADER.length] = (byte) (sequenceNumber & 0xff);
        cipher.updateAAD(aeadData);
        
        byte[] content = new byte[buffer.readableBytes()];
        buffer.readBytes(content);
        
        byte[] plainText;
        try {
            plainText = cipher.doFinal(content);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new RuntimeException(e);
        }
        
        return Unpooled.wrappedBuffer(plainText);
    }
}
