package bricktricker.servercursemanager;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.function.Consumer;
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
	public List<ModFileOrException> scanMods() {
		this.sideHandler.waitForInstall();
		final List<ModFileOrException> modFiles = dirLocator.scanMods();
		final ModFileOrException packutil = modFiles.stream()
				.filter(modFile -> "serverpackutility.jar".equals(modFile.file().getFileName()))
				.findFirst()
				.orElseThrow(() -> new RuntimeException("Something went wrong with the internal utility mod"));

		List<ModFileOrException> finalModList = new ArrayList<>();
		if(sideHandler.isValid()) {
			finalModList = modFiles.stream()
				.filter(mf -> sideHandler.shouldLoadFile(mf.file().getFileName()))
				.collect(Collectors.toCollection(() -> new ArrayList<>()));	
		}

		finalModList.add(packutil);

		ModAccessor.setStatusLine("ServerPack: " + sideHandler.getStatus());
		LOGGER.debug(ModAccessor.getStatusLine());

		sideHandler.doCleanup();
		return finalModList;
	}

	@Override
	public String name() {
		return "ServerCurseManager";
	}

	@Override
	public void scanFile(IModFile modFile, Consumer<Path> pathConsumer) {
		dirLocator.scanFile(modFile, pathConsumer);
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
		
		LOGGER.info("Loading server pack locator from: " + url.toString());
        URI targetURI = LamdbaExceptionUtils.uncheck(() -> new URI("file://" + LamdbaExceptionUtils.uncheck(url::toURI).getRawSchemeSpecificPart().split("!")[0].split("\\.jar")[0] + ".jar"));

        LOGGER.info("Unpacking utility mod from: " + targetURI.toString());
        try(FileSystem thiszip = FileSystems.newFileSystem(Paths.get(targetURI), getClass().getClassLoader())) {
            final Path utilModPath = thiszip.getPath("utilmod", "serverpackutility.zip");
            Files.copy(utilModPath, serverModsPath.resolve("serverpackutility.jar"), StandardCopyOption.REPLACE_EXISTING);
        }catch(IOException e) {
            throw new UncheckedIOException(e);
        }
	}

	@Override
	public boolean isValid(IModFile modFile) {
		return dirLocator.isValid(modFile);
	}

}
