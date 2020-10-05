package com.walnutcrasher.servercursemanager;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.jar.Manifest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;

public class ServerCurseManager implements IModLocator {
	
	private static final Logger LOGGER = LogManager.getLogger();

	@Override
	public List<IModFile> scanMods() {
		return null;
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
		
	}

	@Override
	public boolean isValid(IModFile modFile) {
		return false;
	}

}
