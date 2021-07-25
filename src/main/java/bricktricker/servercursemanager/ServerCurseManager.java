package bricktricker.servercursemanager;

import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.client.ClientSideHandler;
import bricktricker.servercursemanager.server.ServerSideHandler;
import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import cpw.mods.forge.serverpacklocator.ModAccessor;
import cpw.mods.modlauncher.api.LamdbaExceptionUtils;
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
		this.sideHandler.waitForInstall();
		final List<IModFile> modFiles = dirLocator.scanMods();
		final IModFile packutil = modFiles.stream()
				.filter(modFile -> "serverpackutility.jar".equals(modFile.getFileName()))
				.findFirst()
				.orElseThrow(() -> new RuntimeException("Something went wrong with the internal utility mod"));

		List<IModFile> finalModList = new ArrayList<>();
		if(sideHandler.isValid()) {
			finalModList = modFiles.stream()
				.filter(mf -> sideHandler.shouldLoadFile(mf.getFileName()))
				.collect(Collectors.toCollection(() -> new ArrayList<>()));	
		}

		finalModList.add(packutil);

		ModAccessor.statusLine = "ServerPack: " + sideHandler.getStatus();
		LOGGER.debug(ModAccessor.statusLine);

		sideHandler.doCleanup();
		return finalModList;
	}

	@Override
	public String name() {
		return "ServerCurseManager";
	}

	@Override
	public Path findPath(IModFile modFile, String... path) {
		return dirLocator.findPath(modFile, path);
	}

	@Override
	public void scanFile(IModFile modFile, Consumer<Path> pathConsumer) {
		dirLocator.scanFile(modFile, pathConsumer);
	}

	@Override
	public Optional<Manifest> findManifest(Path file) {
		return dirLocator.findManifest(file);
	}

	@Override
	public void initArguments(Map<String, ?> arguments) {
		final IModDirectoryLocatorFactory modFileLocator = LaunchEnvironmentHandler.INSTANCE.getModFolderFactory();
		dirLocator = modFileLocator.build(sideHandler.getServermodsFolder(), "serverpack");
		if(sideHandler.isValid()) {
			sideHandler.initialize();
		}else {
			LOGGER.warn("Server Curse Manager: Invalid configuration");
		}

		// installes the serverpackutility mod. Copied from
		// https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/PackLocator.java#L80-L84
		Path serverModsPath = sideHandler.getServermodsFolder();
		URL url = getClass().getProtectionDomain().getCodeSource().getLocation();
		URI targetURI = LamdbaExceptionUtils.uncheck(() -> new URI("file://" + LamdbaExceptionUtils.uncheck(url::toURI).getRawSchemeSpecificPart().split("!")[0]));
		final FileSystem thiszip = LamdbaExceptionUtils.uncheck(() -> FileSystems.newFileSystem(Paths.get(targetURI), getClass().getClassLoader()));
		final Path utilModPath = thiszip.getPath("utilmod", "serverpackutility.jar");
		LamdbaExceptionUtils.uncheck(() -> Files.copy(utilModPath, serverModsPath.resolve("serverpackutility.jar"), StandardCopyOption.REPLACE_EXISTING));
	}

	@Override
	public boolean isValid(IModFile modFile) {
		return dirLocator.isValid(modFile);
	}

}
