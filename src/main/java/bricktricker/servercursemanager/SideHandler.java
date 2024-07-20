package bricktricker.servercursemanager;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class SideHandler {

	protected static final Logger LOGGER = LogManager.getLogger();
	protected static final String ADDITIONAL = "additional";
	protected static final String MODS = "mods";
	protected static final String CLIENT_PACKS = "clientPacks";

	protected final Path serverModsPath;
	protected Path serverpackFolder;

	protected Set<String> loadedModNames;
	
	protected CompletableFuture<Void> installTask;
	protected ExecutorService downloadThreadpool;

	protected SideHandler(Path gameDir) {
		this.serverpackFolder = Utils.createOrGetDirectory(gameDir, "serverpack");
		this.serverModsPath = Utils.createOrGetDirectory(gameDir, "servermods");
	}

	public void doCleanup() {
		this.loadedModNames = null;
	}

	public void initialize() {
		this.loadedModNames = ConcurrentHashMap.newKeySet();
	}
	
	public void waitForInstall() {
		if(this.installTask == null) {
			return;
		}
		try {
			this.installTask.get();
		}catch(InterruptedException | ExecutionException e) {
			LOGGER.catching(e);
		}finally {
			try {
				this.downloadThreadpool.shutdown();
				this.downloadThreadpool.awaitTermination(2, TimeUnit.MINUTES);
			}catch(InterruptedException e) {
				LOGGER.catching(e);
			}finally {
				this.downloadThreadpool = null;
				this.installTask = null;
			}
		}
	}

	public abstract boolean isValid();

	protected abstract String getConfigFile();

	public String getStatus() {
		return "";
	}

	public boolean shouldLoadFile(String modfile) {
		return this.loadedModNames.contains(modfile);
	}

	public Path getServerpackFolder() {
		return this.serverpackFolder;
	}

	public Path getServermodsFolder() {
		return this.serverModsPath;
	}

}
