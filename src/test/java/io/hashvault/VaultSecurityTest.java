package io.hashvault;

import io.hashvault.store.ObjectStore;
import io.hashvault.store.VaultBackup;
import io.hashvault.store.VaultCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class VaultSecurityTest {
    @TempDir Path temp;

    @Test void derivesAndUnlocksVaultKeyAndRejectsWrongPassword() throws Exception {
        VaultCrypto crypto = new VaultCrypto(temp);
        byte[] created = crypto.initialize("correct horse battery".toCharArray());
        byte[] unlocked = crypto.unlock("correct horse battery".toCharArray());
        assertArrayEquals(created, unlocked);
        assertThrows(IOException.class, () -> crypto.unlock("incorrect password".toCharArray()));
        assertFalse(Files.readString(temp.resolve("vault.crypto")).contains("correct horse battery"));
    }

    @Test void encryptsObjectsAndAuthenticatesCiphertext() throws Exception {
        Path vault = temp.resolve("encrypted");
        byte[] key = new byte[32];
        java.security.SecureRandom.getInstanceStrong().nextBytes(key);
        byte[] secret = "private object payload that must not appear on disk".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ObjectStore writer = new ObjectStore(vault, 0);
        writer.setCompression(io.hashvault.store.CompressionCodec.NONE);
        writer.setEncryptionKey(key);
        String hash = writer.put(secret);
        Path blob = vault.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash + ".blob");
        byte[] persisted = Files.readAllBytes(blob);
        assertEquals(2, persisted[4]);
        assertFalse(new String(persisted, java.nio.charset.StandardCharsets.ISO_8859_1)
            .contains("private object payload"));

        ObjectStore reader = new ObjectStore(vault, 0);
        reader.setEncryptionKey(key);
        assertArrayEquals(secret, reader.get(hash));

        persisted[persisted.length - 1] ^= 1;
        Files.write(blob, persisted);
        assertThrows(IOException.class, () -> reader.verifyAndGet(hash));
    }

    @Test void migratesExistingRawObjectsToEncryptedFormat() throws Exception {
        Path vault = temp.resolve("migration");
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        byte[] payload = "old plaintext object".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ObjectStore store = new ObjectStore(vault, 0);
        store.setCompression(io.hashvault.store.CompressionCodec.NONE);
        String hash = store.put(payload);
        Path blob = vault.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash + ".blob");
        assertEquals(0, Files.readAllBytes(blob)[4]);
        store.setEncryptionKey(key);
        store.encryptExistingObjects();
        assertEquals(2, Files.readAllBytes(blob)[4]);
        ObjectStore reopened = new ObjectStore(vault, 0);
        reopened.setEncryptionKey(key);
        assertArrayEquals(payload, reopened.get(hash));
    }

    @Test void createsAndRestoresAuthenticatedEncryptedBackup() throws Exception {
        Path vault = temp.resolve("source-vault");
        Files.createDirectories(vault.resolve("objects"));
        Files.createDirectories(vault.resolve("manifests"));
        Files.writeString(vault.resolve("vault.wal"), "BEGIN\ttx\tZmlsZQ\n");
        Files.writeString(vault.resolve("manifests").resolve("sample.json"), "manifest metadata");
        Files.writeString(vault.resolve("objects").resolve("secret.blob"), "sensitive payload");
        Path backup = temp.resolve("vault.hvb");
        VaultBackup.create(vault, backup, "backup passphrase long".toCharArray());
        assertFalse(new String(Files.readAllBytes(backup), java.nio.charset.StandardCharsets.ISO_8859_1)
            .contains("sensitive payload"));

        Path restored = temp.resolve("restored-vault");
        VaultBackup.restore(backup, restored, "backup passphrase long".toCharArray());
        assertEquals("sensitive payload", Files.readString(restored.resolve("objects").resolve("secret.blob")));
        assertEquals("manifest metadata", Files.readString(restored.resolve("manifests").resolve("sample.json")));
    }

    @Test void rejectsWrongPasswordForEncryptedBackup() throws Exception {
        Path vault = temp.resolve("backup-source");
        Files.createDirectories(vault.resolve("objects"));
        Files.createDirectories(vault.resolve("manifests"));
        Files.writeString(vault.resolve("vault.wal"), "");
        Path backup = temp.resolve("wrong-password.hvb");
        VaultBackup.create(vault, backup, "backup passphrase long".toCharArray());
        Path target = temp.resolve("must-cleanup");
        assertThrows(IOException.class, () ->
            VaultBackup.restore(backup, target, "another wrong passphrase".toCharArray()));
        assertFalse(Files.exists(target));
    }

    @Test void engineEncryptionMigratesPriorVaultObjects() throws Exception {
        Path vault = temp.resolve("engine-encryption");
        Path source = temp.resolve("before-encryption.txt");
        Files.writeString(source, "existing content is migrated before future writes");
        io.hashvault.store.HashVaultEngine engine = new io.hashvault.store.HashVaultEngine(vault);
        var manifest = engine.put(source, new io.hashvault.chunk.FixedChunker(64), 1);
        String hash = manifest.chunks.get(0).hash;
        Path blob = vault.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash + ".blob");
        assertEquals(0, Files.readAllBytes(blob)[4]);
        engine.enableEncryption("strong vault password".toCharArray());
        assertTrue(engine.encryptionEnabled());
        assertEquals(2, Files.readAllBytes(blob)[4]);
        byte[] key = new VaultCrypto(vault).unlock("strong vault password".toCharArray());
        ObjectStore reopened = new ObjectStore(vault, 0);
        reopened.setEncryptionKey(key);
        assertArrayEquals(Files.readAllBytes(source), reopened.get(hash));
        java.util.Arrays.fill(key, (byte) 0);
    }

}
