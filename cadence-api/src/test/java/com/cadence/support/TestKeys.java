package com.cadence.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Generates a throwaway RS256 key pair as PEM files for the test JVM (no private key is ever committed). */
public final class TestKeys {

    private static Path privateKey;
    private static Path publicKey;

    private TestKeys() {
    }

    public static synchronized Path privateKeyFile() {
        ensure();
        return privateKey;
    }

    public static synchronized Path publicKeyFile() {
        ensure();
        return publicKey;
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void ensure() {
        if (privateKey != null) {
            return;
        }
        KeyPair pair = generate();
        try {
            Path dir = Files.createTempDirectory("cadence-test-keys");
            privateKey = Files.writeString(dir.resolve("jwt-private.pem"), pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            publicKey = Files.writeString(dir.resolve("jwt-public.pem"), pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            dir.toFile().deleteOnExit();
            privateKey.toFile().deleteOnExit();
            publicKey.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
