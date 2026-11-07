package io.hashvault.store;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Streaming AES-256-GCM encrypted vault backup and restore. */
public final class VaultBackup {
    private static final int MAGIC = 0x4856424b; // HVBK
    private static final int ITERATIONS = 310_000;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final long MAX_RESTORE_BYTES = 1L << 40;
    private static final SecureRandom RANDOM = new SecureRandom();

    private VaultBackup() {}

    public static void create(Path vaultRoot, Path backupFile, char[] password) throws IOException {
        if (password == null || password.length < 12) throw new IllegalArgumentException("Backup password must contain at least 12 characters");
        Path root = vaultRoot.toAbsolutePath().normalize();
        Path target = backupFile.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Vault directory does not exist: " + root);
        if (target.startsWith(root)) throw new IOException("Backup file must be outside the vault directory");
        Files.createDirectories(target.getParent());
        byte[] salt = new byte[SALT_BYTES], nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(salt); RANDOM.nextBytes(nonce);
        byte[] key = derive(password, salt);
        Path temp = Files.createTempFile(target.getParent(), ".hashvault-backup-", ".tmp");
        try {
            try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(temp))) {
                DataOutputStream header = new DataOutputStream(raw);
                header.writeInt(MAGIC); header.write(salt); header.write(nonce); header.flush();
                Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce);
                try (ZipOutputStream zip = new ZipOutputStream(new CipherOutputStream(raw, cipher))) {
                    try (var walk = Files.walk(root)) {
                        for (Path file : walk.filter(p -> !p.equals(root))
                                .filter(p -> !p.getFileName().toString().equals(".hashvault.lock")).sorted().toList()) {
                            if (Files.isSymbolicLink(file)) continue;
                            String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                                zip.putNextEntry(new ZipEntry(name + "/"));
                                zip.closeEntry();
                            } else if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                                zip.putNextEntry(new ZipEntry(name));
                                Files.copy(file, zip);
                                zip.closeEntry();
                            }
                        }
                    }
                }
            }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(password, (char) 0);
            Files.deleteIfExists(temp);
        }
    }

    /** Restores only into a nonexistent or empty target directory. */
    public static void restore(Path backupFile, Path targetDirectory, char[] password) throws IOException {
        if (password == null || password.length < 12) throw new IllegalArgumentException("Backup password must contain at least 12 characters");
        Path target = targetDirectory.toAbsolutePath().normalize();
        if (Files.exists(target)) {
            try (var contents = Files.list(target)) {
                if (contents.findAny().isPresent()) throw new IOException("Restore target must be empty: " + target);
            }
        } else Files.createDirectories(target);
        byte[] key = null;
        boolean success = false;
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(backupFile))) {
            DataInputStream header = new DataInputStream(raw);
            if (header.readInt() != MAGIC) throw new IOException("Invalid HashVault backup header");
            byte[] salt = header.readNBytes(SALT_BYTES);
            byte[] nonce = header.readNBytes(NONCE_BYTES);
            if (salt.length != SALT_BYTES || nonce.length != NONCE_BYTES) throw new IOException("Truncated backup header");
            key = derive(password, salt);
            Cipher decrypt = cipher(Cipher.DECRYPT_MODE, key, nonce);
            long total = 0;
            int entries = 0;
            try (ZipInputStream zip = new ZipInputStream(new CipherInputStream(raw, decrypt))) {
                ZipEntry entry;
                byte[] buffer = new byte[64 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > 1_000_000) throw new IOException("Backup contains too many entries");
                    Path relative = Path.of(entry.getName());
                    if (relative.isAbsolute()) throw new IOException("Absolute path in backup entry");
                    Path output = target.resolve(relative).normalize();
                    if (!output.startsWith(target) || entry.getName().contains("\\") || entry.getName().contains("\0"))
                        throw new IOException("Unsafe path in backup entry: " + entry.getName());
                    if (entry.isDirectory()) Files.createDirectories(output);
                    else {
                        Files.createDirectories(output.getParent());
                        try (OutputStream out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                            int n;
                            while ((n = zip.read(buffer)) != -1) {
                                total += n;
                                if (total > MAX_RESTORE_BYTES) throw new IOException("Backup exceeds restore size limit");
                                out.write(buffer, 0, n);
                            }
                        }
                    }
                    zip.closeEntry();
                }
                // Force the authenticated stream to EOF so GCM verifies its final tag.
                while (zip.read(buffer) != -1) { /* drain authenticated trailing stream */ }
            } catch (IOException e) {
                throw new IOException("Backup authentication or extraction failed; password may be wrong or backup damaged", e);
            }
            success = true;
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            Arrays.fill(password, (char) 0);
            if (!success) deleteTree(target);
        }
        if (!Files.isRegularFile(target.resolve("vault.wal")) || !Files.isDirectory(target.resolve("objects"))
                || !Files.isDirectory(target.resolve("manifests"))) {
            deleteTree(target);
            throw new IOException("Backup does not contain a complete HashVault directory");
        }
    }

    private static byte[] derive(char[] password, byte[] salt) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception e) { throw new IOException("Unable to derive backup key", e); }
        finally { spec.clearPassword(); }
    }
    private static Cipher cipher(int mode, byte[] key, byte[] nonce) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return cipher;
        } catch (Exception e) { throw new IOException("Unable to initialize backup encryption", e); }
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
