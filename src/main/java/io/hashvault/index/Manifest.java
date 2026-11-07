package io.hashvault.index;
import java.util.ArrayList;
import java.util.List;
public final class Manifest {
    public String id;
    public String filename;
    public long originalSize;
    public String chunker;
    public String previousVersionId;
    public long createdAt;
    public List<ChunkRef> chunks = new ArrayList<>();
    public static final class ChunkRef {
        public String hash;
        public int size;
        public ChunkRef() {}
        public ChunkRef(String hash, int size) { this.hash = hash; this.size = size; }
    }
}
