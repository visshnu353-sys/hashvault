# HashVault
Java 17 content-addressable storage with SHA-256 object identity, deduplication, compression, manifests, fixed-size and content-defined chunking.

## Capabilities
- Fixed-size and gear-hash CDC chunking.
- Streaming imports with bounded in-flight chunks.
- Parallel chunk hashing/compression/object writes while preserving manifest order.
- SHA-256 object identity and cross-file deduplication.
- GZIP compression when it reduces object size.
- Immutable manifests with file version history.
- Restore any historical manifest.
- Deduplication/storage report and safe garbage collection.
- JUnit tests and GitHub Actions CI.

## Build
    mvn clean verify
    mvn package

The shaded runnable CLI is created at target/hashvault-1.0.0.jar.

## CLI
    java -jar target/hashvault-1.0.0.jar init ./vault
    java -jar target/hashvault-1.0.0.jar put ./vault ./file.bin
    java -jar target/hashvault-1.0.0.jar put ./vault ./file.bin --chunker=cdc --threads=4
    java -jar target/hashvault-1.0.0.jar get ./vault MANIFEST_ID ./restored.bin
    java -jar target/hashvault-1.0.0.jar list ./vault
    java -jar target/hashvault-1.0.0.jar history ./vault file.bin
    java -jar target/hashvault-1.0.0.jar verify ./vault
    java -jar target/hashvault-1.0.0.jar stats ./vault
    java -jar target/hashvault-1.0.0.jar gc ./vault
    java -jar target/hashvault-1.0.0.jar gc ./vault --apply

Garbage collection is dry-run by default. Review the reclaimable object count and byte estimate before adding --apply. It deletes only objects not referenced by any manifest; historical versions remain restorable and therefore retain their objects.

## Benchmark
    mvn exec:java -Dexec.mainClass=io.hashvault.benchmark.HashVaultBenchmark -Dexec.args="128 ./benchmark-work"

The benchmark generates deterministic random input (default 128 MiB) and compares fixed and CDC ingestion in separate vault directories.

## Storage layout
    vault/
      objects/<first-two-hash-characters>/<sha256>.blob
      manifests/<manifest-id>.json

Object hashes are calculated over uncompressed bytes. Each object has a magic header, codec id, original length, and raw or GZIP payload. Manifests preserve ordered hashes, chunk lengths, chunker settings, creation time and parent-version id.

## Version semantics
A file is versioned by its basename within a vault. A changed import creates a new immutable manifest pointing to the previous version. Importing unchanged bytes with the same chunker returns the existing version. Use history to list snapshots and get with any manifest id to restore one.

## Safety and limitations
Objects are written to temporary files and published atomically where supported. Manifests are saved after all chunk writes complete; a crash between object writes and manifest publication can leave unreferenced objects, which dry-run/actual GC can identify and remove.

This is a local single-process-oriented prototype, not yet a fully transactional multi-process database. Concurrent imports to the same filename are not serialized into a guaranteed linear version chain. There is no encryption, remote replication, object lease system, or journal-based crash recovery yet. Run verify after storage maintenance and keep independent backups of important vault data.
