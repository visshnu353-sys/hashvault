package io.hashvault.chunk;
import java.io.IOException;
@FunctionalInterface
public interface ChunkConsumer {
    void accept(byte[] chunk) throws IOException;
}
