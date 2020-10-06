package cpw.mods.forge.serverpacklocator;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Copied from https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/ModAccessor.java
 * @author cpw
 * Made fields public
 */
public class ModAccessor {
    public static String statusLine = "ServerPack: unknown";
    public static boolean needsCertificate = true;

    public static Supplier<String> status() {
        return ()->statusLine;
    }

    public static BooleanSupplier needsCert() { return () -> needsCertificate; }
}