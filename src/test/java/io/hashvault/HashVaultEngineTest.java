package io.hashvault;
import io.hashvault.chunk.CdcChunker;
import io.hashvault.chunk.FixedChunker;
import io.hashvault.store.HashVaultEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class HashVaultEngineTest {
    @TempDir Path temp;
    @Test void roundTripFixedChunks() throws Exception {
        Path input = temp.resolve("input.bin"), output = temp.resolve("output.bin");
        byte[] data = new byte[250_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte)(i % 97);
        Files.write(input, data);
        HashVaultEngine engine = new HashVaultEngine(temp.resolve("vault"));
        var manifest = engine.put(input, new FixedChunker(32_768), 3);
        engine.get(manifest.id, output);
        assertArrayEquals(data, Files.readAllBytes(output));
        assertEquals(8, manifest.chunks.size());
    }
    @Test void deduplicatesRepeatedChunks() throws Exception {
        Path a = temp.resolve("a.txt"), b = temp.resolve("b.txt");
        byte[] data = "same content repeated ".repeat(1000).getBytes();
        Files.write(a, data); Files.write(b, data);
        HashVaultEngine engine = new HashVaultEngine(temp.resolve("vault"));
        var first = engine.put(a, new FixedChunker(1024), 2);
        long afterFirst = engine.storedBytes();
        var second = engine.put(b, new FixedChunker(1024), 2);
        assertEquals(afterFirst, engine.storedBytes());
        assertEquals(first.chunks.stream().map(c -> c.hash).toList(), second.chunks.stream().map(c -> c.hash).toList());
    }
    @Test void roundTripCdcChunks() throws Exception {
        Path input = temp.resolve("cdc.txt"), output = temp.resolve("restored.txt");
        byte[] data = "content-defined chunking ".repeat(20_000).getBytes();
        Files.write(input, data);
        HashVaultEngine engine = new HashVaultEngine(temp.resolve("vault"));
        var manifest = engine.put(input, new CdcChunker(512, 1024, 2048), 2);
        engine.get(manifest.id, output);
        assertArrayEquals(data, Files.readAllBytes(output));
        engine.verify();
    }
    @Test void handlesEmptyFileWithoutCreatingObjects() throws Exception {
        Path input = temp.resolve("empty"), output = temp.resolve("restored");
        Files.write(input, new byte[0]);
        HashVaultEngine engine = new HashVaultEngine(temp.resolve("vault"));
        var manifest = engine.put(input, new FixedChunker(4096), 1);
        assertTrue(manifest.chunks.isEmpty());
        engine.get(manifest.id, output);
        assertEquals(0, Files.size(output));
        engine.verify();
    }
    @Test void versionsFileAndPreservesOldSnapshot() throws Exception {
        Path input = temp.resolve("notes.txt"), oldCopy = temp.resolve("old.txt");
        Files.writeString(input, "version one");
        HashVaultEngine engine = new HashVaultEngine(temp.resolve("vault"));
        var first = engine.put(input, new FixedChunker(4), 2);
        Files.writeString(input, "version two with changes");
        var second = engine.put(input, new FixedChunker(4), 2);
        assertEquals(first.id, second.previousVersionId);
        assertEquals(2, engine.history("notes.txt").size());
        engine.get(first.id, oldCopy);
        assertEquals("version one", Files.readString(oldCopy));
    }
    @Test void repeatedUnchangedImportDoesNotCreateNewVersion() throws Exception {
        Path input = temp.resolve("same.txt");
        Files.writeString(input, "identical bytes");
        HashVaultEngine engine = new HashVaultEngine(temp.resolve("vault"));
        var first = engine.put(input, new FixedChunker(4), 1);
        var second = engine.put(input, new FixedChunker(4), 1);
        assertEquals(first.id, second.id);
        assertEquals(1, engine.history("same.txt").size());
    }
    @Test void garbageCollectionOnlyRemovesUnreferencedObjects() throws Exception {
        Path input = temp.resolve("kept.txt");
        Files.writeString(input, "keep this content");
        HashVaultEngine engine = new HashVaultEngine(temp.resolve("vault"));
        engine.put(input, new FixedChunker(5), 1);
        new io.hashvault.store.ObjectStore(temp.resolve("vault")).put("orphaned data".getBytes());
        var dry = engine.garbageCollect(true);
        assertEquals(1, dry.get("unreferencedObjects"));
        assertEquals(0, engine.garbageCollect(true).get("deletedObjects"));
        var applied = engine.garbageCollect(false);
        assertEquals(1, applied.get("deletedObjects"));
        engine.verify();
    }
}
