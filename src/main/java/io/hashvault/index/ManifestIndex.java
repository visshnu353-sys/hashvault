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
        mapper.writeValue(temp.toFile(), manifest);
        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    public Manifest load(String id) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid manifest id");
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
}
