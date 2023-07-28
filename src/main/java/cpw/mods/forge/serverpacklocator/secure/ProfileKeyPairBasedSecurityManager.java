package cpw.mods.forge.serverpacklocator.secure;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ServicesKeySet;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.authlib.yggdrasil.response.KeyPairResponse;

import cpw.mods.modlauncher.ArgumentHandler;
import cpw.mods.modlauncher.Launcher;

import java.lang.reflect.Field;
import java.net.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Based on https://github.com/cpw/serverpacklocator/blob/4496cf9ba45515b286bde1a3a79513e75b69754e/src/main/java/cpw/mods/forge/serverpacklocator/secure/ProfileKeyPairBasedSecurityManager.java
 * @author marchermans
 * 
 * Removed base interface
 * Renamed sessionId => playerUUID
 * Removed all the HTTP Based methods
 */
public final class ProfileKeyPairBasedSecurityManager
{
    
	// For now, we do not support custom proxies.
    private final static YggdrasilAuthenticationService AUTH_SERVICE = new YggdrasilAuthenticationService(Proxy.NO_PROXY);

    private static final UUID DEFAULT_NILL_UUID = new UUID(0L, 0L);

    private static final ProfileKeyPairBasedSecurityManager INSTANCE = new ProfileKeyPairBasedSecurityManager();
    public static ProfileKeyPairBasedSecurityManager getInstance()
    {
        return INSTANCE;
    }

    private final SigningHandler playerSigningHandler;
    private final UUID playerUUID;
    private final SignatureValidator mojangValidator;

    private ProfileKeyPairBasedSecurityManager()
    {
        playerSigningHandler = fetchSigningHandler();
        playerUUID = fetchPlayerUUID();
        mojangValidator = fetchMojangValidator();
    }
    
    public UUID getPlayerUUID() {
    	return this.playerUUID;
    }
    
    public SignatureValidator getMojangValdiator() {
    	return this.mojangValidator;
    }
    
    public SigningHandler getSigningHandler() {
    	return this.playerSigningHandler;
    }

    private static ArgumentHandler getArgumentHandler() {
        try {
            final Field argumentHandlerField = Launcher.class.getDeclaredField("argumentHandler");
            argumentHandlerField.setAccessible(true);
            return (ArgumentHandler) argumentHandlerField.get(Launcher.INSTANCE);
        }
        catch (NoSuchFieldException | IllegalAccessException | ClassCastException e)
        {
            throw new RuntimeException("Failed to get the argument handler used to start the system", e);
        }
    }

    private static String[] getLaunchArguments() {
        final ArgumentHandler argumentHandler = getArgumentHandler();
        try {
            final Field argsArrayField = ArgumentHandler.class.getDeclaredField("args");
            argsArrayField.setAccessible(true);
            return (String[]) argsArrayField.get(argumentHandler);
        }
        catch (NoSuchFieldException | IllegalAccessException | ClassCastException e)
        {
            throw new RuntimeException("Failed to get the launch arguments used to start the system", e);
        }
    }

    private static String fetchAccessToken() {
        final String[] arguments = getLaunchArguments();
        for (int i = 0; i < arguments.length; i++)
        {
            final String arg = arguments[i];
            if (Objects.equals(arg, "--accessToken")) {
                return arguments[i+1];
            }
        }

        return "";
    }

    private static UUID fetchPlayerUUID() {
        final String[] arguments = getLaunchArguments();
        for (int i = 0; i < arguments.length; i++)
        {
            final String arg = arguments[i];
            if (Objects.equals(arg, "--uuid")) {
                return UUID.fromString(arguments[i+1].replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
            }
        }

        return DEFAULT_NILL_UUID;
    }

    private static UserApiService getApiService() {
        final String accessToken = fetchAccessToken();
        if (accessToken.isBlank())
            return UserApiService.OFFLINE;

        try
        {
            return AUTH_SERVICE.createUserApiService(accessToken);
        }
        catch (AuthenticationException e)
        {
            throw new RuntimeException("Failed to create user api service to get profile key pair!", e);
        }
    }

    public static KeyPairResponse getKeyPair() {
        final UserApiService apiService = getApiService();
        return apiService.getKeyPair();
    }

    private static ProfileKeyPair getProfileKeyPair() {
        final KeyPairResponse keyPairResponse = getKeyPair();
        if (keyPairResponse == null)
            return null;

        return new ProfileKeyPair(Crypt.stringToPemRsaPrivateKey(keyPairResponse.getPrivateKey()),
                new PublicKeyData(
                Crypt.stringToRsaPublicKey(keyPairResponse.getPublicKey()),
                Instant.parse(keyPairResponse.getExpiresAt()),
                keyPairResponse.getPublicKeySignature().array()));
    }

    private static SigningHandler fetchSigningHandler() {
        final ProfileKeyPair profileKeyPair = getProfileKeyPair();
        if (profileKeyPair == null)
            return null;

        return new SigningHandler(profileKeyPair);
    }

    private static SignatureValidator fetchMojangValidator() {
        final ServicesKeySet keyInfo = AUTH_SERVICE.getServicesKeySet();
        if (keyInfo == null)
            return SignatureValidator.ALWAYS_FAIL;

        return SignatureValidator.from(keyInfo);
    }
    
    public void validatePublicKey(PublicKeyData keyData, UUID sessionId) throws Exception
    {
    	if (keyData.key() == null) {
            throw new Exception("Missing public key!");
        } else {
            if (keyData.expiresAt().isBefore(Instant.now())) {
                throw new Exception("Public key has expired!");
            }
            if (!keyData.verifyPlayerId(this.mojangValidator, sessionId)) {
                throw new Exception("Invalid public key!");
            }
        }
    }

    public record PublicKeyData(PublicKey key, Instant expiresAt, byte[] publicKeySignature) {

        boolean verifyPlayerId(SignatureValidator validator, UUID playerUUID) {
            return validator.validate(this.signedPayload(playerUUID), this.publicKeySignature);
        }

        public SignatureValidator validator() {
            return SignatureValidator.from(key(), "SHA256withRSA");
        }

        private byte[] signedPayload(UUID playerUUID) {
            byte[] keyPayload = this.key.getEncoded();
            byte[] idWithKeyResult = new byte[24 + keyPayload.length];
            ByteBuffer bytebuffer = ByteBuffer.wrap(idWithKeyResult).order(ByteOrder.BIG_ENDIAN);
            bytebuffer.putLong(playerUUID.getMostSignificantBits()).putLong(playerUUID.getLeastSignificantBits()).putLong(this.expiresAt.toEpochMilli()).put(keyPayload);
            return idWithKeyResult;
        }
    }

    public record ProfileKeyPair(PrivateKey privateKey, PublicKeyData publicKeyData) {
    }

    public record SigningHandler(ProfileKeyPair keyPair, Signer signer) {

        private SigningHandler(ProfileKeyPair keyPair)
        {
            this(keyPair, Signer.from(keyPair.privateKey(), "SHA256withRSA"));
        }
    }
}
