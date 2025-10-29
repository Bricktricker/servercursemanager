package bricktricker.servercursemanager.server;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.UUID;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.auth.x500.X500Principal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.forge.serverpacklocator.ModAccessor;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager;
import cpw.mods.forge.serverpacklocator.secure.WhitelistVerificationHelper;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager.PublicKeyData;
import cpw.mods.forge.serverpacklocator.secure.WhitelistVerificationHelper.AllowedStatus;

public class MojangCertTrustManager extends X509ExtendedTrustManager {
    
    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        checkUser(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        checkUser(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        checkUser(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        throw new UnsupportedOperationException();
    }
    
    private void checkUser(X509Certificate[] chain, String authType) throws CertificateException {
        if(authType != "RSA") {
            throw new CertificateException("Only RSA authentication allowed");
        }
        
        if(chain == null || chain.length != 1) {
            throw new CertificateException("Certificate chain of 1 is required");
        }
        X509Certificate cert = chain[0];
        cert.checkValidity(); // check its not expired
        
        if(cert.hasUnsupportedCriticalExtension()) {
            throw new CertificateException("Unsupported critical extensions found");
        }
        
        String playerUuidStr = cert.getSubjectX500Principal().getName(X500Principal.RFC1779); // returns the format CN=<uuid>
        playerUuidStr = playerUuidStr.split("=")[1];
        UUID playerUUID = UUID.fromString(playerUuidStr);
        Instant expireDate = cert.getNotAfter().toInstant();
        
        if(cert.getSigAlgName() != "SHA256withRSA") {
            throw new CertificateException("Only SHA256withRSA as signature algorithm supported");
        }
        
        // Read correct expire date from nsComment, notAfter does not contain milliseconds
        byte[] commentExt = cert.getExtensionValue("2.16.840.1.113730.1.13");
        if(commentExt == null) {
            throw new CertificateException("nsComment extension missing");
        }
        String comment = readCommentExt(commentExt);
        long commentTime = Long.valueOf(comment);
        if(Math.abs(expireDate.toEpochMilli() - commentTime) > 1000) {
            throw new CertificateException("Certificate notAfter and time and comment missmatch");
        }
        expireDate = Instant.ofEpochMilli(commentTime);
        
        PublicKey publicKey = cert.getPublicKey();
        byte[] mojangSigRaw = cert.getSignature();
        PublicKeyData keyData = new PublicKeyData(publicKey, expireDate, mojangSigRaw);
        
        try {
            ProfileKeyPairBasedSecurityManager.getInstance().validatePublicKey(keyData, playerUUID);
        } catch (Exception e) {
            throw new CertificateException(e.getMessage());
        }
        
        AllowedStatus whitelistStatus = WhitelistVerificationHelper.getInstance().isAllowed(playerUUID);
        if (whitelistStatus == AllowedStatus.REJECTED) {
            LOGGER.warn("Player {} attempted to download modpack, but is not whitelisted!", playerUUID);
            throw new CertificateException("Player not accepted");
        } else if (whitelistStatus == AllowedStatus.NOT_READY) {
            LOGGER.warn("Player {} attempted to download modpack, whitelist is not loaded yet!", playerUUID);
            throw new CertificateException("whitelist not ready");
        }
        
        LOGGER.info("Player {} requested the modpack", ModAccessor.resolveName(playerUUID));
    }
    
    // parses the comment extension, needs to be improved
    private static String readCommentExt(byte[] ext) throws CertificateException {
        if(ext[0] != 0x04) {
            // No octet string
            throw new CertificateException("Invalid comment extension");
        }
        
        if(ext[1] >= 0x80) {
            // TODO: implement multi byte length value
            throw new UnsupportedOperationException("multi byte length value not implemented");
        }
        
        int octedLength = ext[1];
        if(ext.length - 2 != octedLength) {
            throw new CertificateException("Invalid comment length");
        }
        
        if(ext[2] != 0x16) {
            // Not a IA5String
            throw new CertificateException("Invalid comment extension");
        }
        
        if(ext[3] >= 0x80) {
            // TODO: implement multi byte length value
            throw new UnsupportedOperationException("multi byte length value not implemented");
        }
        
        int stringLen = ext[3];
        if(ext.length - 4 != stringLen) {
            throw new CertificateException("Invalid comment length");
        }
        
        return new String(ext, 4, stringLen, StandardCharsets.US_ASCII);
    }

}
