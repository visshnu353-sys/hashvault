# HashVault
Java 17 content-addressable storage with SHA-256 object identity, deduplication, compression, manifests, fixed-size and content-defined chunking.

## Phase 2 improvements
- Streaming chunk consumer: ingestion retains at most one chunk rather than a list of all chunks.
- Buffered input/output for file operations.
- Temporary object files and atomic publication to avoid exposing incomplete objects to readers.
- Gear-hash CDC with configurable minimum, average and maximum chunk sizes.
- Benchmark harness comparing fixed and CDC chunking.
- Tests for empty files, larger inputs, round trips and deduplication.

## Build
    mvn test
    mvn package

## CLI
    java -jar target/hashvault-1.0.0.jar init ./vault
    java -jar target/hashvault-1.0.0.jar put ./vault ./file.bin
    java -jar target/hashvault-1.0.0.jar put ./vault ./file.bin --chunker=cdc
    java -jar target/hashvault-1.0.0.jar get ./vault MANIFEST_ID ./restored.bin
    java -jar target/hashvault-1.0.0.jar list ./vault
    java -jar target/hashvault-1.0.0.jar verify ./vault
    java -jar target/hashvault-1.0.0.jar stats ./vault

## Benchmark
Build first, then run:
    mvn exec:java -Dexec.mainClass=io.hashvault.benchmark.HashVaultBenchmark -Dexec.args="128 ./benchmark-work"

The benchmark generates deterministic random input (default 128 MiB) and writes results into separate fixed and CDC vault directories. The Maven exec plugin supplies the project dependency classpath.

## Storage format
Objects live at objects/<first-two-hash-characters>/<sha256>.blob. The object hash is calculated over uncompressed bytes. Each object has a magic header, codec id, original length, and raw or GZIP payload. Manifests preserve ordered hashes and lengths.

## Limitations
This remains a local prototype. It does not yet include a transactional journal, multi-process locking guarantees across all filesystems, garbage collection, encryption, remote replication, or crash-safe manifest/object transactions.
