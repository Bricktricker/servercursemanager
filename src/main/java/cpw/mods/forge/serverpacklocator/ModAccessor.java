package cpw.mods.forge.serverpacklocator;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.commons.lang3.tuple.Pair;

/**
 * Copied from https://github.com/cpw/serverpacklocator/blob/4496cf9ba45515b286bde1a3a79513e75b69754e/src/main/java/cpw/mods/forge/serverpacklocator/ModAccessor.java
 * Added nameResolver functionality
 * @author cpw, marchermans
 */
public class ModAccessor {
    private static String statusLine = "ServerPack: unknown";
    private static Function<UUID, CompletableFuture<Boolean>> isWhiteListed = null;
    private static Supplier<CompletableFuture<Boolean>> isWhiteListEnabled = null;
    private static Function<UUID, CompletableFuture<Optional<String>>> nameResolver = null;
    
    private static List<Pair<String, Boolean>> clientPacks;
    public static Consumer<List<Pair<String, Boolean>>> clientPackSelectionConsumer; 

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
    
    public static void setNameResolver(final Function<UUID, CompletableFuture<Optional<String>>> resolver)
    {
        ModAccessor.nameResolver = resolver;
    }
    
    public static String resolveName(UUID id)
    {
        if(nameResolver == null) {
            return id.toString();
        }
        return nameResolver.apply(id).join().orElseGet(id::toString);
    }
    
    public static List<Pair<String, Boolean>> getClientPacks() {
        return clientPacks;
    }
    
    public static void setClientpacks(List<Pair<String, Boolean>> packs) {
        clientPacks = packs;
    }
    
    public static void savePacksSelection(List<Pair<String, Boolean>> packs) {
        clientPackSelectionConsumer.accept(packs);
        clientPacks = packs;
    }
}
