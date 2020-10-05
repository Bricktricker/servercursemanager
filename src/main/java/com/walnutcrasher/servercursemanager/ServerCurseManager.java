package com.walnutcrasher.servercursemanager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.jar.Manifest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.walnutcrasher.servercursemanager.client.ClientSideHandler;
import com.walnutcrasher.servercursemanager.server.ServerSideHandler;

import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.forgespi.locating.IModDirectoryLocatorFactory;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;

public class ServerCurseManager implements IModLocator {
	
	private static final Logger LOGGER = LogManager.getLogger();
	
	private IModLocator dirLocator;
	
	private SideHandler sideHandler;
	
	public ServerCurseManager() {
		LOGGER.info("Loading Server Curse Manager. Version {}", getClass().getPackage().getImplementationVersion());
		Dist currentDist = LaunchEnvironmentHandler.INSTANCE.getDist();
		final Path gameDir = LaunchEnvironmentHandler.INSTANCE.getGameDir();
		
		if(currentDist.isDedicatedServer()) {
			sideHandler = new ServerSideHandler(gameDir);
		}else {
			sideHandler = new ClientSideHandler(gameDir);
		}
	}

	@Override
	public List<IModFile> scanMods() {
		return new ArrayList<>();
	}

	@Override
	public String name() {
		return "ServerCurseManager";
	}

	@Override
	public Path findPath(IModFile modFile, String... path) {
		return null;
	}

	@Override
	public void scanFile(IModFile modFile, Consumer<Path> pathConsumer) {

	}

	@Override
	public Optional<Manifest> findManifest(Path file) {
		return null;
	}

	@Override
	public void initArguments(Map<String, ?> arguments) {
		final IModDirectoryLocatorFactory modFileLocator = LaunchEnvironmentHandler.INSTANCE.getModFolderFactory();
        dirLocator = modFileLocator.build(null, "serverpack");
        /*
        if (serverPackLocator.isValid()) {
            serverPackLocator.initialize(dirLocator);
        }
        */
	}

	@Override
	public boolean isValid(IModFile modFile) {
		return dirLocator.isValid(modFile);
	}

}
