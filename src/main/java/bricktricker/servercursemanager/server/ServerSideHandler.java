package bricktricker.servercursemanager.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
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
import bricktricker.servercursemanager.client.ClientSideHandler;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager;

public class ServerSideHandler extends SideHandler {

	private List<ModMapping> modMappings;
	
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

	protected void loadMappings() {
		this.modMappings = new ArrayList<>();
		Path modMappingsFile = this.serverModsPath.resolve("files.json");
		if(!Files.exists(modMappingsFile)) {
			return;
		}

		JsonArray mappingArray = Utils.loadJson(modMappingsFile).getAsJsonArray();
		StreamSupport.stream(mappingArray.spliterator(), false)
			.map(JsonElement::getAsJsonObject)
			.map(mod -> {
				int projectID = mod.getAsJsonPrimitive("projectID").getAsInt();
				int fileID = mod.getAsJsonPrimitive("fileID").getAsInt();
				String fileName = mod.getAsJsonPrimitive("fileName").getAsString();
				String downloadUrl = mod.getAsJsonPrimitive("url").getAsString();
				String sha1 = mod.getAsJsonPrimitive("sha1").getAsString();
	
				return new ModMapping(projectID, fileID, fileName, downloadUrl, sha1);
			})
			.forEach(modMappings::add);
	}

	private void addMapping(ModMapping mapping) {
		synchronized(this.modMappings) {
			this.modMappings.removeIf(mod -> mod.projectID == mapping.projectID && mod.fileID == mapping.fileID);
			this.modMappings.add(mapping);
		}
	}

	private void saveAndCloseMappings() {
		if(this.modMappings == null) {
			return;
		}

		JsonArray mappingArray = new JsonArray();
		for(ModMapping mapping : this.modMappings) {
			JsonObject mod = new JsonObject();

			mod.addProperty("projectID", mapping.projectID);
			mod.addProperty("fileID", mapping.fileID);
			mod.addProperty("fileName", mapping.fileName);
			mod.addProperty("url", mapping.downloadUrl);
			mod.addProperty("sha1", mapping.sha1);

			mappingArray.add(mod);
		}

		Utils.saveJson(mappingArray, this.serverModsPath.resolve("files.json"));
		this.modMappings = null;
	}

	private Optional<ModMapping> getMapping(int projectID, int fileID) {
		Optional<ModMapping> modMapping;
		synchronized(this.modMappings) {
			modMapping = this.modMappings.stream().filter(mod -> mod.projectID == projectID && mod.fileID == fileID).findAny();
		}

		modMapping.filter(mapping -> {
			Path modFile = this.serverModsPath.resolve(mapping.fileName);
			return Files.exists(modFile);
		});

		// check hash
		modMapping.filter(mapping -> {
			Path modFile = this.serverModsPath.resolve(mapping.fileName);
			String hash = Utils.computeSha1Str(modFile);
			return hash.equals(mapping.sha1);
		});

		return modMapping;
	}

	public void doCleanup() {
		super.doCleanup();
		this.saveAndCloseMappings();
	}

	@Override
	public void initialize() {
		super.initialize();

		this.loadMappings();

		int numDownloadThreads = Math.max(Runtime.getRuntime().availableProcessors() / 2, 1);
		this.downloadThreadpool = new ThreadPoolExecutor(1, numDownloadThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
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
				}, downloadThreadpool).thenAcceptAsync(mapping -> {
					if(mapping != null) {
						var manifestMod = new JsonObject();
						manifestMod.addProperty("source", "remote");
						manifestMod.addProperty("url", mapping.downloadUrl());
						manifestMod.addProperty("file", mapping.fileName());
						manifestMod.addProperty("sha1", mapping.sha1());
						manifestMods.add(manifestMod);

						this.loadedModNames.add(mapping.fileName());
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
				}, downloadThreadpool).thenAcceptAsync(modManifest -> {
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

							boolean alreadyPresent = filesToCopy.stream().map(Pair::getRight).anyMatch(x -> x.equals(targetStr));

							if(!alreadyPresent) {
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

			RequestServer.run(this, packData);

		}, r -> r.run());

		// Initialize ProfileKeyPairBasedSecurityManager
		ProfileKeyPairBasedSecurityManager.getInstance();
	}

	public int getPort() {
		return this.packConfig.getAsJsonPrimitive("port").getAsInt();
	}

	public static record ModMapping(int projectID, int fileID, String fileName, String downloadUrl, String sha1) {
	}

}
