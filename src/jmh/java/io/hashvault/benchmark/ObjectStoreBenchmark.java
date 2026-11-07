package io.hashvault.benchmark;

import io.hashvault.store.CompressionCodec;
import io.hashvault.store.ObjectStore;
import org.openjdk.jmh.annotations.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ObjectStoreBenchmark {
    @Param({"65536", "262144"}) public int payloadSize;
    @Param({"AUTO", "NONE", "GZIP"}) public String codec;
    private ObjectStore store;
    private byte[] payload;
    private String readHash;
    private AtomicInteger sequence;
    private Path directory;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        directory = Files.createTempDirectory("hashvault-jmh-");
        store = new ObjectStore(directory, 0);
        store.setCompression(CompressionCodec.valueOf(codec));
        payload = new byte[payloadSize];
        // Deterministic, moderately compressible payload.
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) ((i * 31 + i / 17) % 251);
        readHash = store.put(payload);
        sequence = new AtomicInteger();
    }

    @Benchmark public byte[] readObjectFromDisk() throws Exception {
        return store.get(readHash);
    }

    @Benchmark public String writeUniqueObject() throws Exception {
        int n = sequence.incrementAndGet();
        payload[0] = (byte) n;
        payload[1] = (byte) (n >>> 8);
        return store.put(payload);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
