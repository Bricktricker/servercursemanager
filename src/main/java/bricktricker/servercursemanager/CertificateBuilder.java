/*
MIT License
Copyright (c) 2022
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/
package bricktricker.servercursemanager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import javax.security.auth.x500.X500Principal;

/**
 * A class that allows building a X509 certificate as defined in <a href="https://datatracker.ietf.org/doc/html/rfc5280">RFC5280</a>.
 * This Builder only supports signing the certificate with a RSA key.
 * Usage:
 * <pre>
 * X509Certificate cert = new CertificateBuilder()
        .version(3)
        .serialNumber(serialNumber)
        .issuer("CN=TheCa, O=CaOrg")
        .validity(beginValid, endValid)
        .subject("CN=Consumer, O=CertBuilder")
        .publicKey(subjectPublicKey)
        .basicConstrains(true, 0)
        .build(caPrivateRsaKey, CertificateBuilder.SIG_Sha256WithRSAEncryption);
 * </pre>
 */
public class CertificateBuilder {

    public static final SignatureAlg SIG_Sha256WithRSAEncryption = new SignatureAlg("SHA256WithRSA", new byte[] {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b});

    // DER encoded version, or null if version is 1
    private byte[] versionBytes;
    // DER encoded integer of the serial number
    private byte[] serialNumber;
    private X500Principal issuer;
    // DER encoded time when the certificate validity starts
    private byte[] validFromBytes;
    // DER encoded time when the certificate validity ends
    private byte[] validToBytes;
    private X500Principal subject;
    private PublicKey publicKey;
    
    // Basic constrains extension data
    private Optional<Boolean> basicConstrainsCa = Optional.empty();
    private Optional<Integer> basicConstrainsLen = Optional.empty();
    
    private String comment;

    /**
     * Sets the certificate version. If not set, the version defaults to 1.
     * Version >= 2 is required if you need certificate extensions.
     * 
     * @param v The version, valid values are 1, 2 and 3
     * @return This CertificateBuilder
     */
    public CertificateBuilder version(int v) {
        if(v < 1 || v > 3) {
            throw new IllegalArgumentException("Invalid version, valid values are 1, 2 and 3");
        }
        
        if(v == 1) {
            // 1 is default, don't write it
            versionBytes = null;
        }else {
            this.versionBytes = new byte[] {
                    (byte) 0xa0, // constructed context-specific type [0]
                    3, // length of 0x3 (3) bytes 
                    2, // universal type integer
                    1, // integer length of 0x1 bytes
                    (byte) (v-1) // integer version value 
            };  
        }
        return this;
    }
    
    /**
     * Sets the serial number of this Certificate.
     * The serial number should be unique for this certificate and between 1 and 127 bytes long.
     * 
     * @param serial The bytes of the serial numer
     * @return This CertificateBuilder
     */
    public CertificateBuilder serialNumber(byte[] serial) {
        if(serial.length == 0 || serial.length > 127) {
            throw new IllegalArgumentException();
        }
            
        this.serialNumber = new byte[2 + serial.length];
        this.serialNumber[0] = 2; // universal type integer
        this.serialNumber[1] = (byte) serial.length;
        System.arraycopy(serial, 0, this.serialNumber, 2, serial.length);
        return this;
    }
    
    /**
     * Sets the serial number of this Certificate.
     * The serial number should be unique for this certificate and not larger than 2^127.
     * 
     * @param number The serial number as a BigInteger
     * @return This CertificateBuilder
     */
    public CertificateBuilder serialNumber(BigInteger number) {
        return serialNumber(number.toByteArray());
    }
    
    /**
     * Sets the issuer / the CA of this certificate. Accepts the same distinguished names as {@link X500Principal}.
     * Example string: "CN=Duke, OU=JavaSoft, O=Sun Microsystems, C=US".
     * 
     * @param distinguishedNames The issuer as a distinguished names string
     * @return This CertificateBuilder
     */
    public CertificateBuilder issuer(String distinguishedNames) {
        return issuer(new X500Principal(distinguishedNames));
    }
    
