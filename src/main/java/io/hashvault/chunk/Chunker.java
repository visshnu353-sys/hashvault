package io.hashvault.chunk;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
public interface Chunker {
    List<byte[]> split(InputStream input) throws IOException;
    String name();
}
