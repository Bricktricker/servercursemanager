package cpw.mods.forge.serverpacklocator.client;

import com.electronwill.nightconfig.core.file.FileConfig;
import com.walnutcrasher.servercursemanager.Utils;

import cpw.mods.forge.serverpacklocator.LaunchEnvironmentHandler;
import cpw.mods.forge.serverpacklocator.cert.CertificateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sun.security.x509.X500Name;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Copied from https://github.com/cpw/serverpacklocator/blob/e0e101c8db9008e7b9f9c8e0841fa92bf69ffcdb/src/main/java/cpw/mods/forge/serverpacklocator/client/ClientCertificateManager.java
 * @author cpw
 *
 * Changes:
 * Use ifPresentOrElse from Utils
 * Made class public
 * Changed config paths
 */
@SuppressWarnings("OptionalGetWithoutIsPresent")
public class ClientCertificateManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private final boolean hasCertificate;
    private KeyPair keyPair;
    private List<X509Certificate> certs;

    public ClientCertificateManager(final FileConfig config, final Path configDir, final String uuid) {
        final Optional<String> certificate = config.getOptional("config.certificate");
        final Optional<String> key = config.getOptional("config.key");
        Utils.ifPresentOrElse(key.map(configDir::resolve)
                .filter(Files::exists),
                ky -> CertificateManager.loadKey(ky, k->this.keyPair = k),
                () -> CertificateManager.buildNewKeyPair(configDir, key.get(), k -> this.keyPair = k));

        Utils.ifPresentOrElse(certificate.map(configDir::resolve)
                .filter(Files::exists),
                cert -> CertificateManager.loadCertificates(cert, cl -> this.certs = cl),
                () -> newCertSigningRequest(configDir, uuid, certificate.get()));

        // We don't have a valid certificate chain yet, just exit
        if (certs == null) {
            this.hasCertificate = false;
            return;
        }
        if (certs.size() != 2) {
            this.hasCertificate = false;
            LOGGER.fatal("The certificate chain is invalid. You'll need to get them again");
            return;
        }
        // Check the DN of the first certificate and make sure it matches our UUID. If not, it was issued
        // for someone else and we shouldn't use it here.
        boolean maybeCert = false;
        try {
            final X500Name subjectName = (X500Name) certs.get(0).getSubjectDN();
            if (Objects.equals(subjectName.getCommonName(), uuid)) {
                maybeCert = true;
            } else {
                LaunchEnvironmentHandler.INSTANCE.addProgressMessage("The certificate UUID does not match the player UUID. No server mods will be downloaded");
                LOGGER.warn("Found existing certificate, but it's not for you. Ignoring..");
            }
        } catch (IOException e) {
            LOGGER.catching(e);
        }
        this.hasCertificate = maybeCert;
    }

    private void newCertSigningRequest(final Path serverModsPath, final String uuid, final String outputCertPath) {
        LOGGER.info("Generating new certificate signing request for UUID {} at 'serverrequest.csr'", uuid);
        CertificateManager.generateCSR(()->uuid, ()->this.keyPair, csr->CertificateManager.writeCSR(()->csr, serverModsPath.resolve("serverrequest.csr")));
        LOGGER.warn("NEW certificate signing request and private key generated at 'serverrequest.csr'. " +
                "You will need to get this approved by the server before you can proceed. " +
                "Once you have the certificate from the server, place it in the file {} and restart the client. " +
                "Make sure you configure the remoteServer URL as well.", outputCertPath);
        LaunchEnvironmentHandler.INSTANCE.addProgressMessage("This new game directory needs a new certificate from the server.");
    }

    public KeyPair getKeyPair() {
        return keyPair;
    }

    public X509Certificate[] getCerts() {
        return certs.toArray(new X509Certificate[0]);
    }

    public boolean isValid() {
        return hasCertificate;
    }
}