    /**
     * Sets the issuer / the CA of this certificate to the passed in {@link X500Principal}
     * 
     * @param issuer The identity that issued this certificate 
     * @return This CertificateBuilder
     */
    public CertificateBuilder issuer(X500Principal issuer) {
        this.issuer = Objects.requireNonNull(issuer);
        return this;
    }
    
    /**
     * Sets the validity range of this certificate. 
     * 
     * @param from A UTC date-time where the certificate validity starts
     * @param to A UTC date-time where the certificate validity ends
     * @return This CertificateBuilder
     */
    public CertificateBuilder validity(LocalDateTime from, LocalDateTime to) {
        if(!Objects.requireNonNull(to).isAfter(Objects.requireNonNull(from))) {
            throw new IllegalArgumentException("'to' must be a date after 'from'");
        }
        this.validFromBytes = encodeDate(from);
        this.validToBytes = encodeDate(to);
        return this;
    }
    
    /**
     * Sets the subject / user of this certificate. Accepts the same distinguished names as {@link X500Principal}.
     * Example string: "CN=Duke, OU=JavaSoft, O=Sun Microsystems, C=US".
     * 
     * @param distinguishedNames The suject as a distinguished names string
     * @return This CertificateBuilder
     */
    public CertificateBuilder subject(String distinguishedNames) {
        return subject(new X500Principal(distinguishedNames));
    }
    
    /**
     * Sets the subject / user of this certificate to the passed in {@link X500Principal}
     * 
     * @param subject The identity that uses this certificate 
     * @return This CertificateBuilder
     */
    public CertificateBuilder subject(X500Principal subject) {
        this.subject = Objects.requireNonNull(subject);
        return this;
    }
    
    /**
     * Sets the {@link PublicKey} of the subject / user.
     * 
     * @param key The public key of the user
     * @return This CertificateBuilder
     */
    public CertificateBuilder publicKey(PublicKey key) {
        this.publicKey = Objects.requireNonNull(key);
        return this;
    }
    
    /**
     * Adds a Netscape certificate comment extension with the specified comment to the certificate
     * 
     * @param comment The comment. Can only contain ASCII characters 
     * @return This CertificateBuilder
     */
    public CertificateBuilder comment(String comment) {
        if(comment != null && comment.isEmpty()) {
            throw new IllegalArgumentException("Comment can't be empty");
        }
        this.comment = comment;
        return this;
    }
    
    /**
     * Sets the <a href="https://datatracker.ietf.org/doc/html/rfc5280#section-4.2.1.9">Basic Constraints</a> extension of this certificate.
     * This extension specifies, whether the subject of the certificate is a CA and the maximum depth of valid certification paths that include this certificate.
     * 
     * @param ca True if the subject is a CA
     * @param len if ca is true, the maximum number of certificates in a certificate chain
     * @return This CertificateBuilder
     */
    public CertificateBuilder basicConstrains(boolean ca, int len) {
        if(this.versionBytes == null) {
            // No version bytes -> version must be v1. Extensions are only supported in version >= 2
            throw new IllegalStateException("Certificate version must be greater than 1");
        }
        if(ca && (len < 0 || len > 255)) {
            throw new IllegalArgumentException("specified length must be between 0 and 255 (inclusive)");
        }
        
        this.basicConstrainsCa = Optional.of(ca);
        this.basicConstrainsLen = Optional.of(len);

        return this;
    }
    
