# HashVault
Java 17 content-addressable storage with SHA-256 object identity, deduplication, compression, manifests, fixed-size and content-defined chunking.

## Capabilities
- Fixed-size and gear-hash CDC chunking.
- Streaming imports with bounded in-flight chunks.
- Parallel chunk hashing/compression/object writes while preserving manifest order.
- SHA-256 object identity and cross-file deduplication.
- Configurable `auto`, `gzip`, or `none` object compression.
- Bounded byte-sized LRU cache for recently read/written objects, with hit/miss metrics.
- Immutable manifests with file version history.
- Restore any historical manifest.
- Deduplication/storage report and safe garbage collection.
- Durable write-ahead log with startup recovery of incomplete transactions.
- OS-backed exclusive vault lock for mutation and garbage collection.
- JUnit tests and GitHub Actions CI.

## Build
    mvn clean verify
    mvn package

The shaded runnable CLI is created at target/hashvault-1.0.0.jar.

## CLI
    java -jar target/hashvault-1.0.0.jar init ./vault
    java -jar target/hashvault-1.0.0.jar put ./vault ./file.bin
    java -jar target/hashvault-1.0.0.jar put ./vault ./file.bin --chunker=cdc --threads=4 --compression=auto
    java -jar target/hashvault-1.0.0.jar put ./vault ./file.bin --compression=gzip
    java -jar target/hashvault-1.0.0.jar put ./vault ./file.bin --compression=none
    java -jar target/hashvault-1.0.0.jar get ./vault MANIFEST_ID ./restored.bin
    java -jar target/hashvault-1.0.0.jar list ./vault
    java -jar target/hashvault-1.0.0.jar history ./vault file.bin
    java -jar target/hashvault-1.0.0.jar verify ./vault
    java -jar target/hashvault-1.0.0.jar recover ./vault
    java -jar target/hashvault-1.0.0.jar stats ./vault
    java -jar target/hashvault-1.0.0.jar cache ./vault
    java -jar target/hashvault-1.0.0.jar gc ./vault
    java -jar target/hashvault-1.0.0.jar gc ./vault --apply

Garbage collection is dry-run by default. Review the reclaimable object count and byte estimate before adding --apply. It deletes only objects not referenced by any manifest; historical versions remain restorable and therefore retain their objects.

## Benchmarks
The end-to-end benchmark generates deterministic input and compares fixed and CDC ingestion:

    mvn exec:java -Dexec.mainClass=io.hashvault.benchmark.HashVaultBenchmark -Dexec.args="128 ./benchmark-work"

For microbenchmarks of object read/write throughput across compression modes, build and run JMH:

    mvn -Pjmh clean package
    java -jar target/hashvault-1.0.0-benchmarks.jar ObjectStoreBenchmark

JMH reports throughput by payload size and compression policy. Use `-prof gc` to inspect allocation and garbage-collection overhead.

## Storage layout
    vault/
      objects/<first-two-hash-characters>/<sha256>.blob
      manifests/<manifest-id>.json

Object hashes are calculated over uncompressed bytes. Each object has a magic header, codec id, original length, and raw or GZIP payload. Existing RAW and GZIP objects remain readable regardless of the current write policy. The LRU cache stores uncompressed bytes and is bounded by `-Dhashvault.cacheBytes` (default 32 MiB; set to `0` to disable). Manifests preserve ordered hashes, chunk lengths, chunker settings, creation time and parent-version id.

## Version semantics
A file is versioned by its basename within a vault. A changed import creates a new immutable manifest pointing to the previous version. Importing unchanged bytes with the same chunker returns the existing version. Use history to list snapshots and get with any manifest id to restore one.

## Safety and limitations
Objects are written to temporary files and published atomically where supported. A durable append-only WAL records BEGIN, PREPARE, COMMIT and ABORT transitions. On startup, recovery completes a prepared transaction when its immutable manifest exists, or records an abort when publication did not complete. A crash after object publication but before manifest publication can still leave unreferenced objects; garbage collection can identify these.

Mutations and garbage collection acquire an OS-backed exclusive lock file (`.hashvault.lock`), preventing simultaneous HashVault writers from changing the same vault. This is local-filesystem coordination, not distributed locking; all writers must use HashVault. Reads use immutable manifests and content-addressed objects. There is no encryption, remote replication or cross-host coordination. Run verify after storage maintenance and keep independent backups of important vault data.
