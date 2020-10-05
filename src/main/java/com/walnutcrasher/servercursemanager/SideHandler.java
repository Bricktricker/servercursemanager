package com.walnutcrasher.servercursemanager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileConfig;

public abstract class SideHandler {
	
	private static final Logger LOGGER = LogManager.getLogger();
	
	protected final FileConfig packConfig;
	
	protected String configServer;
	protected int configPort;
	protected String configCertificate;
	protected String configKey;
	
	protected SideHandler(Path gameDir) {
		Path serverpack = gameDir.resolve("serverpack");
		if(!Files.exists(serverpack)) {
			try {
				Files.createDirectory(gameDir);
			}catch(IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		
		this.packConfig = CommentedFileConfig.builder(serverpack.resolve("config.toml"))
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
	
	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty(); 
	}

}
