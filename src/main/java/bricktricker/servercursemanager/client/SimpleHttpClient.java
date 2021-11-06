package bricktricker.servercursemanager.client;

import cpw.mods.forge.cursepacklocator.HashChecker;
import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Based on https://github.com/OrionDevelopment/serverpacklocator/blob/4ed6a61ec664f403a426e52e7862c36bea5c8f0f/src/main/java/cpw/mods/forge/serverpacklocator/client/SimpleHttpClient.java
 * @author cpw, OrionDevelopment
 */
public class SimpleHttpClient {
    private static final Logger LOGGER = LogManager.getLogger();
    private final String passwordHash;
    private final ClientSideHandler clientSideHandler;
    private final Future<Boolean> downloadJob;
    private boolean downloadSuccessful = false;
    private final String currentModpackHash;

    public SimpleHttpClient(final ClientSideHandler clientSideHandler, String currentModpackHash, String password) {
    	this.passwordHash = HashChecker.computeSHA256(password);
    	this.currentModpackHash = currentModpackHash;
    	this.clientSideHandler = clientSideHandler;
        downloadJob = Executors.newSingleThreadExecutor().submit(() -> this.connectAndDownload(clientSideHandler.getRemoteServer()));
    }

    private boolean connectAndDownload(String server) {
    	if(server.endsWith("/")) {
    		server.substring(0, server.length() - 1);	
    	}
        LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Connecting to server at " + server);
        
        URL url;
		try {
			url = new URL(server + "/modpack.zip?hash=" + currentModpackHash);
		}catch(MalformedURLException e) {
			throw new UncheckedIOException(e);
		}
		
		try {
			var connection = url.openConnection();
			connection.setRequestProperty("Authentication", this.passwordHash);
        
	        try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream())) {
	        	LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Receiving modpack.zip");
	        	final Path modpack = clientSideHandler.getServerpackFolder().resolve("modpack.zip");
	        	try (OutputStream os = Files.newOutputStream(modpack)) {
	                in.transferTo(os);
	                downloadSuccessful = true;
	            } catch (IOException e) {
	                LOGGER.catching(e);
	            }
	        }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download manifest", e);
        }
        
        if (!downloadSuccessful) {
            LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Failed to complete transaction at " + server);
            LOGGER.error("Failed to receive successful data connection from server.");
            return false;
        }
        LOGGER.debug("Successfully downloaded pack from server");
        LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Downloaded modpack.zip from server");
        return true;
    }

    boolean waitForResult() throws ExecutionException {
        try {
            return downloadJob.get();
        } catch (InterruptedException e) {
            return false;
        }
    }
}