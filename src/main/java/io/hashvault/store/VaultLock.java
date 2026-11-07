package io.hashvault.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Exclusive, OS-backed lock for one vault. Acquire per mutating operation. */
public final class VaultLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private VaultLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static VaultLock acquire(Path vaultRoot) throws IOException {
        Files.createDirectories(vaultRoot);
        FileChannel channel = FileChannel.open(vaultRoot.resolve(".hashvault.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException e) { lock = null; }
            if (lock == null) throw new IOException("Vault is locked by another HashVault operation: " + vaultRoot);
            return new VaultLock(channel, lock);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @Override public void close() throws IOException {
        try { if (lock.isValid()) lock.release(); }
        finally { channel.close(); }
    }
}
