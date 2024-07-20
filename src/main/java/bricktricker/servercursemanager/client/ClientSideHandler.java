package bricktricker.servercursemanager.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.tuple.Pair;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import bricktricker.servercursemanager.CopyOption;
import bricktricker.servercursemanager.CurseDownloader;
import bricktricker.servercursemanager.SideHandler;
import bricktricker.servercursemanager.Utils;
import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import cpw.mods.forge.serverpacklocator.ModAccessor;

public class ClientSideHandler extends SideHandler {

	private SimpleClient httpClient;

	private String status = "";
	
	protected final FileConfig packConfig;

	public ClientSideHandler(Path gameDir) {
		super(gameDir);
		this.status = "Not set up";
		
		this.packConfig = CommentedFileConfig.builder(serverpackFolder.resolve("config.toml"))
				.preserveInsertionOrder()
				.onFileNotFound(FileNotFoundAction.copyData(ClientSideHandler.class.getResourceAsStream(this.getConfigFile())))
				.build();

		this.packConfig.load();
		
		ModAccessor.clientPackSelectionConsumer = this::handleClientPackSelection;
	}

	@Override
	protected String getConfigFile() {
		return "/defaultclientconfig.toml";
	}
	
	private void handleClientPackSelection(List<Pair<String, Boolean>> selection) {
	    for(var pack : selection) {
	        this.packConfig.set("packs." + pack.getLeft(), pack.getRight());
	    }
	    this.packConfig.save();
	}

	@Override
	public boolean isValid() {
		final String uuid = LaunchEnvironmentHandler.INSTANCE.getUUID();
		if(uuid == null || uuid.length() == 0) {
			// invalid UUID - probably offline mode. not supported
			LaunchEnvironmentHandler.INSTANCE.addProgressMessage("NO UUID found. Offline mode does not work. No server mods will be downloaded");
			LOGGER.error("There was not a valid UUID present in this client launch. You are probably playing offline mode. Trivially, there is nothing for us to do.");
			return false;
		}

		final String remoteServer = this.packConfig.get("client.remoteServer");

		LOGGER.debug("Configuration: remoteServer {}", remoteServer);

		if(remoteServer.isBlank()) {
			LOGGER.fatal("Invalid configuration for Server Curse Manager found: {}, please delete or correct before trying again", this.packConfig.getNioPath());
			return false;
		}
		return true;
	}

