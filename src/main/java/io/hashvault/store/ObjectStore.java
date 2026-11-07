package io.hashvault.store;

import javax.crypto.Cipher;
import javax.crypto.AEADBadTagException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class ObjectStore {
    private static final int MAGIC = 0x48564c54;
    private static final byte RAW = 0, GZIP = 1, AES_RAW = 2, AES_GZIP = 3;
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path root;
    private final ObjectCache cache;
    private volatile CompressionCodec compression = CompressionCodec.AUTO;
    private volatile byte[] encryptionKey;

    public ObjectStore(Path root) throws IOException {
        this(root, Long.getLong("hashvault.cacheBytes", 32L * 1024 * 1024));
    }
    public ObjectStore(Path root, long cacheBytes) throws IOException {
        this.root = root.resolve("objects");
        this.cache = new ObjectCache(cacheBytes);
        Files.createDirectories(this.root);
    }
    public void setCompression(CompressionCodec codec) { this.compression = Objects.requireNonNull(codec); }
    public Map<String, Long> cacheStats() { return cache.stats(); }
    public boolean encryptionEnabled() { return encryptionKey != null; }

    public synchronized void setEncryptionKey(byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        this.encryptionKey = key.clone();
    }

    /** Rewrites every existing object using AES-256-GCM; mixed old/new objects remain readable during migration. */
    public void encryptExistingObjects() throws IOException {
        if (encryptionKey == null) throw new IllegalStateException("Encryption key is not configured");
        for (String hash : hashes()) {
            byte[] plaintext = get(hash);
            writeObject(hash, plaintext, true);
        }
    }

    public String put(byte[] data) throws IOException {
        String hash = sha256(data);
        Path target = pathFor(hash);
        if (Files.exists(target)) return hash;
        writeObject(hash, data, false);
        cache.put(hash, data);
        return hash;
    }

    private void writeObject(String hash, byte[] data, boolean replace) throws IOException {
        byte[] compressed = compression == CompressionCodec.NONE ? data : gzip(data);
        boolean useGzip = compression == CompressionCodec.GZIP
                || (compression == CompressionCodec.AUTO && compressed.length < data.length);
        if (compression == CompressionCodec.AUTO && compressed.length >= data.length) useGzip = false;
        byte codec = useGzip ? GZIP : RAW;
        byte[] payload = useGzip ? compressed : data;
        byte[] key = encryptionKey;
        if (key != null) {
            codec = (byte) (useGzip ? AES_GZIP : AES_RAW);
            payload = encrypt(payload, key, hash, data.length, codec);
        }
        Path target = pathFor(hash);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".object-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(MAGIC);
                out.writeByte(codec);
                out.writeInt(data.length);
                out.write(payload);
            }
            if (replace) {
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            } else {
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (FileAlreadyExistsException exists) { /* immutable object already published */ }
                catch (AtomicMoveNotSupportedException unsupported) {
                    try { Files.move(temp, target); } catch (FileAlreadyExistsException exists) { /* already published */ }
                }
            }
        } finally { Files.deleteIfExists(temp); }
    }

    public byte[] get(String hash) throws IOException {
        validateHash(hash);
        byte[] cached = cache.get(hash);
        if (cached != null) return cached;
        return readAndValidate(hash);
    }

    /** Bypasses the cache so an audit always checks bytes currently persisted on disk. */
    public byte[] verifyAndGet(String hash) throws IOException {
        validateHash(hash);
        cache.invalidate(hash);
        return readAndValidate(hash);
    }

    private byte[] readAndValidate(String hash) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(pathFor(hash))))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid object header: " + hash);
            byte codec = in.readByte();
            int length = in.readInt();
            if (length < 0) throw new IOException("Invalid object length");
            byte[] payload = in.readAllBytes();
            boolean encrypted = codec == AES_RAW || codec == AES_GZIP;
            if (encrypted) {
                byte[] key = encryptionKey;
                if (key == null) throw new IOException("Object is encrypted; unlock the vault with HASHVAULT_PASSWORD");
                payload = decrypt(payload, key, hash, length, codec);
                codec = codec == AES_GZIP ? GZIP : RAW;
            }
            byte[] bytes;
            if (codec == GZIP) {
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) { bytes = gzip.readAllBytes(); }
            } else if (codec == RAW) bytes = payload;
            else throw new IOException("Unknown object codec: " + codec);
            if (bytes.length != length || !sha256(bytes).equals(hash)) throw new IOException("Object integrity check failed: " + hash);
            cache.put(hash, bytes);
            return bytes;
        }
    }

    public Set<String> hashes() throws IOException {
        Set<String> result = new HashSet<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (name.matches("[a-f0-9]{64}\\.blob")) result.add(name.substring(0, 64));
            }
        }
        return result;
    }
    public long size(String hash) throws IOException { return Files.size(pathFor(hash)); }
    public void delete(String hash) throws IOException {
        Files.deleteIfExists(pathFor(hash));
        cache.invalidate(hash);
    }
    public long storedBytes() throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { throw new UncheckedIOException(e); }
            }).sum();
        }
    }
    private Path pathFor(String hash) { return root.resolve(hash.substring(0, 2)).resolve(hash + ".blob"); }
    private static void validateHash(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid SHA-256 object id");
    }
    public static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(data); }
        return bytes.toByteArray();
    }
    private static byte[] encrypt(byte[] plaintext, byte[] key, String hash, int length, byte codec) throws IOException {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(hash, length, codec));
            byte[] encrypted = cipher.doFinal(plaintext);
            ByteArrayOutputStream result = new ByteArrayOutputStream(nonce.length + encrypted.length);
            result.write(nonce);
            result.write(encrypted);
            return result.toByteArray();
        } catch (GeneralSecurityException e) { throw new IOException("Unable to encrypt object", e); }
    }
    private static byte[] decrypt(byte[] ciphertext, byte[] key, String hash, int length, byte codec) throws IOException {
        if (ciphertext.length < NONCE_BYTES + 16) throw new IOException("Encrypted object payload is truncated");
        try {
            byte[] nonce = Arrays.copyOfRange(ciphertext, 0, NONCE_BYTES);
            byte[] encrypted = Arrays.copyOfRange(ciphertext, NONCE_BYTES, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(hash, length, codec));
            return cipher.doFinal(encrypted);
        } catch (AEADBadTagException e) { throw new IOException("Encrypted object authentication failed: " + hash, e); }
        catch (GeneralSecurityException e) { throw new IOException("Unable to decrypt object", e); }
    }
    private static byte[] aad(String hash, int length, byte codec) {
        return (hash + ":" + length + ":" + codec).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
