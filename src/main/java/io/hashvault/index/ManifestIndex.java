package io.hashvault.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ManifestIndex {
    public record ScanEntry(String fileName, Manifest manifest, String error) {
        public boolean valid() { return error == null; }
    }

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public ManifestIndex(Path root) throws IOException {
        dir = root.resolve("manifests");
        Files.createDirectories(dir);
    }

    public void save(Manifest manifest) throws IOException {
        if (manifest.id == null || !manifest.id.equals(manifest.identity()))
            throw new IOException("Refusing to save manifest with invalid identity");
        manifest.seal();
        Path target = dir.resolve(manifest.id + ".json");
        Path temp = Files.createTempFile(dir, ".manifest-", ".tmp");
        try {
            mapper.writeValue(temp.toFile(), manifest);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (FileAlreadyExistsException exists) {
                Manifest existing = mapper.readValue(target.toFile(), Manifest.class);
                if (!existing.validationErrors().isEmpty()) throw new IOException("Existing immutable manifest is invalid: " + manifest.id);
            } catch (AtomicMoveNotSupportedException e) {
                try { Files.move(temp, target); }
                catch (FileAlreadyExistsException exists) {
                    Manifest existing = mapper.readValue(target.toFile(), Manifest.class);
                    if (!existing.validationErrors().isEmpty()) throw new IOException("Existing immutable manifest is invalid: " + manifest.id);
                }
            }
        } finally { Files.deleteIfExists(temp); }
    }

    public boolean exists(String id) {
        return id != null && id.matches("[a-f0-9]{64}") && Files.isRegularFile(dir.resolve(id + ".json"));
    }

    public Manifest load(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid manifest id");
        Manifest manifest = mapper.readValue(dir.resolve(id + ".json").toFile(), Manifest.class);
        List<String> errors = manifest.validationErrors();
        if (!errors.isEmpty()) throw new IOException("Invalid manifest " + id + ": " + String.join(", ", errors));
        return manifest;
    }

    /** Reads every manifest independently so one damaged file does not hide the rest of the scan. */
    public List<ScanEntry> scan() throws IOException {
        List<ScanEntry> result = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString();
                try {
                    Manifest manifest = mapper.readValue(file.toFile(), Manifest.class);
                    List<String> errors = manifest.validationErrors();
                    if (!file.getFileName().toString().equals(manifest.id + ".json"))
                        errors.add("manifest filename does not match manifest id");
                    result.add(new ScanEntry(name, manifest, errors.isEmpty() ? null : String.join("; ", errors)));
                } catch (Exception e) {
                    result.add(new ScanEntry(name, null, "cannot parse manifest: " + e.getMessage()));
                }
            }
        }
        return result;
    }

    public List<Manifest> list() throws IOException {
        List<Manifest> result = new ArrayList<>();
        for (ScanEntry entry : scan()) {
            if (!entry.valid()) throw new IOException("Invalid manifest " + entry.fileName() + ": " + entry.error());
            result.add(entry.manifest());
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
