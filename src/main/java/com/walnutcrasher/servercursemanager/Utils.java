package com.walnutcrasher.servercursemanager;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public class Utils {
	
	/**
	 * copied from https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/DirHandler.java#L9
	 * @author cpw
	 */
	public static Path createOrGetDirectory(final Path root, final String name) {
        final Path newDir = root.resolve(name);
        if (Files.exists(newDir) && Files.isDirectory(newDir)) {
            return newDir;
        }

        try {
            Files.createDirectory(newDir);
            return newDir;
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }
	
	public static JsonElement loadJson(Path file) {
		try(Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return new JsonParser().parse(r);
		}catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	public static JsonElement loadJson(InputStream is) {
		try(Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
			return new JsonParser().parse(r);
		}catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	public static void saveJson(JsonElement json, Path destination) {
		String jsonStr = json.toString();
		try {
			Files.write(destination, jsonStr.getBytes(StandardCharsets.UTF_8));
		}catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
