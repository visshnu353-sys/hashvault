package io.hashvault.chunk;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
/** Deterministic rolling-hash chunking with minimum and maximum boundaries. */
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
    @Override public List<byte[]> split(InputStream in) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream(maxSize);
        long hash = 0;
        int value;
        while ((value = in.read()) != -1) {
            current.write(value);
            hash = Long.rotateLeft(hash, 1) ^ gear(value);
            int size = current.size();
            if (size >= minSize && ((hash & mask) == 0 || size >= maxSize)) {
                chunks.add(current.toByteArray()); current.reset(); hash = 0;
            }
        }
        if (current.size() > 0) chunks.add(current.toByteArray());
        return chunks;
    }
    private static long gear(int b) {
        long z = (b & 0xffL) + 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
