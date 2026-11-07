package io.hashvault.chunk;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
public interface Chunker {
    void forEachChunk(InputStream input, ChunkConsumer consumer) throws IOException;
    default List<byte[]> split(InputStream input) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        forEachChunk(input, chunks::add);
        return chunks;
    }
    String name();
}
