package com.walnutcrasher.servercursemanager.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.walnutcrasher.servercursemanager.CurseDownloader;
import com.walnutcrasher.servercursemanager.SideHandler;
import com.walnutcrasher.servercursemanager.Utils;

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
	}

	@Override
	public boolean isValid() {
		return this.certManager.isValid();
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

		// TODO: move to scanMods?
		boolean downloadSuccessful = false;
		try {
			downloadSuccessful = this.httpClient.waitForResult();
		}catch(ExecutionException e) {
			LOGGER.catching(e);
		}
		
		if(!downloadSuccessful) {
			//If this is the first start and no modpack is available, it's getting overwritten in the next block
			this.status = "Using old Modpack version";
		}

		if(!Files.exists(modpackZip) || !Files.isRegularFile(modpackZip)) {
			LOGGER.warn("Could not download modpack, won't load any mods");
			LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Could not download modpack, won't load any mods");
			this.status = "No mods loaded";
			return;
		}

		final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() / 2);
		final ExecutorService singleExcecutor = Executors.newSingleThreadExecutor();

		try(ZipFile zf = new ZipFile(modpackZip.toFile())) {
			ZipEntry manifestEntry = zf.getEntry("manifest.json");
			JsonObject manifest = Utils.loadJson(zf.getInputStream(manifestEntry)).getAsJsonObject();

			JsonArray mods = manifest.getAsJsonArray("mods");
			for(JsonElement modE : mods) {
				JsonObject mod = modE.getAsJsonObject();
				final String source = mod.getAsJsonPrimitive("source").getAsString();
				if("curse".equals(source)) {
					int projectID = mod.getAsJsonPrimitive("projectID").getAsInt();
					int fileID = mod.getAsJsonPrimitive("fileID").getAsInt();

					executorService.execute(() -> {						
						ModMapping mapping = this.getMapping(projectID, fileID)
								.orElseGet(() -> {
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
							
							if(mapping != null) {
								singleExcecutor.submit(() -> {
									this.loadedModNames.add(mapping.getFileName());
								});
							}
						
					});
				}else if("local".equals(source)) {
					String filename = mod.getAsJsonPrimitive("file").getAsString();
					ZipEntry modEntry = zf.getEntry("mods/" + filename);
					Files.copy(zf.getInputStream(modEntry), getServermodsFolder().resolve(filename), StandardCopyOption.REPLACE_EXISTING);
					singleExcecutor.submit(() -> this.loadedModNames.add(filename));
				}
			}

			JsonArray additional = manifest.getAsJsonArray("additional");
			for(JsonElement fileE : additional) {
				String file = fileE.getAsJsonPrimitive().getAsString();
				ZipEntry fileEntry = zf.getEntry("additional/" + file);
				Path destination = LaunchEnvironmentHandler.INSTANCE.getGameDir().resolve(file);
				Files.createDirectories(destination.getParent());
				Files.copy(zf.getInputStream(fileEntry), destination, StandardCopyOption.REPLACE_EXISTING);
			}

		}catch(IOException e) {
			LOGGER.catching(e);
			this.status = "Exception while loading modpack";
		}finally {
			try {
				executorService.shutdown();
				executorService.awaitTermination(2, TimeUnit.HOURS);
				
				singleExcecutor.shutdown();
				singleExcecutor.awaitTermination(2, TimeUnit.HOURS);
			}catch(InterruptedException e) {
			}
		}

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
