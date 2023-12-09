package bricktricker.servercursemanager.handshake;

import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import bricktricker.servercursemanager.handshake.HandshakeKeyDerivation.KeyMaterial;
import io.netty.buffer.ByteBuf;

public class HandshakeData {
    
    private final MessageDigest messageHash;
    
    private KeyPair ephemeralKeyPair;
    
    private KeyMaterial keyMaterial;
    
    private int clientSequenceNumber;
    private int serverSequenceNumber;
    
    public HandshakeData() {
        try {
            this.messageHash = MessageDigest.getInstance("SHA-384");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        this.clientSequenceNumber = 0;
        this.serverSequenceNumber = 0;
    }
    
    public KeyPair getEphemeralKeyPair() {
        if(this.ephemeralKeyPair == null) {
            throw new IllegalStateException("No ephemeral key pair set");
        }
        return this.ephemeralKeyPair;
    }
    
    public void setEphemeralKeyPair(KeyPair keyPair) {
        this.ephemeralKeyPair = keyPair;
    }
    
    public void setKeyMaterial(KeyMaterial keyMaterial) {
        this.keyMaterial = keyMaterial;
    }
    
    public KeyMaterial getKeyMaterial() {
        if(this.keyMaterial == null) {
            throw new IllegalStateException("No key material set");
        }
        return this.keyMaterial;
    }

    public int getClientSequenceNumber() {
        return clientSequenceNumber;
    }

    public void incClientSequenceNumber() {
        this.clientSequenceNumber++;
    }

    public int getServerSequenceNumber() {
        return serverSequenceNumber;
    }

    public void incServerSequenceNumber() {
        this.serverSequenceNumber++;
    }

    public void addIncommingMessageHash(ByteBuf buf) {
        int beginIndx = buf.readerIndex();
        int bytesLeft = buf.capacity() - beginIndx;
        byte[] content = new byte[bytesLeft];
        buf.readBytes(content);
        buf.readerIndex(beginIndx);
        this.messageHash.update(content);
    }
    
    public void addOutgoingMessageHash(ByteBuf buf, boolean skipHeader) {
        int SKIP_BYTES = 0;
        if(!skipHeader) {
            SKIP_BYTES = 4 + 4 + 1; // HEADER.length + length int + packet type byte
        }
        int contentLen = buf.writerIndex() - SKIP_BYTES;
        byte[] content = new byte[contentLen];
        buf.readerIndex(SKIP_BYTES);
        buf.readBytes(content);
        buf.readerIndex(0);
        this.messageHash.update(content);
    }
    
    public void addOutgoingMessageHash(ByteBuf buf) {
        this.addOutgoingMessageHash(buf, false);
    }
    
    public byte[] getMessageHash() {
        MessageDigest clone;
        try {
            clone = (MessageDigest)this.messageHash.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
        return clone.digest();
    }
}
