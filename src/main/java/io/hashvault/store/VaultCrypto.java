package io.hashvault.store;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

/** Password-based vault key derivation and password verifier metadata. */
public final class VaultCrypto {
    private static final int ITERATIONS = 310_000;
    private static final int KEY_BITS = 256;
    private static final byte[] VERIFIER_CONTEXT = "HashVault password verifier v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path metadata;

    public VaultCrypto(Path vaultRoot) { metadata = vaultRoot.resolve("vault.crypto"); }

    public boolean isEnabled() { return Files.isRegularFile(metadata); }

    public byte[] initialize(char[] password) throws IOException {
        if (password == null || password.length < 12) throw new IllegalArgumentException("Vault password must contain at least 12 characters");
        if (Files.exists(metadata)) throw new IOException("Vault encryption is already configured");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt);
        Properties props = new Properties();
        props.setProperty("format", "1");
        props.setProperty("kdf", "PBKDF2WithHmacSHA256");
        props.setProperty("iterations", Integer.toString(ITERATIONS));
        props.setProperty("salt", Base64.getEncoder().encodeToString(salt));
        props.setProperty("verifier", Base64.getEncoder().encodeToString(hmac(key, VERIFIER_CONTEXT)));
        Path temp = Files.createTempFile(metadata.getParent(), ".crypto-", ".tmp");
        try {
            try (var out = Files.newOutputStream(temp)) { props.store(out, "HashVault encryption metadata; contains no encryption key"); }
            try { Files.move(temp, metadata, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, metadata); }
        } finally { Files.deleteIfExists(temp); }
        return key;
    }

    public byte[] unlock(char[] password) throws IOException {
        if (password == null || password.length == 0) throw new IOException("Encrypted vault requires HASHVAULT_PASSWORD");
        Properties props = new Properties();
        try (var in = Files.newInputStream(metadata)) { props.load(in); }
        if (!"1".equals(props.getProperty("format")) || !"PBKDF2WithHmacSHA256".equals(props.getProperty("kdf")))
            throw new IOException("Unsupported vault encryption metadata format");
        int iterations;
        byte[] salt, expected;
        try {
            iterations = Integer.parseInt(props.getProperty("iterations"));
            salt = Base64.getDecoder().decode(props.getProperty("salt"));
            expected = Base64.getDecoder().decode(props.getProperty("verifier"));
        } catch (RuntimeException e) { throw new IOException("Invalid vault encryption metadata", e); }
        if (iterations < 100_000 || iterations > 2_000_000 || salt.length != 16 || expected.length != 32)
            throw new IOException("Unsafe or invalid vault encryption parameters");
        byte[] key = derive(password, salt, iterations);
        if (!MessageDigest.isEqual(expected, hmac(key, VERIFIER_CONTEXT))) {
            java.util.Arrays.fill(key, (byte) 0);
            throw new IOException("Incorrect vault password");
        }
        return key;
    }

    private static byte[] derive(char[] password, byte[] salt) throws IOException {
        return derive(password, salt, ITERATIONS);
    }
    private static byte[] derive(char[] password, byte[] salt, int iterations) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception e) { throw new IOException("Unable to derive vault encryption key", e); }
        finally { spec.clearPassword(); }
    }
    private static byte[] hmac(byte[] key, byte[] data) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) { throw new IOException("Unable to verify vault key", e); }
    }
}
