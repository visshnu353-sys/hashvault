package io.hashvault.store;
import io.hashvault.chunk.Chunker;
import io.hashvault.index.Manifest;
import io.hashvault.index.ManifestIndex;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
public final class HashVaultEngine {
    private final ObjectStore objects;
    private final ManifestIndex index;
    public HashVaultEngine(Path root) throws IOException {
        Files.createDirectories(root); objects = new ObjectStore(root); index = new ManifestIndex(root);
    }
    public Manifest put(Path source, Chunker chunker) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("Input is not a regular file: " + source);
        Manifest m = new Manifest();
        m.filename = source.getFileName().toString();
        m.originalSize = Files.size(source);
        m.chunker = chunker.name();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source))) {
            chunker.forEachChunk(in, chunk -> m.chunks.add(new Manifest.ChunkRef(objects.put(chunk), chunk.length)));
        }
        StringBuilder identity = new StringBuilder(m.filename).append(':').append(m.originalSize).append(':').append(m.chunker);
        for (Manifest.ChunkRef c : m.chunks) identity.append(':').append(c.hash).append(':').append(c.size);
        m.id = ObjectStore.sha256(identity.toString().getBytes(StandardCharsets.UTF_8));
        index.save(m);
        return m;
    }
    public void get(String manifestId, Path destination) throws IOException {
        Manifest m = index.load(manifestId);
        Path absolute = destination.toAbsolutePath();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".hashvault-restore-", ".tmp");
        long written = 0;
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                for (Manifest.ChunkRef c : m.chunks) {
                    byte[] bytes = objects.get(c.hash);
                    if (bytes.length != c.size) throw new IOException("Manifest chunk length mismatch: " + c.hash);
                    out.write(bytes); written += bytes.length;
                }
            }
            if (written != m.originalSize) throw new IOException("Reconstructed size mismatch");
            try { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    public List<Manifest> list() throws IOException { return index.list(); }
    public long storedBytes() throws IOException { return objects.storedBytes(); }
    public void verify() throws IOException {
        for (Manifest m : index.list()) {
            long total = 0;
            for (Manifest.ChunkRef c : m.chunks) {
                byte[] data = objects.get(c.hash);
                if (data.length != c.size) throw new IOException("Chunk size mismatch: " + c.hash);
                total += data.length;
            }
            if (total != m.originalSize) throw new IOException("Manifest verification failed: " + m.id);
        }
    }
}
