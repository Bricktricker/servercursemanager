package bricktricker.servercursemanager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.google.gson.JsonParser;

import bricktricker.servercursemanager.server.ServerSideHandler.ModMapping;

public class CurseDownloader {

    public static CompletableFuture<ModMapping> downloadMod(int projectID, int fileID, Path targetDir, Executor executor) {
        
        String metaDataURL = String.format("https://api.curse.tools/v1/cf/mods/%s/files/%s/", projectID, fileID);
        URI url;
        try {
            url = new URI(metaDataURL);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.of(30, ChronoUnit.SECONDS))
                .followRedirects(Redirect.NORMAL)
                .executor(executor)
                .build();

        var httpRequest = HttpRequest.newBuilder(url).GET().timeout(Duration.of(30, ChronoUnit.SECONDS)).build();

        var reqFuture = httpClient.sendAsync(httpRequest, BodyHandlers.ofString())
                .thenApply(r -> JsonParser.parseString(r.body()))
                .thenCompose(elem -> {
                    // TODO: Check the used thread

                    String downloadURL = elem.getAsJsonObject()
                            .getAsJsonObject("data")
                            .getAsJsonPrimitive("downloadUrl")
                            .getAsString();
                    
                    int lastSlash = downloadURL.lastIndexOf('/');
                    if (lastSlash == -1) {
                        throw new UncheckedIOException(new IOException("download URL does not contain a slash"));
                    }

                    String filename = downloadURL.substring(lastSlash + 1).replaceAll("\\s+", "_");
                    filename = URLDecoder.decode(filename, StandardCharsets.UTF_8);

                    Path target = targetDir.resolve(filename);

                    URI fileUri;
                    try {
                        fileUri = new URI(downloadURL);
                    } catch (URISyntaxException e) {
                        throw new RuntimeException(e);
                    }

                    var fileReq = HttpRequest.newBuilder(fileUri).GET().timeout(Duration.of(60, ChronoUnit.SECONDS))
                            .build();

                    return httpClient.sendAsync(fileReq,
                            BodyHandlers.ofFile(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE));
                }).thenApply(response -> {
                    Path target = response.body();
                    String sha1Hash = Utils.computeSha1Str(target);

                    String downloadURL = response.request().uri().toString();
                    String fileName = target.getFileName().toString();

                    return new ModMapping(projectID, fileID, fileName, downloadURL, sha1Hash);
                });

        return reqFuture;
    }

    public static void downloadFile(String downloadURL, String filename, String sha1, Path targetDir) throws IOException {
        Path target = targetDir.resolve(filename);
        if (!Files.exists(target)) {
            URL url;
            try {
                URI uri = new URI(downloadURL);
                url = uri.toURL();
            } catch (URISyntaxException e) {
                throw new IOException(e);
            }
            Files.copy(url.openStream(), target, StandardCopyOption.REPLACE_EXISTING);
        }

        String computedHash = Utils.computeSha1Str(target);
        if (!computedHash.equals(sha1)) {
            Files.delete(target);
            throw new IOException("Wrong hash for downloaded file " + downloadURL);
        }
    }

}
