package io.hashvault.store;
import io.hashvault.chunk.Chunker;
import io.hashvault.index.Manifest;
import io.hashvault.index.ManifestIndex;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
public final class HashVaultEngine {
    private final Path root;
    private final ObjectStore objects;
    private final ManifestIndex index;
    private final TransactionLog journal;
    private final Map<String, Integer> recoveryReport;

    public HashVaultEngine(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        objects = new ObjectStore(this.root);
        index = new ManifestIndex(this.root);
        journal = new TransactionLog(this.root);
        try (VaultLock ignored = VaultLock.acquire(this.root)) {
            recoveryReport = journal.recover(index::exists);
        }
    }

    public Map<String, Integer> recoveryReport() { return Collections.unmodifiableMap(recoveryReport); }\n\n    public void configureCompression(CompressionCodec codec) { objects.setCompression(codec); }\n    public Map<String, Long> cacheStats() { return objects.cacheStats(); }

    public Manifest put(Path source, Chunker chunker) throws IOException {
        return put(source, chunker, Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    public Manifest put(Path source, Chunker chunker, int parallelism) throws IOException {
        try (VaultLock ignored = VaultLock.acquire(root)) {
            journal.recover(index::exists);
            return putLocked(source, chunker, parallelism);
        }
    }

    private Manifest putLocked(Path source, Chunker chunker, int parallelism) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("Input is not a regular file: " + source);
        if (parallelism < 1 || parallelism > 256) throw new IllegalArgumentException("parallelism must be 1..256");
        Manifest m = new Manifest();
        m.filename = source.getFileName().toString();
        m.originalSize = Files.size(source);
        m.chunker = chunker.name();
        Manifest previous = index.latest(m.filename);
        m.previousVersionId = previous == null ? null : previous.id;
        m.createdAt = System.currentTimeMillis();
        String tx = journal.begin(m.filename);
        boolean finished = false;
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        ArrayDeque<Future<Manifest.ChunkRef>> pending = new ArrayDeque<>();
        int window = Math.max(2, parallelism * 2);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source))) {
            chunker.forEachChunk(in, chunk -> {
                pending.addLast(pool.submit(() -> new Manifest.ChunkRef(objects.put(chunk), chunk.length)));
                if (pending.size() >= window) m.chunks.add(await(pending.removeFirst()));
            });
            while (!pending.isEmpty()) m.chunks.add(await(pending.removeFirst()));
            pool.shutdown();
            if (!pool.awaitTermination(1, TimeUnit.MINUTES)) throw new IOException("Chunk workers did not terminate");
            if (previous != null && previous.originalSize == m.originalSize
                    && previous.chunker.equals(m.chunker)
                    && previous.chunks.size() == m.chunks.size()) {
                boolean unchanged = true;
                for (int i = 0; i < m.chunks.size(); i++) {
                    if (!previous.chunks.get(i).hash.equals(m.chunks.get(i).hash)) { unchanged = false; break; }
                }
                if (unchanged) {
                    journal.abort(tx);
                    finished = true;
                    return previous;
                }
            }
            StringBuilder identity = new StringBuilder(m.filename).append(':').append(m.originalSize).append(':')
                .append(m.chunker).append(':').append(m.previousVersionId);
            for (Manifest.ChunkRef c : m.chunks) identity.append(':').append(c.hash).append(':').append(c.size);
            m.id = ObjectStore.sha256(identity.toString().getBytes(StandardCharsets.UTF_8));
            journal.prepare(tx, m.id);
            index.save(m);
            journal.commit(tx);
            finished = true;
            return m;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for chunk workers", e);
        } catch (IOException | RuntimeException e) {
            for (Future<?> f : pending) f.cancel(true);
            throw e;
        } finally {
            pool.shutdownNow();
            if (!finished) {
                try { journal.abort(tx); } catch (IOException ignored) { /* Recovery resolves an incomplete record. */ }
            }
        }
    }

    private static Manifest.ChunkRef await(Future<Manifest.ChunkRef> future) throws IOException {
        try { return future.get(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Chunk processing interrupted", e); }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Chunk processing failed", cause);
        }
    }

    public void get(String manifestId, Path destination) throws IOException {
        Manifest m = index.load(manifestId);
        Path absolute = destination.toAbsolutePath(), parent = absolute.getParent();
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
    public List<Manifest> history(String filename) throws IOException { return index.history(filename); }
    public long storedBytes() throws IOException { return objects.storedBytes(); }

    public Map<String, Object> deduplicationReport() throws IOException {
        List<Manifest> manifests = index.list();
        Set<String> unique = new HashSet<>();
        long logical = 0;
        for (Manifest m : manifests) {
            logical += m.originalSize;
            for (Manifest.ChunkRef c : m.chunks) unique.add(c.hash);
        }
        long stored = storedBytes();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("manifests", manifests.size());
        report.put("logicalBytesAcrossVersions", logical);
        report.put("uniqueObjects", unique.size());
        report.put("storedObjectBytes", stored);
        report.put("logicalMinusStoredBytes", Math.max(0, logical - stored));
        report.put("logicalToStoredRatio", stored == 0 ? 1.0 : (double) logical / stored);\n        report.put("cache", objects.cacheStats());
        return report;
    }

    public Map<String, Object> garbageCollect(boolean dryRun) throws IOException {
        try (VaultLock ignored = VaultLock.acquire(root)) {
            journal.recover(index::exists);
            Set<String> referenced = new HashSet<>();
            for (Manifest m : index.list()) for (Manifest.ChunkRef c : m.chunks) referenced.add(c.hash);
            Set<String> unreferenced = objects.hashes();
            unreferenced.removeAll(referenced);
            long reclaimable = 0;
            for (String hash : unreferenced) {
                reclaimable += objects.size(hash);
                if (!dryRun) objects.delete(hash);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dryRun", dryRun);
            result.put("unreferencedObjects", unreferenced.size());
            result.put("reclaimableBytes", reclaimable);
            result.put("deletedObjects", dryRun ? 0 : unreferenced.size());
            return result;
        }
    }

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
