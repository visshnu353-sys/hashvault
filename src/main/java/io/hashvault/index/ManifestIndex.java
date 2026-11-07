package io.hashvault.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javax.crypto.Cipher;
import javax.crypto.AEADBadTagException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;

public final class ManifestIndex {
    private static final int ENCRYPTED_MAGIC = 0x48564d45; // HVME
    private static final byte[] AAD = "HashVault manifest metadata v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record ScanEntry(String fileName, Manifest manifest, String error) {
        public boolean valid() { return error == null; }
    }

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private volatile byte[] encryptionKey;

    public ManifestIndex(Path root) throws IOException { this(root, null); }

    public ManifestIndex(Path root, byte[] encryptionKey) throws IOException {
        dir = root.resolve("manifests");
        Files.createDirectories(dir);
        if (encryptionKey != null) setEncryptionKey(encryptionKey);
    }

    public synchronized void setEncryptionKey(byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("Manifest AES-256 key must be 32 bytes");
        encryptionKey = key.clone();
    }

    public boolean encryptionEnabled() { return encryptionKey != null; }

    /** Migrates plaintext snapshots to authenticated encrypted JSON envelopes. Safe to rerun after interruption. */
    public void encryptExistingManifests() throws IOException {
        if (encryptionKey == null) throw new IllegalStateException("Manifest encryption key is not configured");
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                byte[] bytes = Files.readAllBytes(file);
                if (isEncrypted(bytes)) continue;
                Manifest manifest = mapper.readValue(bytes, Manifest.class);
                List<String> errors = manifest.validationErrors();
                if (!errors.isEmpty()) throw new IOException("Cannot encrypt invalid manifest " + file.getFileName() + ": " + errors);
                writeAtomically(file, encrypt(bytes));
            }
        }
    }

    public void save(Manifest manifest) throws IOException {
        if (manifest.id == null || !manifest.id.equals(manifest.identity()))
            throw new IOException("Refusing to save manifest with invalid identity");
        manifest.seal();
        Path target = dir.resolve(manifest.id + ".json");
        Path temp = Files.createTempFile(dir, ".manifest-", ".tmp");
        try {
            byte[] json = mapper.writeValueAsBytes(manifest);
            byte[] persisted = encryptionKey == null ? json : encrypt(json);
            Files.write(temp, persisted, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (FileAlreadyExistsException exists) {
                Manifest existing = readManifest(target);
                if (!existing.validationErrors().isEmpty()) throw new IOException("Existing immutable manifest is invalid: " + manifest.id);
            } catch (AtomicMoveNotSupportedException e) {
                try { Files.move(temp, target); }
                catch (FileAlreadyExistsException exists) {
                    Manifest existing = readManifest(target);
                    if (!existing.validationErrors().isEmpty()) throw new IOException("Existing immutable manifest is invalid: " + manifest.id);
                }
            }
        } finally { Files.deleteIfExists(temp); }
    }

    public boolean exists(String id) {
        return id != null && id.matches("[a-f0-9]{64}") && Files.isRegularFile(dir.resolve(id + ".json"));
    }

    public Manifest load(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid manifest id");
        Manifest manifest = readManifest(dir.resolve(id + ".json"));
        List<String> errors = manifest.validationErrors();
        if (!errors.isEmpty()) throw new IOException("Invalid manifest " + id + ": " + String.join(", ", errors));
        return manifest;
    }

    private Manifest readManifest(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (isEncrypted(bytes)) bytes = decrypt(bytes);
        return mapper.readValue(bytes, Manifest.class);
    }

    /** Reads every manifest independently so one damaged file does not hide the rest of the scan. */
    public List<ScanEntry> scan() throws IOException {
        List<ScanEntry> result = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString();
                try {
                    Manifest manifest = readManifest(file);
                    List<String> errors = manifest.validationErrors();
                    if (!name.equals(manifest.id + ".json")) errors.add("manifest filename does not match manifest id");
                    result.add(new ScanEntry(name, manifest, errors.isEmpty() ? null : String.join("; ", errors)));
                } catch (Exception e) {
                    result.add(new ScanEntry(name, null, "cannot read manifest: " + e.getMessage()));
                }
            }
        }
        return result;
    }

    public List<Manifest> list() throws IOException {
        List<Manifest> result = new ArrayList<>();
        for (ScanEntry entry : scan()) {
            if (!entry.valid()) throw new IOException("Invalid manifest " + entry.fileName() + ": " + entry.error());
            result.add(entry.manifest());
        }
        return result;
    }

    public Manifest latest(String filename) throws IOException {
        return list().stream().filter(m -> Objects.equals(m.filename, filename))
            .max(Comparator.comparingLong(m -> m.createdAt)).orElse(null);
    }

    public List<Manifest> history(String filename) throws IOException {
        return list().stream().filter(m -> Objects.equals(m.filename, filename))
            .sorted(Comparator.comparingLong(m -> m.createdAt)).toList();
    }

    private static boolean isEncrypted(byte[] bytes) {
        return bytes.length >= 4 && ByteBuffer.wrap(bytes, 0, 4).getInt() == ENCRYPTED_MAGIC;
    }

    private byte[] encrypt(byte[] plaintext) throws IOException {
        byte[] key = encryptionKey;
        if (key == null) throw new IOException("Manifest encryption key is unavailable");
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(ENCRYPTED_MAGIC);
            out.write(nonce);
            out.write(encrypted);
            return bytes.toByteArray();
        } catch (GeneralSecurityException e) { throw new IOException("Unable to encrypt manifest metadata", e); }
    }

    private byte[] decrypt(byte[] envelope) throws IOException {
        byte[] key = encryptionKey;
        if (key == null) throw new IOException("Manifest is encrypted; unlock the vault with HASHVAULT_PASSWORD");
        if (envelope.length < 32) throw new IOException("Encrypted manifest is truncated");
        try {
            ByteBuffer buffer = ByteBuffer.wrap(envelope);
            if (buffer.getInt() != ENCRYPTED_MAGIC) throw new IOException("Invalid encrypted manifest header");
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) { throw new IOException("Manifest authentication failed", e); }
        catch (GeneralSecurityException e) { throw new IOException("Unable to decrypt manifest metadata", e); }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".manifest-encrypted-", ".tmp");
        try {
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
