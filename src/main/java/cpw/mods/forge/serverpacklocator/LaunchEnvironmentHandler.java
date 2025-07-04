package cpw.mods.forge.serverpacklocator;

import cpw.mods.modlauncher.Environment;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.TypesafeMap;
import net.neoforged.api.distmarker.Dist;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Copied from https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/LaunchEnvironmentHandler.java
 * @author cpw
 * 
 * Changes:
 * Made all methods public
 */
public class LaunchEnvironmentHandler {
    public static final LaunchEnvironmentHandler INSTANCE = new LaunchEnvironmentHandler();
    private final Optional<Environment> environment;

    private LaunchEnvironmentHandler() {
        environment = Optional.ofNullable(Launcher.INSTANCE).map(Launcher::environment);
    }

    private <T> Optional<T> getValue(final Supplier<TypesafeMap.Key<T>> key) {
        return environment.flatMap(e -> e.getProperty(key.get()));
    }

    public Path getGameDir() {
        return getValue(IEnvironment.Keys.GAMEDIR).orElseGet(()-> Paths.get("."));
    }

    public String getUUID() {
        return getValue(IEnvironment.Keys.UUID).orElse("");
    }

    public Dist getDist() {
        return getValue(net.neoforged.neoforgespi.Environment.Keys.DIST).orElse(Dist.CLIENT);
    }

    public void addProgressMessage(String message) {
        getValue(net.neoforged.neoforgespi.Environment.Keys.PROGRESSMESSAGE).ifPresent(pm->pm.accept(message));
    }
}