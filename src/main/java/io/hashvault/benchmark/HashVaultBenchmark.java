package io.hashvault.benchmark;
import io.hashvault.chunk.CdcChunker;
import io.hashvault.chunk.Chunker;
import io.hashvault.chunk.FixedChunker;
import io.hashvault.store.HashVaultEngine;
import java.nio.file.*;
import java.util.Random;
/** Repeatable smoke benchmark; use a fresh vault directory for each run. */
public final class HashVaultBenchmark {
    public static void main(String[] args) throws Exception {
        long sizeMb = args.length > 0 ? Long.parseLong(args[0]) : 128;
        Path work = args.length > 1 ? Path.of(args[1]) : Path.of("benchmark-work");
        Files.createDirectories(work);
        Path input = work.resolve("benchmark-data.bin");
        long bytes = sizeMb * 1024L * 1024L;
        if (!Files.exists(input) || Files.size(input) != bytes) {
            Random random = new Random(42);
            try (var out = Files.newOutputStream(input)) {
                byte[] block = new byte[1024 * 1024];
                long remaining = bytes;
                while (remaining > 0) {
                    random.nextBytes(block);
                    int n = (int)Math.min(block.length, remaining);
                    out.write(block, 0, n); remaining -= n;
                }
            }
        }
        run("fixed", new FixedChunker(1024 * 1024), input, work.resolve("vault-fixed"));
        run("cdc", new CdcChunker(64 * 1024, 256 * 1024, 1024 * 1024), input, work.resolve("vault-cdc"));
    }
    private static void run(String label, Chunker chunker, Path input, Path vault) throws Exception {
        HashVaultEngine engine = new HashVaultEngine(vault);
        long start = System.nanoTime();
        var manifest = engine.put(input, chunker);
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        double mib = Files.size(input) / 1048576.0;
        System.out.printf("%s: %.2f MiB, %d chunks, %.2f s, %.2f MiB/s, %d stored bytes%n",
            label, mib, manifest.chunks.size(), seconds, mib / seconds, engine.storedBytes());
    }
}
