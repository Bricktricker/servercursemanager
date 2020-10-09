package com.walnutcrasher.servercursemanager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import cpw.mods.forge.cursepacklocator.HashChecker;

public abstract class SideHandler {
	
	protected static final Logger LOGGER = LogManager.getLogger();
	protected static final String ADDITIONAL = "additional";
	
	protected final FileConfig packConfig;
	
	protected final Path serverModsPath;
	protected Path serverpackFolder;
	
	protected List<ModMapping> modMappings;
	
	protected Set<String> loadedModNames;
	
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
	
	protected void loadMappings() {
		this.modMappings = new ArrayList<>();
		Path modMappingsFile = this.serverpackFolder.resolve("files.json");
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
				String hash = mod.getAsJsonPrimitive("hash").getAsString();
				return new ModMapping(projectID, fileID, fileName, hash);
			})
			.forEach(modMappings::add);
	}
	
	protected void addMapping(ModMapping mapping) {
		synchronized (this.modMappings) {
			this.modMappings.removeIf(mod -> mod.projectID == mapping.projectID && mod.fileID == mapping.fileID);
			this.modMappings.add(mapping);	
		}
	}
	
	protected void saveAndCloseMappings() {
		if(this.modMappings == null) {
			return;
		}
		
		JsonArray mappingArray = new JsonArray();
		for(ModMapping mapping : this.modMappings) {
			JsonObject mod = new JsonObject();
			
			mod.addProperty("projectID", mapping.projectID);
			mod.addProperty("fileID", mapping.fileID);
			mod.addProperty("fileName", mapping.fileName);
			mod.addProperty("hash", mapping.hash);
			
			mappingArray.add(mod);
		}
		
		Utils.saveJson(mappingArray, this.serverpackFolder.resolve("files.json"));
		this.modMappings = null;
	}
	
	protected Optional<ModMapping> getMapping(int projectID, int fileID) {
		Optional<ModMapping> modMapping;
		synchronized(this.modMappings) {
			modMapping = this.modMappings.stream().filter(mod -> mod.projectID == projectID && mod.fileID == fileID).findAny();	
		}
		
		modMapping.filter(mapping -> {
			Path modFile = this.serverModsPath.resolve(mapping.fileName);
			return Files.exists(modFile);
		});
		
		//check hash
		modMapping.filter(mapping -> {
			Path modFile = this.serverModsPath.resolve(mapping.fileName);
			long hash = HashChecker.computeHash(modFile);
			return String.valueOf(hash).equals(mapping.hash);
		});
		
		return modMapping;
	}
	
	public void doCleanup() {
		this.saveAndCloseMappings();
		this.loadedModNames = null;
	}
	
	public void initialize() {
		this.loadedModNames = new HashSet<>();
		this.loadMappings();
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
	
	protected static class ModMapping {
		private final int projectID;
		private final int fileID;
		private final String fileName;
		private final String hash;
		
		public ModMapping(int projectID, int fileID, String fileName, String hash) {
			this.projectID = projectID;
			this.fileID = fileID;
			this.fileName = fileName;
			this.hash = hash;
		}

		public int getProjectID() {
			return projectID;
		}

		public int getFileID() {
			return fileID;
		}

		public String getFileName() {
			return fileName;
		}

		public String getHash() {
			return hash;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + fileID;
			result = prime * result + projectID;
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if(this == obj)
				return true;
			if(obj == null)
				return false;
			if(getClass() != obj.getClass())
				return false;
			ModMapping other = (ModMapping) obj;
			if(fileID != other.fileID)
				return false;
			if(projectID != other.projectID)
				return false;
			return true;
		}
		
	}

}
