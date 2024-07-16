package bricktricker.servercursemanager.server.modhandler;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonObject;

public abstract class ModHandler implements AutoCloseable {
    
    protected final Path serverModsPath;
    protected final ExecutorService threadPool;
    
    public ModHandler(Path serverModsPath, ExecutorService threadPool) {
        this.serverModsPath = serverModsPath;
        this.threadPool = threadPool;
    }

    public abstract CompletableFuture<ModResult> handleMod(JsonObject mod, ZipOutputStream zos);
    
    protected static boolean loadOnServer(JsonObject mod) {
        if(!mod.has("side")) {
            return true;
        }
        
        String side = mod.getAsJsonPrimitive("side").getAsString().toLowerCase(Locale.ROOT);
        switch(side) {
            case "client":
                return false;
            case "server":
            case "both":
                return true;
             default:
                 throw new IllegalArgumentException("Unkown side: " + side);
        }
    }
    
    protected static boolean loadOnClient(JsonObject mod) {
        if(!mod.has("side")) {
            return true;
        }
        
        String side = mod.getAsJsonPrimitive("side").getAsString().toLowerCase(Locale.ROOT);
        switch(side) {
            case "server":
                return false;
            case "client":
            case "both":
                return true;
             default:
                 throw new IllegalArgumentException("Unkown side: " + side);
        }
    }
    
    public record ModResult(JsonObject manifestData, String modName, boolean loadOnServer) {}

}
