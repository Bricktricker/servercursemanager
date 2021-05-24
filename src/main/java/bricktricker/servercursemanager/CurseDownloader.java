package bricktricker.servercursemanager;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import bricktricker.servercursemanager.SideHandler.ModMapping;
import cpw.mods.forge.cursepacklocator.HashChecker;

public class CurseDownloader {

	private static final Logger LOGGER = LogManager.getLogger();

	public static ModMapping downloadMod(int projectID, int fileID, Path targetDir) throws IOException {
		String metaDataURL = String.format("https://addons-ecs.forgesvc.net/api/v2/addon/%s/file/%s", projectID, fileID);
		URL url = new URL(metaDataURL);
		JsonObject metaData = Utils.loadJson(url.openStream()).getAsJsonObject();

		String filename = metaData.getAsJsonPrimitive("fileName").getAsString().replaceAll("\\s+", "_");
		String downloadURL = metaData.getAsJsonPrimitive("downloadUrl").getAsString();

		Path target = targetDir.resolve(filename);

		url = new URL(downloadURL);
		Files.copy(url.openStream(), target, StandardCopyOption.REPLACE_EXISTING);

		String modHash = String.valueOf(HashChecker.computeHash(target));
		String expectedHash = String.valueOf(metaData.getAsJsonPrimitive("packageFingerprint").getAsLong());

		if(!modHash.equals(expectedHash)) {
			LOGGER.error("Could not download project {} file {}, expected hash {}, but got hash {}", projectID, fileID, expectedHash, modHash);
			throw new IOException("Hash does not match!");
		}

		return new ModMapping(projectID, fileID, filename, modHash);
	}

}
