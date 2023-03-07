package cpw.mods.forge.serverpacklocator;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Copied from https://github.com/cpw/serverpacklocator/blob/4496cf9ba45515b286bde1a3a79513e75b69754e/src/main/java/cpw/mods/forge/serverpacklocator/ModAccessor.java
 * 
 */
public class ModAccessor {
    private static String statusLine = "ServerPack: unknown";
    private static Function<UUID, CompletableFuture<Boolean>> isWhiteListed = null;
    private static Supplier<CompletableFuture<Boolean>> isWhiteListEnabled = null;

    public static void setStatusLine(final String statusLine)
    {
        ModAccessor.statusLine = statusLine;
    }
    public static String getStatusLine()
    {
        return statusLine;
    }

    public static Function<UUID, CompletableFuture<Boolean>> getIsWhiteListed()
    {
        return isWhiteListed;
    }

    public static Supplier<CompletableFuture<Boolean>> getIsWhiteListEnabled()
    {
        return isWhiteListEnabled;
    }

    public static void setIsWhiteListed(final Function<UUID, CompletableFuture<Boolean>> isWhiteListed)
    {
        ModAccessor.isWhiteListed = isWhiteListed;
    }

    public static void setIsWhiteListEnabled(final Supplier<CompletableFuture<Boolean>> isWhiteListEnabled)
    {
        ModAccessor.isWhiteListEnabled = isWhiteListEnabled;
    }
}
