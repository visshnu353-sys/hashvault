package io.hashvault;

import io.hashvault.store.TransactionLog;
import io.hashvault.store.VaultLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TransactionLogTest {
    @TempDir Path temp;

    @Test void recoveryCompletesPreparedManifestAndAbortsUnpreparedTransaction() throws Exception {
        TransactionLog log = new TransactionLog(temp);
        String committed = log.begin("file-a.txt");
        log.prepare(committed, "a".repeat(64));
        String abandoned = log.begin("file-b.txt");

        Map<String, Integer> report = log.recover(id -> id.equals("a".repeat(64)));

        assertEquals(1, report.get("completedTransactions"));
        assertEquals(1, report.get("abortedTransactions"));
        assertEquals(2, report.get("pendingTransactions"));
        Map<String, Integer> secondPass = log.recover(id -> true);
        assertEquals(0, secondPass.get("pendingTransactions"));
    }

    @Test void vaultLockRejectsSecondWriterAndCanBeReacquiredAfterClose() throws Exception {
        VaultLock first = VaultLock.acquire(temp);
        try {
            assertThrows(IOException.class, () -> VaultLock.acquire(temp));
        } finally {
            first.close();
        }
        try (VaultLock ignored = VaultLock.acquire(temp)) {
            assertNotNull(ignored);
        }
    }
}