	@Override
	public void initialize() {
		super.initialize();

		final Path modpackZip = this.getServerpackFolder().resolve("modpack.zip");
		byte[] currentModpackHash = null;
		if(Files.exists(modpackZip) && Files.isRegularFile(modpackZip)) {
			currentModpackHash = Utils.computeSha1(modpackZip);
		}

		this.httpClient = new SimpleClient(this, currentModpackHash);

		boolean downloadSuccessful = false;
		try {
			downloadSuccessful = this.httpClient.waitForResult();
		}catch(ExecutionException e) {
			LOGGER.catching(e);
		}

		if(!downloadSuccessful) {
			// If this is the first start and no modpack is available, it's getting
			// overwritten in the next block
			this.status = "Using old Modpack version";
		}else {
			this.status = "Using latest Modpack version";
		}

		if(!Files.exists(modpackZip) || !Files.isRegularFile(modpackZip)) {
			LOGGER.warn("Could not download modpack, won't load any mods");
			LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Could not download modpack, won't load any mods");
			this.status = "No mods loaded";
			return;
		}

		List<CompletableFuture<String>> futures = new ArrayList<>();

		try(FileSystem modpackSystem = FileSystems.newFileSystem(modpackZip)) {
		    Path manifestPath = modpackSystem.getPath("manifest.json");
			JsonObject manifest = Utils.loadJson(Files.newInputStream(manifestPath)).getAsJsonObject();

			JsonArray mods = manifest.getAsJsonArray(SideHandler.MODS);
			int numDownloadThreads = Math.min(Math.max(Runtime.getRuntime().availableProcessors() / 2, 1), mods.size());
			this.downloadThreadpool = new ThreadPoolExecutor(1, numDownloadThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
			
			futures = this.parseMods(modpackSystem, mods);

			JsonArray additional = manifest.getAsJsonArray(SideHandler.ADDITIONAL);
			for(JsonElement fileE : additional) {
				String file = fileE.getAsJsonObject().getAsJsonPrimitive("file").getAsString();
				CopyOption copyOption = CopyOption.getOption(fileE.getAsJsonObject().getAsJsonPrimitive("copyOption").getAsString());
				Path fileEntry = modpackSystem.getPath(SideHandler.ADDITIONAL, file);
				Path destination = LaunchEnvironmentHandler.INSTANCE.getGameDir().resolve(file);
				Files.createDirectories(destination.getParent());
				if(copyOption.writeFile(destination)) {
					Files.copy(fileEntry, destination, StandardCopyOption.REPLACE_EXISTING);
					LOGGER.debug("Copied additional file to {}", destination.toString());
				}else {
					LOGGER.debug("Skipped writing additional file {}", destination.toString());
				}
			}
			
			var clientPacksList = new ArrayList<Pair<String, Boolean>>();
			JsonArray clientPacks = manifest.getAsJsonArray(SideHandler.CLIENT_PACKS);
			for(JsonElement packE : clientPacks) {
			    JsonObject pack = packE.getAsJsonObject();
			    String name = pack.getAsJsonPrimitive("name").getAsString();
			    
			    if(!this.packConfig.contains("packs." + name)) {
			        LOGGER.debug("adding config path {}", "packs." + name);
			        this.packConfig.add("packs." + name, false);
		             var pair = Pair.<String, Boolean>of(name, false);
		             clientPacksList.add(pair);
			    }else {
			        boolean enable = this.packConfig.<Boolean>get("packs." + name);
		            var pair = Pair.<String, Boolean>of(name, enable);
		            clientPacksList.add(pair);
			        if(!enable) {
			            continue;
			        }
			        
			        var clientPackFutures = this.parseMods(modpackSystem, pack.getAsJsonArray("mods"));
			        futures.addAll(clientPackFutures);
			    }
			}
			ModAccessor.setClientpacks(clientPacksList);
			this.packConfig.save();

			this.installTask = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));

		}catch(Exception e) {
			LOGGER.catching(e);
			this.status = "Exception while loading modpack";
		}
	}
	
	private List<CompletableFuture<String>> parseMods(FileSystem modpackSystem, JsonArray mods) {
	    final List<CompletableFuture<String>> futures = new ArrayList<>();
	    for(JsonElement modE : mods) {
            JsonObject mod = modE.getAsJsonObject();
            final String source = mod.getAsJsonPrimitive("source").getAsString();
            if("remote".equals(source)) {
                String url = mod.getAsJsonPrimitive("url").getAsString();
                String sha1 = mod.getAsJsonPrimitive("sha1").getAsString();
                String fileName = mod.getAsJsonPrimitive("file").getAsString();

                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        CurseDownloader.downloadFile(url, fileName, sha1, getServermodsFolder());
                        this.loadedModNames.add(fileName);
                        return fileName;
                    }catch(IOException e) {
                        LOGGER.catching(e);
                        return null;
                    }
                }, downloadThreadpool);

                futures.add(future);

            }else if("local".equals(source)) {
                String filename = mod.getAsJsonPrimitive("file").getAsString();
                Path modEntryPath = modpackSystem.getPath("mods", filename);
                try {
                    Files.copy(modEntryPath, getServermodsFolder().resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                this.loadedModNames.add(filename);
            }
        }
	    
	    return futures;
	}

	public String getRemoteServer() {
		return this.packConfig.get("client.remoteServer");
	}

	@Override
	public void doCleanup() {
		super.doCleanup();
		this.httpClient = null;
	}

	@Override
	public String getStatus() {
		return this.status;
	}

}
