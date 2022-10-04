package bricktricker.servercursemanager.client;

import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import cpw.mods.forge.serverpacklocator.secure.ProfileKeyPairBasedSecurityManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Based on https://github.com/OrionDevelopment/serverpacklocator/blob/4ed6a61ec664f403a426e52e7862c36bea5c8f0f/src/main/java/cpw/mods/forge/serverpacklocator/client/SimpleHttpClient.java
 * @author cpw, OrionDevelopment
 */
public class SimpleHttpClient {
    private static final Logger LOGGER = LogManager.getLogger();
    private final ClientSideHandler clientSideHandler;
    private final Future<Boolean> downloadJob;
    private boolean downloadSuccessful = false;
    private final String currentModpackHash;
    
    private byte[] challenge;

    public SimpleHttpClient(final ClientSideHandler clientSideHandler, String currentModpackHash) {
    	this.currentModpackHash = currentModpackHash;
    	this.clientSideHandler = clientSideHandler;
        downloadJob = Executors.newSingleThreadExecutor().submit(() -> this.connectAndDownload(clientSideHandler.getRemoteServer()));
    }

    private boolean connectAndDownload(String server) {
    	if(!server.endsWith("/")) {
    		server = server + "/";
    	}
        LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Connecting to server at " + server);
        
        try {
			downloadChallenge(server);
		}catch(IOException e) {
			throw new UncheckedIOException(e);
		}
        
        URL url;
		try {
			url = new URL(server + "modpack.zip?hash=" + currentModpackHash);
		}catch(MalformedURLException e) {
			throw new UncheckedIOException(e);
		}
		
		try {
			var connection = url.openConnection();
			ProfileKeyPairBasedSecurityManager.getInstance().onClientConnectionCreation(connection, this.challenge);
        
	        try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream())) {
	        	int code = ((HttpURLConnection)connection).getResponseCode();
	        	if(code == 200) {
		        	LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Receiving modpack.zip");
		        	final Path modpack = clientSideHandler.getServerpackFolder().resolve("modpack.zip");
		        	try (OutputStream os = Files.newOutputStream(modpack)) {
		                in.transferTo(os);
		                downloadSuccessful = true;
		            } catch (IOException e) {
		                LOGGER.catching(e);
		            }	
	        	}else if(code == 304){
	        		LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Using old modpack.zip");
	        		downloadSuccessful = true;
	        	}else {
	        		LOGGER.error("Could not fetch modpack.zip, got status code {}", code);
	        	}
	        }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download manifest", e);
        }
        
        if (!downloadSuccessful) {
            LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Failed to complete download at " + server);
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
    
    protected void downloadChallenge(final String serverHost) throws IOException {
    	var address = serverHost + "challenge";

    	LOGGER.info("Requesting challenge from: " + serverHost);
    	LaunchEnvironmentHandler.INSTANCE.addProgressMessage("Requesting Challenge from: " + serverHost);

    	var url = new URL(address);
        var connection = url.openConnection();

        try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
        	final String challengeStr = in.readLine();
        	LOGGER.info("Got Challenge {}", challengeStr);
        	challenge = Base64.getDecoder().decode(challengeStr);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download challenge", e);
        }
        LOGGER.debug("Received challenge");
    }
}