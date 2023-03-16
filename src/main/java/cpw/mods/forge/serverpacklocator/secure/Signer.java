package cpw.mods.forge.serverpacklocator.secure;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.PrivateKey;
import java.security.Signature;

/**
 * Coped from https://github.com/cpw/serverpacklocator/blob/4496cf9ba45515b286bde1a3a79513e75b69754e/src/main/java/cpw/mods/forge/serverpacklocator/secure/Signer.java
 * @author marchermans
 * 
 */
public interface Signer {
    Logger LOGGER = LogManager.getLogger();

    byte[] sign(byte[] payload);

    static Signer from(PrivateKey privateKey, String algorithmName) {
        return (payload) -> {
            try {
                Signature signature = Signature.getInstance(algorithmName);
                signature.initSign(privateKey);
                signature.update(payload);
                return signature.sign();
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to sign message", exception);
            }
        };
    }
}
