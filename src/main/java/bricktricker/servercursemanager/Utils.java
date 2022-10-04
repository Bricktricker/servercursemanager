package bricktricker.servercursemanager;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public class Utils {

	/**
	 * copied from
	 * https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/DirHandler.java#L9
	 * 
	 * @author cpw
	 */
	public static Path createOrGetDirectory(final Path root, final String name) {
		final Path newDir = root.resolve(name);
		if(Files.exists(newDir) && Files.isDirectory(newDir)) {
			return newDir;
		}

		try {
			Files.createDirectory(newDir);
			return newDir;
		}catch(IOException ioe) {
			throw new UncheckedIOException(ioe);
		}
	}

	public static JsonElement loadJson(Path file) {
		try(Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(r);
		}catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static JsonElement loadJson(InputStream is) {
		try(Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(r);
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

	public static void saveJson(JsonElement json, OutputStream os) {
		String jsonStr = json.toString();
		try {
			os.write(jsonStr.getBytes(StandardCharsets.UTF_8));
		}catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	public static String computeSha1(InputStream is) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			
			byte[] buffer = new byte[4096];
			int len = is.read(buffer);
			while (len != -1) {
				digest.update(buffer, 0, len);
	            len = is.read(buffer);
	        }
			
			return Base64.getEncoder().encodeToString(digest.digest());	
		}catch(NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	public static String computeSha1(Path file) {
		try(var is = Files.newInputStream(file)){
			return computeSha1(is);
		}catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// Copied from
	// https://github.com/MinecraftForge/ForgeGradle/blob/9dcce0d43044018f5f2191df6d702e9f4c651bee/src/common/java/net/minecraftforge/gradle/common/util/Utils.java#L584
	public static ZipEntry getStableEntry(String name) {
		TimeZone _default = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
		ZipEntry ret = new ZipEntry(name);
		ret.setTime(628041600000L);
		TimeZone.setDefault(_default);
		return ret;
	}

	// Copied from:
	// https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/OptionalHelper.java#L8
	public static <T> void ifPresentOrElse(Optional<T> optional, Consumer<T> action, Runnable orElse) {
		if(optional.isPresent()) {
			optional.ifPresent(action);
		}else {
			orElse.run();
		}
	}

	public static boolean isBlank(String... strings) {
		for(String s : strings) {
			if(s == null || s.trim().isEmpty()) {
				return true;
			}
		}
		return false;
	}

}
