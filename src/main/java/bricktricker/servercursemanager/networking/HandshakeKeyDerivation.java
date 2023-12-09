package bricktricker.servercursemanager.networking;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class HandshakeKeyDerivation {
    
    public static KeyMaterial deriveKeyData(NetworkData networkData, byte[] otherPublicKey) {
        byte[] sharedSecret = null;
        try {
            KeyFactory kf = KeyFactory.getInstance("XDH");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(otherPublicKey);
            PublicKey cPubKey = kf.generatePublic(keySpec);
            
            KeyAgreement ka = KeyAgreement.getInstance("XDH");
            ka.init(networkData.getEphemeralKeyPair().getPrivate());
            ka.doPhase(cPubKey, true);

            sharedSecret = ka.generateSecret();
        }catch(NoSuchAlgorithmException | InvalidKeyException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
        
        HexFormat hexFormat = HexFormat.of();
        
        byte[] earlySecret = extract(new byte[1], new byte[48]);
        
        // Empty SHA384 hash
        byte[] emptyHash = hexFormat.parseHex("38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b");
        
        byte[] derivedSecret = expand(earlySecret, "derived", emptyHash, 48);
        byte[] handshakeSecret = extract(derivedSecret, sharedSecret);
        
        byte[] helloHash = networkData.getMessageHash();
        byte[] csecret = expand(handshakeSecret, "c hs traffic", helloHash, 48);
        byte[] ssecret = expand(handshakeSecret, "s hs traffic", helloHash, 48);
        
        byte[] clientHandshakeKey = expand(csecret, "key", null, 32);
        byte[] serverHandshakeKey = expand(ssecret, "key", null, 32);
        byte[] clientHandshakeIV = expand(csecret, "iv", null, 12);
        byte[] serverHandshakeIV = expand(ssecret, "iv", null, 12);
        
        Key clientKey = new SecretKeySpec(clientHandshakeKey, "AES");
        Key serverKey = new SecretKeySpec(serverHandshakeKey, "AES");
        
        return new KeyMaterial(clientKey, clientHandshakeIV, serverKey, serverHandshakeIV);
    }

    private static byte[] extract(byte[] salt, byte[] keyMaterial) {
        Mac mac = getMacInstance(salt);
        return mac.doFinal(keyMaterial);
    }
    
    private static byte[] expand(byte[] key, String label, byte[] ctx, int len) {
        Mac mac = getMacInstance(key);
        
        if(ctx == null) {
            ctx = new byte[0];
        }
        
        // build info[]
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 16bit int len
        baos.write((len >> 8) & 0xff);
        baos.write(len & 0xff);
        
        label = "scm10 " + label;
        byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
        baos.write(labelBytes.length & 0xff);
        baos.writeBytes(labelBytes);
        
        baos.write(ctx.length & 0xff);
        baos.writeBytes(ctx);
        
        byte[] info = baos.toByteArray();
        
        byte[] lastBlock = new byte[0];
        
        baos = new ByteArrayOutputStream();
        
        int step = 1;
        while(baos.size() < len) {
            mac.update(lastBlock);
            mac.update(info);
            mac.update((byte) (step & 0xff));
            
            lastBlock = mac.doFinal();
            
            try {
                baos.write(lastBlock);
            } catch (IOException e) {
                // Should not happen
                e.printStackTrace();
            }
            
            step++;
        }
        
        byte[] outArray = baos.toByteArray();
        if(outArray.length > len) {
            outArray = Arrays.copyOf(outArray, len);
        }
        
        return outArray;
    }
    
    private static Mac getMacInstance(byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA384");
            mac.init(new SecretKeySpec(secret, "HmacSHA384"));
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }
    
    public record KeyMaterial(Key clientKey, byte[] clientIV, Key serverKey, byte[] serverIV) {}
}
