package com.cadence.common.security;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Reads RSA keys from PEM files. */
final class PemKeys {

    private PemKeys() {
    }

    static RSAPrivateKey privateKey(Resource resource) {
        byte[] der = der(resource, "PRIVATE KEY");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Not a PKCS#8 RSA private key: " + resource, e);
        }
    }

    static RSAPublicKey publicKey(Resource resource) {
        byte[] der = der(resource, "PUBLIC KEY");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Not an X.509 RSA public key: " + resource, e);
        }
    }

    private static byte[] der(Resource resource, String type) {
        if (resource == null || !resource.exists()) {
            throw new IllegalStateException("JWT key file not found: " + resource
                    + ". Run scripts/generate-dev-keys.sh and set CADENCE_JWT_PRIVATE_KEY_LOCATION / CADENCE_JWT_PUBLIC_KEY_LOCATION.");
        }
        try {
            String pem = resource.getContentAsString(StandardCharsets.US_ASCII);
            String begin = "-----BEGIN " + type + "-----";
            String end = "-----END " + type + "-----";
            int from = pem.indexOf(begin);
            int to = pem.indexOf(end);
            if (from < 0 || to < 0) {
                throw new IllegalStateException("Expected a '" + begin + "' PEM block in " + resource);
            }
            return Base64.getMimeDecoder().decode(pem.substring(from + begin.length(), to));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }
}
