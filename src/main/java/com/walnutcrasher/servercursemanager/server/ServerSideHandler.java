package com.walnutcrasher.servercursemanager.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.walnutcrasher.servercursemanager.CurseDownloader;
import com.walnutcrasher.servercursemanager.SideHandler;
import com.walnutcrasher.servercursemanager.Utils;

import cpw.mods.forge.serverpacklocator.server.ServerCertificateManager;
import cpw.mods.forge.serverpacklocator.server.SimpleHttpServer;

public class ServerSideHandler extends SideHandler {

	private ServerCertificateManager certManager;
	private SimpleHttpServer httpServer;
	
	private Set<String> loadedModNames;

	public ServerSideHandler(Path gameDir) {
		super(gameDir);

		this.certManager = new ServerCertificateManager(packConfig, packConfig.getNioPath().getParent());
	}

	@Override
	public boolean isValid() {
		return Files.exists(this.getServerpackFolder().resolve("pack.json"));
	}

	@Override
	public void initialize() {
		// load modpack config
		JsonObject packConfig = Utils.loadJson(getServerpackFolder().resolve("pack.json")).getAsJsonObject();

		if(!packConfig.has("mods")) {
			LOGGER.error("pack configuration for Server Curse Manager is missing mods list");
			throw new IllegalArgumentException("mods not specified in modpack configuration");
		}

		this.loadMappings();
		this.loadedModNames = new HashSet<>();

		final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() / 2);
		final ExecutorService singleExcecutor = Executors.newSingleThreadExecutor();

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ZipOutputStream zos = new ZipOutputStream(baos);
		
		//containing all mod objects that get saved in the manifest.json file in the modpack zip
		final JsonArray manifestMods = new JsonArray();

		JsonArray mods = packConfig.getAsJsonArray("mods");
		for(JsonElement modE : mods) {
			final JsonObject mod = modE.getAsJsonObject();

			final String source = mod.getAsJsonPrimitive("source").getAsString();
			if("curse".equals(source)) {
				int projectID = mod.getAsJsonPrimitive("projectID").getAsInt();
				int fileID = mod.getAsJsonPrimitive("fileID").getAsInt();

				executorService.execute(() -> {
					if(!this.hasFile(projectID, fileID)) {
						try {
							final ModMapping mapping = CurseDownloader.downloadMod(projectID, fileID, getServermodsFolder());
							this.addMapping(mapping);
							
							singleExcecutor.submit(() -> {
								manifestMods.add(mod);
								this.loadedModNames.add(mapping.getFileName());
							});
						}catch(IOException e) {
							LOGGER.catching(e);
							return;
						}
					}
				});

			}else if("local".equals(source)) {
				String modPath = mod.getAsJsonPrimitive("mod").getAsString();
				Path sourcePath = Paths.get(modPath);
				String modName = sourcePath.getFileName().toString();

				executorService.execute(() -> {
					if(!Files.isRegularFile(sourcePath) || !Files.exists(sourcePath)) {
						LOGGER.error("mod path {} does not point to a file", modPath);
						return;
					}
					try {
						Files.copy(sourcePath, getServermodsFolder().resolve(modName), StandardCopyOption.REPLACE_EXISTING);
					}catch(IOException e) {
						LOGGER.catching(e);
						return;
					}
					
					JsonObject modManifest = new JsonObject();
					modManifest.addProperty("source", source);
					modManifest.addProperty("file", modName);
					
					singleExcecutor.submit(() -> {
						ZipEntry entry = Utils.getStableEntry("mods/" + modName);
						entry.setMethod(ZipOutputStream.STORED); //dont compress jar files
						try {
							zos.putNextEntry(entry);
							Files.copy(sourcePath, zos);
							zos.closeEntry();
						}catch(IOException e) {
							LOGGER.catching(e);
							return;
						}
						
						manifestMods.add(modManifest);
						this.loadedModNames.add(modName);
					});
				});
			}else {
				LOGGER.error("Unkown source {} for a mod", source);
				continue;
			}
		}
		
		//gather aditional files
		if(packConfig.has("additional")) {
			JsonArray additional = packConfig.getAsJsonArray("additional");
			for(JsonElement fileE : additional) {
				JsonObject additionalFile = fileE.getAsJsonObject();
				
				String file = additionalFile.getAsJsonPrimitive("file").getAsString();
				String target = additionalFile.getAsJsonPrimitive("target").getAsString();
				
				Path filePath = Paths.get(file);
				if(!Files.isRegularFile(filePath) || !Files.exists(filePath)) {
					LOGGER.error("additional file {} does not point to a file", filePath);
					continue;
				}
				
				singleExcecutor.submit(() -> {
					ZipEntry entry = Utils.getStableEntry("additional/" + target);
					try {
						zos.putNextEntry(entry);
						Files.copy(filePath, zos);
						zos.closeEntry();	
					}catch(IOException e) {
						LOGGER.catching(e);
					}
				});
			}
		}

		executorService.shutdown();
		singleExcecutor.shutdown();
		try {
			executorService.awaitTermination(2, TimeUnit.HOURS);
			singleExcecutor.awaitTermination(2, TimeUnit.HOURS);
		}catch(InterruptedException e) {
			LOGGER.catching(e);
		}

		// create modpack zip
		try {
			ZipEntry manifestEntry = Utils.getStableEntry("manifest.json");
			zos.putNextEntry(manifestEntry);
			Utils.saveJson(manifestMods, zos);
			zos.closeEntry();
			zos.close(); //Close pack zip
		}catch(IOException e) {
			LOGGER.catching(e);
			return;
		}
		
		byte[] packData = baos.toByteArray();
		this.httpServer = new SimpleHttpServer(this, packData);
	}
	
	@Override
	public boolean shouldLoadFile(String modfile) {
		return this.loadedModNames.contains(modfile);
	}
	
	public ServerCertificateManager getCertificateManager() {
		return this.certManager;
	}

}
