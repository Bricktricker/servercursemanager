package bricktricker.servercursemanager.server.modhandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.StreamSupport;
import java.util.zip.ZipOutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import bricktricker.servercursemanager.CurseDownloader;
import bricktricker.servercursemanager.Utils;
import bricktricker.servercursemanager.server.ServerSideHandler.ModMapping;

public class CurseModHandler extends ModHandler {
    
    private static final Logger LOGGER = LogManager.getLogger();
    
    private final List<ModMapping> modMappings;
    
    public CurseModHandler(Path serverModsPath, ExecutorService threadPool) {
        super(serverModsPath, threadPool);
        this.modMappings = new ArrayList<>();
        
        Path modMappingsFile = this.serverModsPath.resolve("files.json");
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
                String downloadUrl = mod.getAsJsonPrimitive("url").getAsString();
                String sha1 = mod.getAsJsonPrimitive("sha1").getAsString();
    
                return new ModMapping(projectID, fileID, fileName, downloadUrl, sha1);
            })
            .forEach(modMappings::add);
    }

    @Override
    public CompletableFuture<ModResult> handleMod(JsonObject mod, ZipOutputStream zos) {
        int projectID = mod.getAsJsonPrimitive("projectID").getAsInt();
        int fileID = mod.getAsJsonPrimitive("fileID").getAsInt();

        var future = this.getMapping(projectID, fileID)
            .map(CompletableFuture::completedFuture)
            .orElseGet(() -> {
                LOGGER.debug("Downloading curse file {} for project {}", fileID, projectID);
                return CurseDownloader.downloadMod(projectID, fileID, serverModsPath, this.threadPool);
            })
            .thenApply(m -> {
                this.addMapping(m);
                return m;
            })
            .exceptionally(e -> {
                LOGGER.catching(e);
                return null;
            });
        
        var res = future.thenApply(mapping -> {
            if(mapping == null) {
                return null;
            }
            
            JsonObject manifestMod = null;
            if(loadOnClient(mod)) {
                manifestMod = new JsonObject();
                manifestMod.addProperty("source", "remote");
                manifestMod.addProperty("url", mapping.downloadUrl());
                manifestMod.addProperty("file", mapping.fileName());
                manifestMod.addProperty("sha1", mapping.sha1());   
            }
            
            return new ModResult(manifestMod, mapping.fileName(), loadOnServer(mod));
        });
        
        return res;
    }

    @Override
    public void close() {
        JsonArray mappingArray = new JsonArray();
        for(ModMapping mapping : this.modMappings) {
            JsonObject mod = new JsonObject();

            mod.addProperty("projectID", mapping.projectID());
            mod.addProperty("fileID", mapping.fileID());
            mod.addProperty("fileName", mapping.fileName());
            mod.addProperty("url", mapping.downloadUrl());
            mod.addProperty("sha1", mapping.sha1());

            mappingArray.add(mod);
        }
        
        Utils.saveJson(mappingArray, this.serverModsPath.resolve("files.json"));
    }
    
    private void addMapping(ModMapping mapping) {
        synchronized(this.modMappings) {
            this.modMappings.removeIf(mod -> mod.projectID() == mapping.projectID() && mod.fileID() == mapping.fileID());
            this.modMappings.add(mapping);
        }
    }
    
    private Optional<ModMapping> getMapping(int projectID, int fileID) {
        Optional<ModMapping> modMapping;
        synchronized(this.modMappings) {
            modMapping = this.modMappings.stream().filter(mod -> mod.projectID() == projectID && mod.fileID() == fileID).findAny();
        }

        modMapping = modMapping.filter(mapping -> {
            Path modFile = this.serverModsPath.resolve(mapping.fileName());
            return Files.exists(modFile);
        });

        // check hash
        modMapping = modMapping.filter(mapping -> {
            Path modFile = this.serverModsPath.resolve(mapping.fileName());
            String hash = Utils.computeSha1Str(modFile);
            return hash.equals(mapping.sha1());
        });

        return modMapping;
    }

}
