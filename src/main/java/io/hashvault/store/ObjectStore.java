package io.hashvault.store;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
public final class ObjectStore {
    private static final int MAGIC = 0x48564c54;
    private static final byte RAW = 0, GZIP = 1;
    private final Path root;
    public ObjectStore(Path root) throws IOException { this.root = root.resolve("objects"); Files.createDirectories(this.root); }
    public String put(byte[] data) throws IOException {
        String hash = sha256(data); Path target = pathFor(hash);
        if (Files.exists(target)) return hash;
        byte[] compressed = gzip(data); boolean useGzip = compressed.length < data.length;
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".object-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(MAGIC); out.writeByte(useGzip ? GZIP : RAW); out.writeInt(data.length);
                out.write(useGzip ? compressed : data);
            }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (FileAlreadyExistsException exists) { /* identical object already published */ }
            catch (AtomicMoveNotSupportedException unsupported) {
                try { Files.move(temp, target); } catch (FileAlreadyExistsException exists) { /* identical object already published */ }
            }
        } finally { Files.deleteIfExists(temp); }
        return hash;
    }
    public byte[] get(String hash) throws IOException {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid SHA-256 object id");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(pathFor(hash))))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid object header: " + hash);
            byte codec = in.readByte(); int length = in.readInt();
            if (length < 0) throw new IOException("Invalid object length");
            InputStream payload;
            if (codec == GZIP) payload = new GZIPInputStream(in);
            else if (codec == RAW) payload = in;
            else throw new IOException("Unknown object codec: " + codec);
            byte[] bytes = payload.readAllBytes();
            if (bytes.length != length || !sha256(bytes).equals(hash)) throw new IOException("Object integrity check failed: " + hash);
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
    public void delete(String hash) throws IOException { Files.deleteIfExists(pathFor(hash)); }
    public long storedBytes() throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { throw new UncheckedIOException(e); }
            }).sum();
        }
    }
    private Path pathFor(String hash) { return root.resolve(hash.substring(0, 2)).resolve(hash + ".blob"); }
    public static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(data); }
        return bytes.toByteArray();
    }
}