    /**
     * Generate and sign the build certificate.
     * 
     * @param cakey The privates key of the issuer / ca that should be used to sign the certificate
     * @param signatureAlgorithm The signing algorithm that should be used
     * @return The build {@link X509Certificate}
     */
    public X509Certificate build(RSAPrivateKey cakey, SignatureAlg signatureAlgorithm) {
        return this.finalize(signatureAlgorithm, tbsCertBytes -> {
            try {
                Signature sig = Signature.getInstance(signatureAlgorithm.name);
                sig.initSign(cakey);
                sig.update(tbsCertBytes);
                return sig.sign();
            } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Generate the certificate, use the given signature bytes as the CA signature. NOTE: This will result in an invalid signature
     * 
     * @param publicKeySignature The signature bytes used for the CA signature
     * @param signatureAlgorithm The signing algorithm that should be used
     * @return The build {@link X509Certificate}
     */
    public X509Certificate forceSignature(byte[] publicKeySignature, SignatureAlg signatureAlgorithm) {
        return this.finalize(signatureAlgorithm, tbsCertBytes -> {
           return publicKeySignature;
        });
    }
    
    /**
     * DER encodes the TBSCertificate part of the X509 certificate.
     * 
     * @param signatureAlgorithm The signing algorithm that should be used 
     * @return The DER encoded TBSCertificate bytes
     * @throws IOException if the {@link ByteArrayOutputStream} throws it
     */
    private byte[] writeTBSCert(SignatureAlg signatureAlgorithm) throws IOException {
        /*
        TBSCertificate  ::=  SEQUENCE  {
            version         [0]  EXPLICIT Version DEFAULT v1,
            serialNumber         CertificateSerialNumber,
            signature            AlgorithmIdentifier,
            issuer               Name,
            validity             Validity,
            subject              Name,
            subjectPublicKeyInfo SubjectPublicKeyInfo,
            issuerUniqueID  [1]  IMPLICIT UniqueIdentifier OPTIONAL,
                                 -- If present, version MUST be v2 or v3 (NOT supported by this implementation)
            subjectUniqueID [2]  IMPLICIT UniqueIdentifier OPTIONAL,
                             -- If present, version MUST be v2 or v3 (NOT supported by this implementation)
            extensions      [3]  EXPLICIT Extensions OPTIONAL
                             -- If present, version MUST be v3
        }
        */
        
        var tbsCert = new ByteArrayOutputStream();
        
        byte[] issuerBytes = this.issuer.getEncoded();
        byte[] subjectBytes = this.subject.getEncoded();
        
        // write the version bytes if the version is != 1
        if(this.versionBytes != null) {
            tbsCert.write(this.versionBytes);
        }
        // followed by the serial number
        tbsCert.write(this.serialNumber);
        
        // Signature algo
        writeSignatureAlgo(signatureAlgorithm, tbsCert);
        
        tbsCert.write(issuerBytes);
        
        // write the validity range
        tbsCert.write(0x30); // constructed universal type sequence
        writeLength(this.validFromBytes.length + this.validToBytes.length, tbsCert);
        tbsCert.write(this.validFromBytes);
        tbsCert.write(this.validToBytes);
        
        // write the subject and his public key
        tbsCert.write(subjectBytes);
        tbsCert.write(this.publicKey.getEncoded());
        
        // Write Extensions (if present)
        if(this.comment != null || this.basicConstrainsCa.isPresent()) {
            tbsCert.write(this.getExtensions());
        }
        
        var contentBytes = tbsCert.toByteArray();
        
        // write the outermost sequence header of this TBSCertificate
        tbsCert = new ByteArrayOutputStream();
        tbsCert.write(0x30); // constructed universal type sequence
        writeLength(contentBytes.length, tbsCert);
        tbsCert.write(contentBytes);
        
        return tbsCert.toByteArray();
    }
    
    private X509Certificate finalize(SignatureAlg signatureAlgorithm, Function<byte[], byte[]> signFunc) {
          /* Certificate structure
            Certificate  ::=  SEQUENCE  {
            tbsCertificate       TBSCertificate,
            signatureAlgorithm   AlgorithmIdentifier,
            signatureValue       BIT STRING  }
         */
        
        Objects.requireNonNull(this.serialNumber, "serial number not set");
        Objects.requireNonNull(this.issuer, "issuer not set");
        Objects.requireNonNull(this.validFromBytes, "validity not set");
        Objects.requireNonNull(this.validToBytes, "validity not set");
        Objects.requireNonNull(this.subject, "subject not set");
        Objects.requireNonNull(this.publicKey, "subject public key not set");
        
        byte[] certificateBytes;
        try {
            var certStream = new ByteArrayOutputStream();
            
            // The tbsCertificate bytes that will be signed by the CA
            byte[] tbsCertBytes = writeTBSCert(signatureAlgorithm);
            
            certStream.write(tbsCertBytes);
            
            // After the tbsCertificate the signatureAlgorithm follows
            writeSignatureAlgo(signatureAlgorithm, certStream);
            
            // sign the tbsCertificate bytes
            byte[] signatureBytes = signFunc.apply(tbsCertBytes);
            
            // write the signature bytes
            certStream.write(0x3); // universal type bitstring
            writeLength(signatureBytes.length + 1, certStream);
            certStream.write(0); // right-padded by 0x0 (0) bits
            certStream.write(signatureBytes);
            
            var contentBytes = certStream.toByteArray();
            
            // write the outermost sequence header
            certStream = new ByteArrayOutputStream();
            certStream.write(0x30); // constructed universal type sequence
            writeLength(contentBytes.length, certStream);
            certStream.write(contentBytes);
            
            certificateBytes = certStream.toByteArray();
        }catch(IOException e) {
            // Should not happen with ByteArrayOutputStream
            throw new RuntimeException(e);
        }
        
        // Convert the DER encoded certificate into a X509Certificate
        try {           
            CertificateFactory certFac = CertificateFactory.getInstance("X509");
            return (X509Certificate) certFac.generateCertificate(new ByteArrayInputStream(certificateBytes));
        }catch(CertificateException e) {
            throw new RuntimeException(e);
        }
    }
    
    private byte[] getExtensions() {
        try {
        
            byte[] basicConstrainsExtension = new byte[0];
            if(this.basicConstrainsCa.isPresent()) {
                // write basic constrains extension
                boolean ca = this.basicConstrainsCa.get();
                int len = this.basicConstrainsLen.get();
                var baos = new ByteArrayOutputStream();
                
                baos.write(6); // universal type object id (OID)
                byte[] oid = new byte[] {85, 29, 19}; // basicConstraints OBJECTIDENTIFIER
                baos.write(oid.length); // OID length
                baos.write(oid);
    
                baos.write(1); // boolean type
                baos.write(1); // boolean length 0x1 (1) bytes 
                baos.write(0xff); // critical
                
                // write extension content
                baos.write(4); // OCTET STRING
                int octetLen = 3 + (ca ? 3 : 0);
                writeLength(octetLen + 2, baos);
    
                // Write DER value of extension data
                baos.write(0x30); // constructed universal type sequence
                writeLength(octetLen, baos);
                
                // write ca value
                baos.write(1); // boolean type
                baos.write(1); // boolean length 0x1 (1) bytes 
                baos.write(ca ? 0xff : 0); // ca value
                
                if(ca) {
                    // TODO: support a path length of > 255
                    baos.write(0x2); // universal type integer
                    baos.write(1); // integer length of 1 byte
                    baos.write(len);
                }
                
                // holds the bytes of the Basic Constraints extension in DER encoded form
                byte[] innerContent = baos.toByteArray();
                
                // now write the sequence around it
                baos = new ByteArrayOutputStream();
                baos.write(0x30); // constructed universal type sequence
                writeLength(innerContent.length, baos);
                baos.write(innerContent);
                
                basicConstrainsExtension = baos.toByteArray();
            }
            
            byte[] commentExtension = new byte[0];
            if(this.comment != null) {
                var baos = new ByteArrayOutputStream();
                byte[] commentBytes = this.comment.getBytes(StandardCharsets.US_ASCII);
                
                baos.write(6); // universal type object id (OID)
                byte[] oid = new byte[] {0x60, (byte)0x86, 0x48, 0x01, (byte)0x86, (byte)0xF8, 0x42, 0x01, 0x0D}; // nsComment OBJECTIDENTIFIER (2.16.840.1.113730.1.13)
                baos.write(oid.length); // OID length
                baos.write(oid);
                
                // write extension content
                baos.write(4); // OCTET STRING
                int octetLen = commentBytes.length;
                writeLength(octetLen + 2, baos);
                
                baos.write(0x16); // IA5String
                writeLength(commentBytes.length, baos);
                baos.write(commentBytes);
                
                // holds the bytes of the Basic Constraints extension in DER encoded form
                byte[] innerContent = baos.toByteArray();
                
                // now write the sequence around it
                baos = new ByteArrayOutputStream();
                baos.write(0x30); // constructed universal type sequence
                writeLength(innerContent.length, baos);
                baos.write(innerContent);
                
                commentExtension = baos.toByteArray();
            }
            
            byte[] mergedExtensions = new byte[basicConstrainsExtension.length + commentExtension.length];
            System.arraycopy(basicConstrainsExtension, 0, mergedExtensions, 0, basicConstrainsExtension.length);
            System.arraycopy(commentExtension, 0, mergedExtensions, basicConstrainsExtension.length, commentExtension.length);
            
            // write another sequence around it
            var baos = new ByteArrayOutputStream();
            baos.write(0x30); // constructed universal type sequence
            writeLength(mergedExtensions.length, baos);
            baos.write(mergedExtensions);
            
            var innerContent = baos.toByteArray();
            
            // write the outer most extension identifier
            baos = new ByteArrayOutputStream();
            baos.write(0xa3); //  constructed context-specific type [3] 
            writeLength(innerContent.length, baos);
            baos.write(innerContent);
            
            return baos.toByteArray();
        
        }catch (IOException e) {
            // Should not happen with ByteArrayOutputStream
            throw new RuntimeException(e);
        }
    }
    
    /**
     * DER encodes the given UTC date-time.
     * 
     * @param date A date-time that should be encoded
     * @return The DER encoding of the giben date-time
     */
    private static byte[] encodeDate(LocalDateTime date) {
        
        String pattern;
        byte asnType;
        if(date.getYear() <= 2049) {
            // UTCTime
            pattern = "yyMMddHHmmss";
            asnType = 0x17; // universal type utctime
        }else {
            // Generalized Time
            pattern = "yyyyMMddHHmmss";
            asnType = 0x18; // universal type generalized time
        }
        
        String utcFormated = date.format(DateTimeFormatter.ofPattern(pattern)) + 'Z';
        byte[] utcBytes = utcFormated.getBytes(StandardCharsets.US_ASCII);
        
        byte[] retBytes = new byte[2 + utcBytes.length];
        retBytes[0] = asnType;
        retBytes[1] = (byte) utcBytes.length;
        System.arraycopy(utcBytes, 0, retBytes, 2, utcBytes.length);
        
        return retBytes;
    }
    
    /**
     * Write the length bytes of a ASN.1 sequence in DER encoding to the OutputStream. 
     * 
     * @param length The number of bytes of the following sequence
     * @param oos The OutputStream where the length information should get written to
     * @throws IOException if the {@link OutputStream} throws it
     */
    private static void writeLength(int length, OutputStream oos) throws IOException {
        if(length > 127) {
            // long form, we need to use multiple bytes for the length
            
            // compute the number of bytes needed to encode the length
            int lengthBackup = length;
            int numLengthBytes = 0;
            while(lengthBackup != 0) {
                numLengthBytes++;
                lengthBackup >>= 8;
            }
            oos.write(numLengthBytes | 0x80); // write the number of needed bytes + set the MSB of this byte
            
            // write the length bytes
            for(int i = numLengthBytes-1; i >= 0; i--) {
                oos.write((length >> (i*8)) & 0xFF);
            }
        }else {
            // short from, we can fit the length into one byte
            oos.write(length);
        }
    }
    
    /**
     * Write the given SignatureAlg to the OutputStream in the DER encoding.
     * 
     * @param algo The siganture algorithm that should be writen to the OutputStream
     * @param oos The OutputStream where the signature algorithm should get written to
     * @throws IOException if the {@link OutputStream} throws it
     */
    private static void writeSignatureAlgo(SignatureAlg algo, OutputStream oos) throws IOException {
        oos.write(0x30); // constructed universal type sequence
        oos.write(4 + algo.oid.length); // sequence length
        oos.write(0x06); // universal type object ID (OID) 
        oos.write(algo.oid.length);
        oos.write(algo.oid);
        
        // write parameters. Not supported in this implementation, so we write 'null'
        oos.write(0x05); // universal type null (params)
        oos.write(0); // null length 0x0 (0) bytes 
    }
    
    /**
     * Holds a signature algorithm. Does not support signature algorithms with extra parameters.
     * @param name The signature name, used to get the Signature object from {@link Signature#getInstance(String)}
     * @param oid The DER encoded object identifier of this signature algorithm
     */
    public record SignatureAlg(String name, byte[] oid) {}

}
