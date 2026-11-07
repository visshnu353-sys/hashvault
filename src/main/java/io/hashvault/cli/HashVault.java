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
              put <vault> <file> [--chunker=fixed|cdc] [--chunk-size=BYTES]
              get <vault> <manifest-id> <output-file>
              list <vault>
              verify <vault>
              stats <vault>
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
                    Chunker chunker = mode.equalsIgnoreCase("cdc")
                        ? new CdcChunker(16 * 1024, 64 * 1024, 256 * 1024) : new FixedChunker(size);
                    Manifest m = engine.put(Path.of(args[2]), chunker);
                    System.out.println("Manifest: " + m.id);
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
                case "verify" -> { engine.verify(); System.out.println("All manifests and objects verified."); }
                case "stats" -> {
                    long logical = engine.list().stream().mapToLong(m -> m.originalSize).sum();
                    System.out.println("Manifests: " + engine.list().size());
                    System.out.println("Logical bytes (sum of imports): " + logical);
                    System.out.println("Stored object bytes: " + engine.storedBytes());
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
