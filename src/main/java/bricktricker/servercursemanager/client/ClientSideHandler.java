package bricktricker.servercursemanager.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import bricktricker.servercursemanager.CopyOption;
import bricktricker.servercursemanager.CurseDownloader;
import bricktricker.servercursemanager.SideHandler;
import bricktricker.servercursemanager.Utils;
import cpw.mods.forge.cursepacklocator.HashChecker;
import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import cpw.mods.forge.serverpacklocator.client.ClientCertificateManager;

public class ClientSideHandler extends SideHandler {

	private ClientCertificateManager certManager;
	private SimpleHttpClient httpClient;

	private String status = "";

	public ClientSideHandler(Path gameDir) {
		super(gameDir);
		this.certManager = new ClientCertificateManager(this.packConfig, this.getServerpackFolder(), LaunchEnvironmentHandler.INSTANCE.getUUID());
		this.status = "Not set up";
	}

	@Override
	protected String getConfigFile() {
		return "/defaultclientconfig.toml";
	}

	@Override
	public boolean isValid() {
		return this.certManager.isValid();
	}

	@Override
	protected void validateConfig() {
		final String uuid = LaunchEnvironmentHandler.INSTANCE.getUUID();
		if(uuid == null || uuid.length() == 0) {
			// invalid UUID - probably offline mode. not supported
			LaunchEnvironmentHandler.INSTANCE.addProgressMessage("NO UUID found. Offline mode does not work. No server mods will be downloaded");
			LOGGER.error("There was not a valid UUID present in this client launch. You are probably playing offline mode. Trivially, there is nothing for us to do.");
			throw new IllegalStateException("Offline play not supported");
		}

		final String certificate = this.packConfig.get("client.certificate");
		final String key = this.packConfig.get("client.key");
		final String remoteServer = this.packConfig.get("client.remoteServer");

		LOGGER.debug("Configuration: Certificate {}, Key {}, remoteServer {}", certificate, key, remoteServer);

		if(Utils.isBlank(certificate, key, remoteServer)) {
			LOGGER.fatal("Invalid configuration for Server Curse Manager found: {}, please delete or correct before trying again", this.packConfig.getNioPath());
			throw new IllegalStateException("Invalid Configuration");
		}
	}

	@Override
	public void initialize() {
		super.initialize();

		final Path modpackZip = this.getServerpackFolder().resolve("modpack.zip");
		String currentModpackHash = "0";
		if(Files.exists(modpackZip) && Files.isRegularFile(modpackZip)) {
			currentModpackHash = String.valueOf(HashChecker.computeHash(modpackZip));
		}

		this.httpClient = new SimpleHttpClient(this, currentModpackHash);

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

		this.downloadThreadpool = Executors.newFixedThreadPool(Math.max(Runtime.getRuntime().availableProcessors() / 2, 1));
		this.singleExcecutor = Executors.newSingleThreadExecutor();
		final List<CompletableFuture<Void>> futures = new ArrayList<>();

		try(ZipFile zf = new ZipFile(modpackZip.toFile())) {
			ZipEntry manifestEntry = zf.getEntry("manifest.json");
			JsonObject manifest = Utils.loadJson(zf.getInputStream(manifestEntry)).getAsJsonObject();

			JsonArray mods = manifest.getAsJsonArray(SideHandler.MODS);
			for(JsonElement modE : mods) {
				JsonObject mod = modE.getAsJsonObject();
				final String source = mod.getAsJsonPrimitive("source").getAsString();
				if("curse".equals(source)) {
					int projectID = mod.getAsJsonPrimitive("projectID").getAsInt();
					int fileID = mod.getAsJsonPrimitive("fileID").getAsInt();

					CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
						ModMapping mapping = this.getMapping(projectID, fileID).orElseGet(() -> {
							try {
								LOGGER.debug("Downloading curse file {} for project {}", fileID, projectID);
								ModMapping m = CurseDownloader.downloadMod(projectID, fileID, getServermodsFolder());
								this.addMapping(m);
								return m;
							}catch(IOException e) {
								LOGGER.catching(e);
								return null;
							}
						});

						return mapping;
					}, downloadThreadpool)
					.thenAcceptAsync(mapping -> {
						if(mapping != null) {
							this.loadedModNames.add(mapping.getFileName());
						}
					}, singleExcecutor);
					
					futures.add(future);
					
				}else if("local".equals(source)) {
					String filename = mod.getAsJsonPrimitive("file").getAsString();
					ZipEntry modEntry = zf.getEntry("mods/" + filename);
					Files.copy(zf.getInputStream(modEntry), getServermodsFolder().resolve(filename), StandardCopyOption.REPLACE_EXISTING);
					CompletableFuture<Void> future = CompletableFuture.runAsync(() -> this.loadedModNames.add(filename), singleExcecutor);
					futures.add(future);
				}
			}

			CopyOption copyOption = CopyOption.getOption(manifest.getAsJsonPrimitive("copyOption").getAsString());
			JsonArray additional = manifest.getAsJsonArray(SideHandler.ADDITIONAL);
			for(JsonElement fileE : additional) {
				String file = fileE.getAsJsonPrimitive().getAsString();
				ZipEntry fileEntry = zf.getEntry(SideHandler.ADDITIONAL + "/" + file);
				Path destination = LaunchEnvironmentHandler.INSTANCE.getGameDir().resolve(file);
				Files.createDirectories(destination.getParent());
				if(copyOption.writeFile(destination)) {
					Files.copy(zf.getInputStream(fileEntry), destination, StandardCopyOption.REPLACE_EXISTING);
					LOGGER.debug("Copied additional file to {}", destination.toString());
				}else {
					LOGGER.debug("Skipped writing additional file {}, because it already exists", destination.toString());
				}
			}
			
			this.installTask = CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));

		}catch(IOException e) {
			LOGGER.catching(e);
			this.status = "Exception while loading modpack";
		}
	}

	public String getRemoteServer() {
		return this.packConfig.get("client.remoteServer");
	}

	@Override
	public void doCleanup() {
		super.doCleanup();
		this.httpClient = null;
		this.certManager = null;
	}

	@Override
	public String getStatus() {
		return this.status;
	}

	public ClientCertificateManager getCertManager() {
		return this.certManager;
	}

}
