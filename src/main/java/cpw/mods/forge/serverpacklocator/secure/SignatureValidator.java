package cpw.mods.forge.serverpacklocator.secure;

import com.mojang.authlib.yggdrasil.ServicesKeyInfo;
import com.mojang.authlib.yggdrasil.ServicesKeySet;
import com.mojang.authlib.yggdrasil.ServicesKeyType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Collection;

/**
 * Copied from https://github.com/cpw/serverpacklocator/blob/4496cf9ba45515b286bde1a3a79513e75b69754e/src/main/java/cpw/mods/forge/serverpacklocator/secure/SignatureValidator.java
 * @author marchermans
 * 
 */
public interface SignatureValidator {
    SignatureValidator ALWAYS_FAIL = (payload, inputSignature) -> false;
    Logger LOGGER = LogManager.getLogger();

    boolean validate(byte[] payload, byte[] expectedSignature);

    private static boolean verifySignature(byte[] payload, byte[] expectedSignature, Signature signature) throws SignatureException
    {
        signature.update(payload);
        return signature.verify(expectedSignature);
    }

    static SignatureValidator from(final PublicKey publicKey, final String algorithmName) {
        return (payLoad, inputSignature) -> {
            try {
                Signature signature = Signature.getInstance(algorithmName);
                signature.initVerify(publicKey);
                return verifySignature(payLoad, inputSignature, signature);
            }
            catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException e)
            {
                throw new RuntimeException(e);
            }
        };
    }

    static SignatureValidator from(ServicesKeySet servicesKeySet) {
    	Collection<ServicesKeyInfo> servicesKeyInfos = servicesKeySet.keys(ServicesKeyType.PROFILE_KEY);
    	if(servicesKeyInfos == null || servicesKeyInfos.isEmpty()) {
    		throw new IllegalStateException("Could not get keys for ServicesKeyType.PROFILE_KEY");
    	}
    	
    	return (payload, inputSignature) -> {
    		return servicesKeyInfos.stream().anyMatch(servicesKeyInfo -> {
    			Signature signature = servicesKeyInfo.signature();
                try {
                    return verifySignature(payload, inputSignature, signature);
                } catch (SignatureException signatureexception) {
                    LOGGER.error("Failed to verify Services signature", signatureexception);
                    return false;
                }
    		});
    	};
    }
}
