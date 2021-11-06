package bricktricker.servercursemanager.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.lang3.tuple.Pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import bricktricker.servercursemanager.CopyOption;
import bricktricker.servercursemanager.CurseDownloader;
import bricktricker.servercursemanager.SideHandler;
import bricktricker.servercursemanager.Utils;
import cpw.mods.forge.serverpacklocator.server.SimpleHttpServer;

public class ServerSideHandler extends SideHandler {

	public ServerSideHandler(Path gameDir) {
		super(gameDir);
		//this.certManager = new ServerCertificateManager(packConfig, packConfig.getNioPath().getParent());
	}

	@Override
	protected String getConfigFile() {
		return "/defaultserverconfig.toml";
	}

	@Override
	protected void validateConfig() {
		final String password = this.packConfig.get("server.password");
		final int port = this.packConfig.get("server.port");
		final String packFile = this.packConfig.get("server.packfile");

		LOGGER.debug("Configuration: password length {}, port {}, packFile {}", password.length(), port, packFile);

		if(Utils.isBlank(password, packFile) || port <= 0) {
			LOGGER.fatal("Invalid configuration for Server Curse Manager found: {}, please delete or correct before trying again", this.packConfig.getNioPath());
			throw new IllegalStateException("Invalid Configuration");
		}
	}

	/**
	 * Checks if the pack file, specified in the server.packfile config key exists
	 */
	@Override
	public boolean isValid() {
		return Files.exists(this.getServerpackFolder().resolve(this.packConfig.<String>get("server.packfile")));
	}

	@Override
	public void initialize() {
		super.initialize();

		// load modpack config
		JsonObject packConfig = Utils.loadJson(getServerpackFolder().resolve(this.packConfig.<String>get("server.packfile"))).getAsJsonObject();

		if(!packConfig.has(SideHandler.MODS) || !packConfig.get(SideHandler.MODS).isJsonArray()) {
			LOGGER.error("pack configuration for Server Curse Manager is missing mods list");
			throw new IllegalArgumentException("mods not specified in modpack configuration");
		}

		this.downloadThreadpool = Executors.newFixedThreadPool(Math.max(Runtime.getRuntime().availableProcessors() / 2, 1));
		this.singleExcecutor = Executors.newSingleThreadExecutor();
		final List<CompletableFuture<Void>> futures = new ArrayList<>();

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ZipOutputStream zos = new ZipOutputStream(baos);

		// containing all mod objects that get saved in the manifest.json file in the
		// modpack zip
		final JsonArray manifestMods = new JsonArray();

		JsonArray mods = packConfig.getAsJsonArray(SideHandler.MODS);
		for(JsonElement modE : mods) {
			final JsonObject mod = modE.getAsJsonObject();

			final String source = mod.getAsJsonPrimitive("source").getAsString();
			if("curse".equalsIgnoreCase(source)) {
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
						manifestMods.add(mod);
						this.loadedModNames.add(mapping.getFileName());	
					}
				}, singleExcecutor);
				
				futures.add(future);
				
			}else if("local".equalsIgnoreCase(source)) {
				String modPath = mod.getAsJsonPrimitive("mod").getAsString();
				Path sourcePath = Paths.get(modPath);
				String modName = sourcePath.getFileName().toString();
				
				CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
					if(!Files.isRegularFile(sourcePath) || !Files.exists(sourcePath)) {
						LOGGER.error("mod path {} does not point to a file", modPath);
						return null;
					}
					try {
						Files.copy(sourcePath, getServermodsFolder().resolve(modName), StandardCopyOption.REPLACE_EXISTING);
					}catch(IOException e) {
						LOGGER.catching(e);
						return null;
					}

					JsonObject modManifest = new JsonObject();
					modManifest.addProperty("source", source);
					modManifest.addProperty("file", modName);
					
					return modManifest;
				}, downloadThreadpool)
				.thenAcceptAsync(modManifest -> {
					if(modManifest != null) {
						ZipEntry entry = Utils.getStableEntry("mods/" + modName);
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
					}
				}, singleExcecutor);

				futures.add(future);
				
			}else {
				LOGGER.error("Unkown source {} for a mod", source);
				continue;
			}
		}

		JsonArray manifestAdditional = new JsonArray();
		// gather aditional files
		if(packConfig.has(SideHandler.ADDITIONAL)) {
			List<Pair<Path, String>> filesToCopy = new ArrayList<>();
			JsonArray additional = packConfig.getAsJsonArray(SideHandler.ADDITIONAL);
			for(JsonElement fileE : additional) {
				JsonObject additionalFile = fileE.getAsJsonObject();

				String file = additionalFile.getAsJsonPrimitive("file").getAsString();
				String target = additionalFile.getAsJsonPrimitive("target").getAsString();

				Path filePath = Paths.get(file);
				boolean isFile = Files.isRegularFile(filePath);
				if(!isFile && !Files.isDirectory(filePath) && !Files.exists(filePath)) {
					LOGGER.error("additional file {} does not point to a file", filePath);
					continue;
				}

				if(!isFile) {
					if(!target.endsWith("/")) {
						LOGGER.error("{} points to a folder but {} is not. 'target' has to end in a '/'", file, target);
						continue;
					}

					try {
						Files.walk(filePath).filter(p -> Files.isRegularFile(p)).forEach(p -> {
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
							
							boolean allreadyPresent = filesToCopy.stream()
								.map(Pair::getRight)
								.anyMatch(x -> x.equals(targetStr));
							
							if(!allreadyPresent) {
								filesToCopy.add(Pair.of(p, targetStr));
							}
						});
					}catch(IOException e) {
						LOGGER.catching(e);
					}

				}else {
					filesToCopy.add(Pair.of(filePath, target));
				}
			}
			
			CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
				for(Pair<Path, String> p : filesToCopy) {
					String pathInZip = SideHandler.ADDITIONAL + "/" + p.getRight();
					LOGGER.debug("Adding additional file {} as target {}, stored as {} to modpack", p.getLeft().toString(), p.getRight(), pathInZip);
					ZipEntry entry = Utils.getStableEntry(pathInZip);
					try {
						zos.putNextEntry(entry);
						Files.copy(p.getLeft(), zos);
						zos.closeEntry();
						manifestAdditional.add(p.getRight());
					}catch(IOException e) {
						LOGGER.catching(e);
					}
				}
			}, singleExcecutor);

			futures.add(future);
		}
		
		CompletableFuture<Void> downloadTask = CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));
		this.installTask = downloadTask.thenRunAsync(() -> {

			// create modpack zip
			JsonObject manifest = new JsonObject();
			manifest.add(SideHandler.MODS, manifestMods);
			manifest.add(SideHandler.ADDITIONAL, manifestAdditional);
			CopyOption copyOption = packConfig.has("copyOption") ? CopyOption.getOption(packConfig.getAsJsonPrimitive("copyOption").getAsString()) : CopyOption.OVERWRITE;
			manifest.addProperty("copyOption", copyOption.configName());
	
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
			
			SimpleHttpServer.run(this, packData, this.packConfig.get("server.password"));
		
		}, r -> r.run());
	}

	public int getPort() {
		return this.packConfig.get("server.port");
	}

}
