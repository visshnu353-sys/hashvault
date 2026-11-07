package io.hashvault.chunk;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
public final class FixedChunker implements Chunker {
    private final int chunkSize;
    public FixedChunker(int chunkSize) {
        if (chunkSize < 1) throw new IllegalArgumentException("chunkSize must be positive");
        this.chunkSize = chunkSize;
    }
    @Override public String name() { return "fixed:" + chunkSize; }
    @Override public List<byte[]> split(InputStream in) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        byte[] buffer = new byte[chunkSize];
        int n;
        while ((n = readChunk(in, buffer)) > 0) {
            byte[] chunk = new byte[n];
            System.arraycopy(buffer, 0, chunk, 0, n);
            chunks.add(chunk);
        }
        return chunks;
    }
    private static int readChunk(InputStream in, byte[] b) throws IOException {
        int total = 0, n;
        while (total < b.length && (n = in.read(b, total, b.length - total)) != -1) total += n;
        return total;
    }
}
