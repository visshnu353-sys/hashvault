package io.hashvault.chunk;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
/**
 * Gear-hash content-defined chunking. Memory is bounded by maxSize plus one
 * input buffer; completed chunks are immediately passed to the consumer.
 */
public final class CdcChunker implements Chunker {
    private final int minSize, averageSize, maxSize;
    private final long mask;
    public CdcChunker(int minSize, int averageSize, int maxSize) {
        if (minSize < 1 || averageSize < minSize || maxSize < averageSize)
            throw new IllegalArgumentException("Require 0 < min <= average <= max");
        this.minSize = minSize; this.averageSize = averageSize; this.maxSize = maxSize;
        int bits = Math.max(1, 31 - Integer.numberOfLeadingZeros(averageSize));
        this.mask = (1L << bits) - 1;
    }
    @Override public String name() { return "cdc:" + minSize + ":" + averageSize + ":" + maxSize; }
    @Override public void forEachChunk(InputStream in, ChunkConsumer consumer) throws IOException {
        ByteArrayOutputStream current = new ByteArrayOutputStream(maxSize);
        byte[] buffer = new byte[64 * 1024];
        long hash = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            for (int i = 0; i < count; i++) {
                int value = buffer[i] & 0xff;
                current.write(value);
                hash = Long.rotateLeft(hash, 1) ^ gear(value);
                int size = current.size();
                if (size >= minSize && ((hash & mask) == 0 || size >= maxSize)) {
                    consumer.accept(current.toByteArray());
                    current.reset();
                    hash = 0;
                }
            }
        }
        if (current.size() > 0) consumer.accept(current.toByteArray());
    }
    private static long gear(int b) {
        long z = (b & 0xffL) + 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
