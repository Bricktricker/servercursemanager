package bricktricker.servercursemanager;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import bricktricker.servercursemanager.server.ServerSideHandler.ModMapping;

public class CurseDownloader {

	public static ModMapping downloadMod(int projectID, int fileID, Path targetDir) throws IOException {
		String metaDataURL = String.format("https://api.curse.tools/v1/cf/mods/%s/files/%s/", projectID, fileID);
		URL url = new URL(metaDataURL);
		String downloadURL = Utils.loadJson(url.openStream()).getAsJsonObject().getAsJsonObject("data").getAsJsonPrimitive("downloadUrl").getAsString();
		
		int lastSlash = downloadURL.lastIndexOf('/');
		if(lastSlash == -1) {
			throw new IOException("download URL does not contain a slash");
		}
		
		String filename = downloadURL.substring(lastSlash+1).replaceAll("\\s+", "_");

		Path target = targetDir.resolve(filename);

		url = new URL(downloadURL);
		Files.copy(url.openStream(), target, StandardCopyOption.REPLACE_EXISTING);
		
		String sha1Hash = Utils.computeSha1(target);

		return new ModMapping(projectID, fileID, filename, downloadURL, sha1Hash);
	}
	
	public static void downloadFile(String downloadURL, String filename, String sha1, Path targetDir) throws IOException {
		Path target = targetDir.resolve(filename);
		if(!Files.exists(target)) {
			URL url = new URL(downloadURL);
			Files.copy(url.openStream(), target, StandardCopyOption.REPLACE_EXISTING);
		}
		
		String computedHash = Utils.computeSha1(target);
		if(!computedHash.equals(sha1)) {
			Files.delete(target);
			throw new IOException("Wrong hash for downloaded file " + downloadURL);
		}
	}

}
