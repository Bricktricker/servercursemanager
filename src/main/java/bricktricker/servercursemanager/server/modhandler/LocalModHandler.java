package bricktricker.servercursemanager.server.modhandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import bricktricker.servercursemanager.Utils;

public class LocalModHandler extends ModHandler {
    
    private static final Logger LOGGER = LogManager.getLogger();
    
    public LocalModHandler(Path serverModsPath, ExecutorService threadPool) {
        super(serverModsPath, threadPool);
    }

    @Override
    public CompletableFuture<ModResult> handleMod(JsonObject mod, ZipOutputStream zos) {
        String modPath = mod.getAsJsonPrimitive("mod").getAsString();
        Path sourcePath = Paths.get(modPath);
        String modName = sourcePath.getFileName().toString();
        
        if(!Files.isRegularFile(sourcePath) || !Files.exists(sourcePath)) {
            LOGGER.error("Mod path {} does not point to a file", modPath);
            return CompletableFuture.failedFuture(new IOException());
        }
        
        // Copy local mods to the servermods folder
        var copyTask = CompletableFuture.runAsync(() -> {
            try {
                Files.copy(sourcePath, serverModsPath.resolve(modName), StandardCopyOption.REPLACE_EXISTING);
            }catch(IOException e) {
                LOGGER.catching(e);
                throw new UncheckedIOException(e);
            }
        }, threadPool);
        
        JsonObject manifestMod = null;
        if(loadOnClient(mod)) {
            manifestMod = new JsonObject();
            manifestMod.addProperty("source", "local");
            manifestMod.addProperty("file", modName);
            
            // Copy local mods to modpack.zip
            ZipEntry entry = Utils.getStableEntry("mods/" + modName);
            try {
                zos.putNextEntry(entry);
                Files.copy(sourcePath, zos);
                zos.closeEntry();
            }catch(IOException e) {
                LOGGER.catching(e);
                return CompletableFuture.failedFuture(e);
            }   
        }
        
        ModResult result = new ModResult(manifestMod, modName, loadOnServer(mod));
        return copyTask.thenApply(v -> result);
    }

    @Override
    public void close() {}

}
