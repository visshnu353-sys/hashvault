package io.hashvault.chunk;
import java.io.IOException;
import java.io.InputStream;
public final class FixedChunker implements Chunker {
    private final int chunkSize;
    public FixedChunker(int chunkSize) {
        if (chunkSize < 1) throw new IllegalArgumentException("chunkSize must be positive");
        this.chunkSize = chunkSize;
    }
    @Override public String name() { return "fixed:" + chunkSize; }
    @Override public void forEachChunk(InputStream in, ChunkConsumer consumer) throws IOException {
        byte[] buffer = new byte[chunkSize];
        while (true) {
            int total = 0;
            while (total < buffer.length) {
                int n = in.read(buffer, total, buffer.length - total);
                if (n < 0) break;
                if (n == 0) continue;
                total += n;
            }
            if (total == 0) return;
            byte[] chunk = new byte[total];
            System.arraycopy(buffer, 0, chunk, 0, total);
            consumer.accept(chunk);
            if (total < buffer.length) return;
        }
    }
}
