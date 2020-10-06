package com.walnutcrasher.servercursemanager.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.walnutcrasher.servercursemanager.CurseDownloader;
import com.walnutcrasher.servercursemanager.SideHandler;
import com.walnutcrasher.servercursemanager.Utils;

import cpw.mods.forge.serverpacklocator.server.ServerCertificateManager;

public class ServerSideHandler extends SideHandler {
	
	private ServerCertificateManager certManager;
	
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
		
		final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() / 2);
		
		JsonArray mods = packConfig.getAsJsonArray("mods");
		for(JsonElement modE : mods) {
			JsonObject mod = modE.getAsJsonObject();
			
			String source = mod.getAsJsonPrimitive("source").getAsString();
			if("curse".equals(source)) {
				int projectID = mod.getAsJsonPrimitive("projectID").getAsInt();
				int fileID = mod.getAsJsonPrimitive("fileID").getAsInt();
				
				if(!this.hasFile(projectID, fileID)) {
					executorService.execute(() -> {
						try {
							ModMapping mapping = CurseDownloader.downloadMod(projectID, fileID, getServermodsFolder());
							this.addMapping(mapping);
						}catch(IOException e) {
							LOGGER.catching(e);
						}
					});
				}
				
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
					}
				});
			}else {
				LOGGER.error("Unkown source {} for a mod", source);
				continue;
			}	
		}
		
		executorService.shutdown();
		try {
			executorService.awaitTermination(2, TimeUnit.HOURS);
		}catch(InterruptedException e) {
			LOGGER.catching(e);
		}
		
		// start HTTPS server
	}

}
