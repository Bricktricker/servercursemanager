package bricktricker.servercursemanager.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.logging.log4j.util.TriConsumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import bricktricker.servercursemanager.CopyOption;
import bricktricker.servercursemanager.SideHandler;
import bricktricker.servercursemanager.Utils;
import bricktricker.servercursemanager.client.ClientSideHandler;
import bricktricker.servercursemanager.server.modhandler.CurseModHandler;
import bricktricker.servercursemanager.server.modhandler.LocalModHandler;
import bricktricker.servercursemanager.server.modhandler.ModHandler;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager;

public class ServerSideHandler extends SideHandler {
	
	private JsonObject packConfig;

	public ServerSideHandler(Path gameDir) {
		super(gameDir);
		Path packConfigPath = this.serverpackFolder.resolve("pack.json");
		if(!Files.exists(packConfigPath) || !Files.isRegularFile(packConfigPath)) {
			try {
				Files.copy(ClientSideHandler.class.getResourceAsStream(this.getConfigFile()), packConfigPath);
			}catch(IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		this.packConfig = Utils.loadJson(packConfigPath).getAsJsonObject();
	}

	@Override
	protected String getConfigFile() {
		return "/defaultserverconfig.json";
	}

	/**
	 * Checks if the pack file, specified in the server.packfile config key exists
	 */
	@Override
	public boolean isValid() {
		if(!this.packConfig.has("port")) {
			LOGGER.fatal("Invalid configuration for Server Curse Manager found: 'port' not specified in the config file");
			return false;
		}
		int port = this.packConfig.getAsJsonPrimitive("port").getAsInt();
		if(port <= 0 || port > 65535) {
			LOGGER.fatal("Invalid configuration for Server Curse Manager found: 'port' must be a valid port number, currently {}", port);
			return false;
		}
		
		if(!packConfig.has(SideHandler.MODS) || !packConfig.get(SideHandler.MODS).isJsonArray()) {
			LOGGER.fatal("pack configuration for Server Curse Manager is missing mods list");
			return false;
		}

		LOGGER.debug("Configuration: port {}, num mods: {}", port, packConfig.getAsJsonArray(SideHandler.MODS).size());
		return true;
	}

	@Override
	public void initialize() {
		super.initialize();
		
		CopyOption globalCopyOption = CopyOption.KEEP;
		if(packConfig.has("copyOption")) {
			globalCopyOption = CopyOption.getOption(packConfig.getAsJsonPrimitive("copyOption").getAsString());
		}

		int numDownloadThreads = Math.max(Runtime.getRuntime().availableProcessors() / 2, 1);
		this.downloadThreadpool = new ThreadPoolExecutor(0, numDownloadThreads, 10L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
		
		// Mod handler
		var curseModHandler = new CurseModHandler(getServermodsFolder(), downloadThreadpool);
		var localModHandler = new LocalModHandler(getServermodsFolder(), downloadThreadpool);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ZipOutputStream zos = new ZipOutputStream(baos);

		// containing all mod objects that get saved in the manifest.json file in the
		// modpack zip
		final JsonArray manifestMods = new JsonArray();

		// we download the mods asynchronously, so we save the futures here
		final List<CompletableFuture<ModHandler.ModResult>> modResultFutures = new ArrayList<>();
		
		JsonArray mods = packConfig.getAsJsonArray(SideHandler.MODS);
		for(JsonElement modE : mods) {
			final JsonObject mod = modE.getAsJsonObject();

			final String source = mod.getAsJsonPrimitive("source").getAsString();
			if("curse".equalsIgnoreCase(source)) {
			    modResultFutures.add(curseModHandler.handleMod(mod, zos));
			}else if("local".equals(source)) {
			    modResultFutures.add(localModHandler.handleMod(mod, zos));
			}else {
				LOGGER.error("Unkown source {} for a mod", source);
			}
		}
		
		// Additional client mods:
		JsonArray clientPacksManifest = new JsonArray();
		if(packConfig.has(SideHandler.CLIENT_PACKS)) {
		    JsonArray clientPacks = packConfig.getAsJsonArray(SideHandler.CLIENT_PACKS);
		    for(JsonElement packE : clientPacks) {
		        JsonObject clientPack = packE.getAsJsonObject();
		        
		        String name = clientPack.getAsJsonPrimitive("name").getAsString();
		        JsonArray clientMods = clientPack.getAsJsonArray("mods");
		        
		        JsonObject clientPackManifest = new JsonObject();
		        clientPackManifest.addProperty("name", name);
		        
		        final List<CompletableFuture<ModHandler.ModResult>> clientResultFutures = new ArrayList<>();
		        
		        for(JsonElement modE : clientMods) {
		            JsonObject clientMod = modE.getAsJsonObject();
		            clientMod.addProperty("side", "client"); // Force only loading on client
		            
		            final String source = clientMod.getAsJsonPrimitive("source").getAsString();
		            if("curse".equalsIgnoreCase(source)) {
		                clientResultFutures.add(curseModHandler.handleMod(clientMod, zos));
		            }else if("local".equals(source)) {
		                clientResultFutures.add(localModHandler.handleMod(clientMod, zos));
		            }else {
		                LOGGER.error("Unkown source {} for a mod", source);
		            }
		        }
		        
		        JsonArray clientManifestMods = new JsonArray(clientResultFutures.size());
		        for(var future : clientResultFutures) {
		            ModHandler.ModResult result = future.join();
		            if(result == null) {
		                continue;
		            }
		            clientManifestMods.add(result.manifestData());
		        }
		        clientPackManifest.add("mods", clientManifestMods);
		        clientPacksManifest.add(clientPackManifest);
		    }
		    
		}
		
		// get all downloaded mods, add the to the 'manifestMods' list
        for(var future : modResultFutures) {
            ModHandler.ModResult result = future.join();
            if(result == null) {
                continue;
            }
            
            if(result.loadOnServer()) {
                this.loadedModNames.add(result.modName());   
            }
            
            var manifestData = result.manifestData();
            if(manifestData != null) {
                manifestMods.add(manifestData);   
            }
        }

		// gather aditional files
		JsonArray manifestAdditional = new JsonArray();
		if(packConfig.has(SideHandler.ADDITIONAL)) {
			
			// Keep track of added additional files
			HashSet<String> filesToCopy = new HashSet<>();
			TriConsumer<Path, String, CopyOption> fileAdder = (p, targetStr, copyOption) -> {
				// Check if we have already added this file
				if(filesToCopy.contains(targetStr)) {
					return;
				}
				filesToCopy.add(targetStr);
				
				String pathInZip = SideHandler.ADDITIONAL + "/" + targetStr;
				LOGGER.debug("Adding additional file {} as target {}, stored as {} to modpack", p.toString(), targetStr, pathInZip);
				ZipEntry entry = Utils.getStableEntry(pathInZip);
				try {
					zos.putNextEntry(entry);
					Files.copy(p, zos);
					zos.closeEntry();
					
					JsonObject additionalObj = new JsonObject();
					additionalObj.addProperty("file", targetStr);
					additionalObj.addProperty("copyOption", copyOption.configName());
					
					manifestAdditional.add(additionalObj);
				}catch(IOException e) {
					LOGGER.catching(e);
				}
			}; 
			
			JsonArray additional = packConfig.getAsJsonArray(SideHandler.ADDITIONAL);
			for(JsonElement fileE : additional) {
				JsonObject additionalFile = fileE.getAsJsonObject();

				String file = additionalFile.getAsJsonPrimitive("file").getAsString();
				String target = additionalFile.getAsJsonPrimitive("target").getAsString();
				CopyOption copyOption = additionalFile.has("copyOption") ? CopyOption.getOption(additionalFile.getAsJsonPrimitive("copyOption").getAsString()) : globalCopyOption;

				Path filePath = Paths.get(file);
				boolean isFile = Files.isRegularFile(filePath);
				if(!isFile && !Files.isDirectory(filePath) && !Files.exists(filePath)) {
					LOGGER.error("additional file {} does not point to a file", filePath);
					continue;
				}

				// Check if it is a file or folder
				if(!isFile) {
					// we have a folder
					if(!target.endsWith("/")) {
						LOGGER.error("{} points to a folder but {} is not. 'target' has to end in a '/'", file, target);
						continue;
					}

					try {
						Files.walk(filePath)
							.filter(Files::isRegularFile)
							.sorted() // Sort the files, so its stable across restarts
							.forEach(p -> {
								Path relPath = filePath.relativize(p);
								Path relTarget = Paths.get(target).resolve(relPath).normalize();
	
								// Custom build target string, to use '/' seperator
								StringBuilder s = new StringBuilder();
								for(Path folder : relTarget.getParent()) {
									s.append(folder.toString());
									s.append("/");
								}
								s.append(relTarget.getFileName());
	
								String targetStr = s.toString();
								
								fileAdder.accept(p, targetStr, copyOption);
							});
					}catch(IOException e) {
						LOGGER.catching(e);
					}

				}else {
					fileAdder.accept(filePath, target, copyOption);
				}
			}
		}
		
		curseModHandler.close();
		localModHandler.close();
		
		// create modpack zip
		JsonObject manifest = new JsonObject();
		manifest.add(SideHandler.MODS, manifestMods);
		manifest.add(SideHandler.ADDITIONAL, manifestAdditional);
		manifest.add(SideHandler.CLIENT_PACKS, clientPacksManifest);

		try {
			ZipEntry manifestEntry = Utils.getStableEntry("manifest.json");
			zos.putNextEntry(manifestEntry);
			Utils.saveJson(manifest, zos);
			zos.closeEntry();
			zos.close(); // Close pack zip
		}catch(IOException e) {
			LOGGER.catching(e);
			return;
		}

		byte[] packData = baos.toByteArray();
		LOGGER.debug("Generated modpack {} bytes big", packData.length);

		// Initialize ProfileKeyPairBasedSecurityManager
		ProfileKeyPairBasedSecurityManager.getInstance();
		
		RequestServer.run(this, packData);
	}

	public int getPort() {
		return this.packConfig.getAsJsonPrimitive("port").getAsInt();
	}

    public static record ModMapping(int projectID, int fileID, String fileName, String downloadUrl, String sha1) {
    }
}
