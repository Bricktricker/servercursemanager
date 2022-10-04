package bricktricker.servercursemanager;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;

public abstract class SideHandler {

	protected static final Logger LOGGER = LogManager.getLogger();
	protected static final String ADDITIONAL = "additional";
	protected static final String MODS = "mods";

	protected final FileConfig packConfig;

	protected final Path serverModsPath;
	protected Path serverpackFolder;

	protected Set<String> loadedModNames;
	
	protected CompletableFuture<Void> installTask;
	protected ExecutorService downloadThreadpool;
	protected ExecutorService singleExcecutor;

	protected SideHandler(Path gameDir) {
		this.serverpackFolder = Utils.createOrGetDirectory(gameDir, "serverpack");
		this.serverModsPath = Utils.createOrGetDirectory(gameDir, "servermods");

		this.packConfig = CommentedFileConfig.builder(serverpackFolder.resolve("config.toml"))
				.preserveInsertionOrder()
				.onFileNotFound(FileNotFoundAction.copyData(SideHandler.class.getResourceAsStream(this.getConfigFile())))
				.build();

		this.packConfig.load();
		this.packConfig.close();

		this.validateConfig();
	}

	public void doCleanup() {
		this.loadedModNames = null;
	}

	public void initialize() {
		this.loadedModNames = new HashSet<>();
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
				
				this.singleExcecutor.shutdown();
				this.singleExcecutor.awaitTermination(2, TimeUnit.MINUTES);
			}catch(InterruptedException e) {
				LOGGER.catching(e);
			}finally {
				this.downloadThreadpool = null;
				this.singleExcecutor = null;
				this.installTask = null;
			}
		}
	}

	public abstract boolean isValid();

	protected abstract String getConfigFile();

	protected abstract void validateConfig();

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
