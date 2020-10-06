package com.walnutcrasher.servercursemanager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraftforge.forgespi.locating.IModLocator;

public abstract class SideHandler {
	
	protected static final Logger LOGGER = LogManager.getLogger();
	
	protected final FileConfig packConfig;
	
	protected final Path serverModsPath;
	
	protected String configServer;
	protected int configPort;
	protected String configCertificate;
	protected String configKey;
	
	protected List<ModMapping> modMappings;
	
	private Path serverpackFolder;
	
	protected SideHandler(Path gameDir) {		
		this.serverpackFolder = Utils.createOrGetDirectory(gameDir, "serverpack");
		this.serverModsPath = Utils.createOrGetDirectory(gameDir, "servermods");
		
		this.packConfig = CommentedFileConfig.builder(serverpackFolder.resolve("config.toml"))
				.preserveInsertionOrder()
				.defaultResource("config.toml")
				.build();
		
		this.packConfig.load();
		this.packConfig.close();
		
		this.configServer = this.packConfig.<String>get("config.server");
		this.configPort = this.packConfig.getInt("config.port");
		this.configCertificate = this.packConfig.<String>get("config.certificate");
		this.configKey = this.packConfig.<String>get("config.key");
		
		if(isBlank(configServer) || configPort <= 0 || isBlank(configCertificate) || isBlank(configKey)) {
            LOGGER.fatal("Invalid configuration for Server Curse Manager found: {}, please delete or correct before trying again", this.packConfig.getNioPath());
			throw new IllegalStateException("Invalid Configuration");
		}
	}
	
	protected void loadMappings() {
		this.modMappings = Collections.synchronizedList(new ArrayList<>());
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
		this.modMappings.add(mapping);
	}
	
	protected void saveAndCloseMappings() {
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
	
	protected boolean hasFile(int projectID, int fileID) {
		Optional<ModMapping> modMapping = this.modMappings.stream().filter(mod -> mod.projectID == projectID && mod.fileID == fileID).findAny();
		if(!modMapping.isPresent()) {
			return false;
		}
		
		Path modFile = this.serverModsPath.resolve(modMapping.get().fileName);
		if(!Files.exists(modFile)) {
			return false;
		}
		
		//TODO: check if hash match
		
		return true;
	}
	
	public abstract boolean isValid();
	
	public abstract void initialize();
	
	protected Path getServerpackFolder() {
		return this.serverpackFolder;
	}
	
	public Path getServermodsFolder() {
		return this.serverModsPath;
	}
	
	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty(); 
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
