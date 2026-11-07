package io.hashvault.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Small append-only write-ahead journal. Every record is forced to disk before
 * the operation advances. Recovery completes a prepared transaction if its
 * immutable manifest exists; otherwise it marks the transaction aborted.
 */
public final class TransactionLog {
    private final Path journal;

    public TransactionLog(Path vaultRoot) throws IOException {
        Files.createDirectories(vaultRoot);
        journal = vaultRoot.resolve("vault.wal");
        if (!Files.exists(journal)) Files.createFile(journal);
    }

    public String begin(String filename) throws IOException {
        String id = UUID.randomUUID().toString();
        append("BEGIN\t" + id + "\t" + encode(filename));
        return id;
    }

    public void prepare(String transactionId, String manifestId) throws IOException {
        append("PREPARE\t" + transactionId + "\t" + manifestId);
    }

    public void commit(String transactionId) throws IOException {
        append("COMMIT\t" + transactionId);
    }

    public void abort(String transactionId) throws IOException {
        append("ABORT\t" + transactionId);
    }

    public synchronized Map<String, Integer> recover(Predicate<String> manifestExists) throws IOException {
        Map<String, String> pending = new LinkedHashMap<>();
        for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\\t", -1);
            if (fields.length < 2) continue; // Ignore a torn final record.
            switch (fields[0]) {
                case "BEGIN" -> pending.put(fields[1], null);
                case "PREPARE" -> {
                    if (fields.length == 3 && pending.containsKey(fields[1])) pending.put(fields[1], fields[2]);
                }
                case "COMMIT", "ABORT" -> pending.remove(fields[1]);
                default -> { /* Forward-compatible journal record. */ }
            }
        }
        int completed = 0, aborted = 0;
        for (Map.Entry<String, String> tx : pending.entrySet()) {
            if (tx.getValue() != null && manifestExists.test(tx.getValue())) {
                commit(tx.getKey());
                completed++;
            } else {
                abort(tx.getKey());
                aborted++;
            }
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("completedTransactions", completed);
        result.put("abortedTransactions", aborted);
        result.put("pendingTransactions", pending.size());
        return result;
    }

    private synchronized void append(String record) throws IOException {
        byte[] bytes = (record + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static String encode(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
