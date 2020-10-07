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

	private final ClientCertificateManager certManager;
	private SimpleHttpClient httpClient;

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
		try {
			this.httpClient.waitForResult();
		}catch(ExecutionException e) {
			LOGGER.catching(e);
		}

		if(!Files.exists(modpackZip) || !Files.isRegularFile(modpackZip)) {
			LOGGER.warn("Could not download modpack, won't load any mods");
			LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Could not download modpack, won't load any mods");
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
						if(!this.hasFile(projectID, fileID)) {
							try {
								final ModMapping mapping = CurseDownloader.downloadMod(projectID, fileID, getServermodsFolder());
								this.addMapping(mapping);

								singleExcecutor.submit(() -> this.loadedModNames.add(mapping.getFileName()));
							}catch(IOException e) {
								LOGGER.catching(e);
								return;
							}
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
			return;
		}finally {
			try {
				executorService.shutdown();
				singleExcecutor.shutdown();
				executorService.awaitTermination(2, TimeUnit.HOURS);
				singleExcecutor.awaitTermination(2, TimeUnit.HOURS);
			}catch(InterruptedException e) {
			}
		}

	}

	@Override
	public void doCleanup() {
		super.doCleanup();
		this.httpClient = null;
	}

	public ClientCertificateManager getCertManager() {
		return this.certManager;
	}

}
