package cpw.mods.forge.serverpacklocator.secure;

import cpw.mods.forge.serverpacklocator.ModAccessor;

import java.util.UUID;

/**
 * Copied from https://github.com/cpw/serverpacklocator/blob/4496cf9ba45515b286bde1a3a79513e75b69754e/src/main/java/cpw/mods/forge/serverpacklocator/secure/WhitelistVerificationHelper.java
 * 
 */
public final class WhitelistVerificationHelper
{
    private static final WhitelistVerificationHelper INSTANCE = new WhitelistVerificationHelper();

    public static WhitelistVerificationHelper getInstance()
    {
        return INSTANCE;
    }

    private WhitelistVerificationHelper()
    {
    }
    public AllowedStatus isAllowed(final UUID sessionId) {
    	if(ModAccessor.getIsWhiteListEnabled() == null || ModAccessor.getIsWhiteListed() == null) {
    		return AllowedStatus.NOT_READY;
    	}
    	if(!ModAccessor.getIsWhiteListEnabled().get().join() || ModAccessor.getIsWhiteListed().apply(sessionId).join()) {
    		return AllowedStatus.ALLOWED;
    	}
        return AllowedStatus.REJECTED;
    }
    
    public static enum AllowedStatus  {
    	ALLOWED,
    	REJECTED,
    	NOT_READY
    }
}
