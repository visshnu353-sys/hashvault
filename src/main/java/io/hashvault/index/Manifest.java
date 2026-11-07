package io.hashvault.index;

import io.hashvault.store.ObjectStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Immutable snapshot metadata. Version 0 represents manifests written before checksums. */
public final class Manifest {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public String id;
    public String filename;
    public long originalSize;
    public String chunker;
    public String previousVersionId;
    public long createdAt;
    public int formatVersion;
    public String checksum;
    public List<ChunkRef> chunks = new ArrayList<>();

    public static final class ChunkRef {
        public String hash;
        public int size;
        public ChunkRef() {}
        public ChunkRef(String hash, int size) { this.hash = hash; this.size = size; }
    }

    /** Original identity formula is intentionally retained for old vault compatibility. */
    public String identity() {
        StringBuilder value = new StringBuilder(filename).append(':').append(originalSize).append(':')
            .append(chunker).append(':').append(previousVersionId);
        for (ChunkRef chunk : chunks) value.append(':').append(chunk.hash).append(':').append(chunk.size);
        return ObjectStore.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Stable, explicit checksum input; excludes the checksum field itself. */
    public String checksumValue() {
        StringBuilder value = new StringBuilder("hashvault-manifest-v1\n")
            .append(id).append('\n').append(filename).append('\n').append(originalSize).append('\n')
            .append(chunker).append('\n').append(previousVersionId).append('\n').append(createdAt).append('\n');
        for (ChunkRef chunk : chunks) value.append(chunk.hash).append(':').append(chunk.size).append('\n');
        return ObjectStore.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void seal() {
        formatVersion = CURRENT_FORMAT_VERSION;
        checksum = checksumValue();
    }

    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        if (filename == null || filename.isBlank()) errors.add("missing filename");
        if (originalSize < 0) errors.add("negative original size");
        if (chunker == null || chunker.isBlank()) errors.add("missing chunker");
        if (chunks == null) errors.add("missing chunk list");
        else {
            long total = 0;
            for (int i = 0; i < chunks.size(); i++) {
                ChunkRef c = chunks.get(i);
                if (c == null || c.hash == null || !c.hash.matches("[a-f0-9]{64}") || c.size < 0) {
                    errors.add("invalid chunk reference at index " + i);
                } else total += c.size;
            }
            if (total != originalSize) errors.add("chunk lengths do not equal original size");
        }
        if (id == null || !id.matches("[a-f0-9]{64}") || filename == null || chunker == null || chunks == null
                || !id.equals(identity())) errors.add("manifest identity mismatch");
        if (formatVersion < 0 || formatVersion > CURRENT_FORMAT_VERSION) errors.add("unsupported manifest format version: " + formatVersion);
        if (formatVersion >= 1 && (checksum == null || !checksum.equals(checksumValue()))) errors.add("manifest checksum mismatch");
        return errors;
    }
}
