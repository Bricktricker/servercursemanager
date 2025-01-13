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
import java.util.Comparator;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bricktricker.servercursemanager.client.ClientSideHandler;
import bricktricker.servercursemanager.server.ServerSideHandler;
import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import cpw.mods.forge.serverpacklocator.ModAccessor;
import cpw.mods.modlauncher.api.LambdaExceptionUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.StringUtils;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

public class ServerCurseManager implements IModFileCandidateLocator {

	private static final Logger LOGGER = LogManager.getLogger();

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
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        if(sideHandler.isValid()) {
            sideHandler.initialize();
        }else {
            LOGGER.warn("Server Curse Manager: Invalid configuration");
            return;
        }

        // installes the serverpackutility mod. Copied from
        // https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/PackLocator.java#L80-L84
        Path serverModsPath = sideHandler.getServermodsFolder();
        URL url = getClass().getProtectionDomain().getCodeSource().getLocation();
        
        LOGGER.info("Loading server pack locator from: " + url.toString());
        URI targetURI = LambdaExceptionUtils.uncheck(() -> new URI("file://" + LambdaExceptionUtils.uncheck(url::toURI).getRawSchemeSpecificPart().split("!")[0].split("\\.jar")[0] + ".jar"));

        LOGGER.info("Unpacking utility mod from: " + targetURI.toString());
        try(FileSystem thiszip = FileSystems.newFileSystem(Paths.get(targetURI), getClass().getClassLoader())) {
            final Path utilModPath = thiszip.getPath("utilmod", "serverpackutility.zip");
            final Path utilModJar = serverModsPath.resolve("serverpackutility.jar");
            Files.copy(utilModPath, utilModJar, StandardCopyOption.REPLACE_EXISTING);
            pipeline.addPath(utilModJar, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
        }catch(IOException e) {
            throw new UncheckedIOException(e);
        }
        
        this.sideHandler.waitForInstall();
        
        List<Path> directoryContent;
        try (var files = Files.list(serverModsPath)) {
            directoryContent = files
                    .filter(p -> sideHandler.shouldLoadFile(p.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> StringUtils.toLowerCase(path.getFileName().toString())))
                    .toList();
        } catch (UncheckedIOException | IOException e) {
            throw new ModLoadingException(ModLoadingIssue.error("fml.modloadingissue.failed_to_list_folder_content", serverModsPath).withAffectedPath(serverModsPath).withCause(e));
        }
        
        ModAccessor.setStatusLine("ServerPack: " + sideHandler.getStatus());
        LOGGER.debug(ModAccessor.getStatusLine());
        
        for (var file : directoryContent) {
            if (!Files.isRegularFile(file)) {
                pipeline.addIssue(ModLoadingIssue.warning("fml.modloadingissue.brokenfile.unknown").withAffectedPath(file));
                continue;
            }

            pipeline.addPath(file, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
        }
        
        sideHandler.doCleanup();   
    }

	@Override
	public String toString() {
		return "ServerCurseManager";
	}

}
