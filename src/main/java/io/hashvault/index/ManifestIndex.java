package io.hashvault.index;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
public final class ManifestIndex {
    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    public ManifestIndex(Path root) throws IOException { dir = root.resolve("manifests"); Files.createDirectories(dir); }
    public void save(Manifest manifest) throws IOException {
        Path target = dir.resolve(manifest.id + ".json");
        Path temp = Files.createTempFile(dir, ".manifest-", ".tmp");
        try {
            mapper.writeValue(temp.toFile(), manifest);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (FileAlreadyExistsException exists) { /* immutable manifest already present */ }
            catch (AtomicMoveNotSupportedException e) {
                try { Files.move(temp, target); } catch (FileAlreadyExistsException exists) { /* already committed */ }
            }
        } finally { Files.deleteIfExists(temp); }
    }
    public boolean exists(String id) {
        return id != null && id.matches("[a-f0-9]{64}") && Files.isRegularFile(dir.resolve(id + ".json"));
    }
    public Manifest load(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid manifest id");
        return mapper.readValue(dir.resolve(id + ".json").toFile(), Manifest.class);
    }
    public List<Manifest> list() throws IOException {
        List<Manifest> result = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path p : files.filter(x -> x.toString().endsWith(".json")).sorted().toList())
                result.add(mapper.readValue(p.toFile(), Manifest.class));
        }
        return result;
    }
    public Manifest latest(String filename) throws IOException {
        return list().stream().filter(m -> Objects.equals(m.filename, filename))
            .max(Comparator.comparingLong(m -> m.createdAt)).orElse(null);
    }
    public List<Manifest> history(String filename) throws IOException {
        return list().stream().filter(m -> Objects.equals(m.filename, filename))
            .sorted(Comparator.comparingLong(m -> m.createdAt)).toList();
    }
}
