package io.hashvault.cli;
import io.hashvault.chunk.*;
import io.hashvault.index.Manifest;
import io.hashvault.store.HashVaultEngine;
import java.nio.file.Path;
public final class HashVault {
    private static void usage() {
        System.out.println("""
            HashVault - content-addressable storage
            Commands:
              init <vault>
              put <vault> <file> [--chunker=fixed|cdc] [--chunk-size=BYTES] [--threads=N]
              get <vault> <manifest-id> <output-file>
              list <vault>
              history <vault> <filename>
              verify <vault>
              stats <vault>
              gc <vault> [--apply]
            """);
    }
    public static void main(String[] args) {
        if (args.length == 0) { usage(); return; }
        try {
            String command = args[0];
            if (command.equals("init")) {
                if (args.length < 2) { usage(); return; }
                new HashVaultEngine(Path.of(args[1]));
                System.out.println("Initialized vault at " + Path.of(args[1]).toAbsolutePath()); return;
            }
            if (args.length < 2) { usage(); return; }
            HashVaultEngine engine = new HashVaultEngine(Path.of(args[1]));
            switch (command) {
                case "put" -> {
                    if (args.length < 3) { usage(); return; }
                    String mode = option(args, "--chunker=", "fixed");
                    int size = Integer.parseInt(option(args, "--chunk-size=", "1048576"));
                    int threads = Integer.parseInt(option(args, "--threads=", Integer.toString(Runtime.getRuntime().availableProcessors())));
                    Chunker chunker = mode.equalsIgnoreCase("cdc")
                        ? new CdcChunker(16 * 1024, 64 * 1024, 256 * 1024) : new FixedChunker(size);
                    Manifest m = engine.put(Path.of(args[2]), chunker, threads);
                    System.out.println("Manifest: " + m.id);
                    System.out.println("Previous version: " + m.previousVersionId);
                    System.out.println("Chunks: " + m.chunks.size() + " | Original bytes: " + m.originalSize);
                }
                case "get" -> {
                    if (args.length < 4) { usage(); return; }
                    engine.get(args[2], Path.of(args[3])); System.out.println("Restored to " + Path.of(args[3]).toAbsolutePath());
                }
                case "list" -> {
                    for (Manifest m : engine.list())
                        System.out.printf("%s  %s  %d bytes  %d chunks%n", m.id, m.filename, m.originalSize, m.chunks.size());
                }
                case "history" -> {
                    if (args.length < 3) { usage(); return; }
                    for (Manifest m : engine.history(args[2]))
                        System.out.printf("%s  %s  %d bytes  %d chunks  parent=%s%n", m.id,
                            java.time.Instant.ofEpochMilli(m.createdAt), m.originalSize, m.chunks.size(), m.previousVersionId);
                }
                case "verify" -> { engine.verify(); System.out.println("All manifests and objects verified."); }
                case "stats" -> System.out.println(engine.deduplicationReport());
                case "gc" -> {
                    boolean apply = java.util.Arrays.asList(args).contains("--apply");
                    System.out.println(engine.garbageCollect(!apply));
                }
                default -> usage();
            }
        } catch (Exception e) { System.err.println("HashVault error: " + e.getMessage()); System.exit(1); }
    }
    private static String option(String[] args, String prefix, String fallback) {
        for (String arg : args) if (arg.startsWith(prefix)) return arg.substring(prefix.length());
        return fallback;
    }
}
