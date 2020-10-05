package cpw.mods.forge.serverpacklocator.server;

import com.electronwill.nightconfig.core.file.FileConfig;
import cpw.mods.forge.serverpacklocator.cert.CertificateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sun.security.x509.X500Name;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.*;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * copied from https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/server/ServerCertificateManager.java
 * @author cpw
 * 
 * inlined ifPresentOrElse from here https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/OptionalHelper.java#L8
 */
public class ServerCertificateManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private X509Certificate cert;
    private KeyPair keyPair;

    public ServerCertificateManager(final FileConfig config, final Path serverModsDir) {
        final Optional<String> keyFile = config.getOptional("server.cakey");
        ifPresentOrElse(keyFile
                .map(serverModsDir::resolve)
                .filter(Files::exists),
                path -> CertificateManager.loadKey(path, key->this.keyPair = key),
                () -> CertificateManager.buildNewKeyPair(serverModsDir, keyFile.get(), key->this.keyPair = key)
        );

        final Optional<String> cacertificate = config.getOptional("server.cacertificate");
        ifPresentOrElse(cacertificate
                .map(serverModsDir::resolve)
                .filter(Files::exists),
                path -> CertificateManager.loadCertificates(path, certs -> this.cert = certs.get(0)),
                ()->this.generateCaCert(serverModsDir, cacertificate.get(), config.get("server.name"))
        );

        try {
            if (!(Objects.equals(config.get("server.name"), ((X500Name)this.cert.getSubjectDN()).getCommonName()))) {
                LOGGER.fatal("The certificate has an incorrect name. It will need to be regenerated, as will " +
                        "all dependent certificates.");
                throw new IllegalStateException("Bad certificate");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void generateCaCert(final Path serverModsDir, final String caPath, final String serverName) {
        Consumer<X509Certificate> setter = cert -> this.cert = cert;
        CertificateManager.generateCerts(()->serverName, ()->this.keyPair, setter.andThen(cert -> CertificateManager.writeCertificates(()-> Collections.singletonList(cert), serverModsDir.resolve(caPath))));
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    public X509Certificate getCertificate() {
        return cert;
    }
    
    private static <T> void ifPresentOrElse(Optional<T> optional, Consumer<T> action, Runnable orElse) {
        if (optional.isPresent()) {
            optional.ifPresent(action);
        } else {
            orElse.run();
        }
    }
}