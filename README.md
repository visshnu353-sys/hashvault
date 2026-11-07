# HashVault
Java 17 content-addressable storage with SHA-256 object identity, deduplication, compression, manifests, fixed-size and content-defined chunking.

## Capabilities
- Fixed-size and gear-hash CDC chunking.
- Streaming imports with bounded in-flight chunks.
- Parallel chunk hashing/compression/object writes while preserving manifest order.
- SHA-256 object identity and cross-file deduplication.
- Configurable `auto`, `gzip`, or `none` object compression.
- Bounded byte-sized LRU cache for recently read/written objects, with hit/miss metrics.
- Immutable, checksummed, versioned manifests with file version history.
- Full integrity scans validate manifest identities, metadata checksums, object SHA-256 hashes and chunk lengths.
- Conservative repair replays the WAL and reports damaged data without inventing or deleting content.
- Restore any historical manifest.
- Deduplication/storage report and safe garbage collection.
- Durable write-ahead log with startup recovery of incomplete transactions.
- OS-backed exclusive vault lock for mutation and garbage collection.
- Optional AES-256-GCM encryption for object payloads, with PBKDF2-HMAC-SHA256 password-derived keys.
- Streaming AES-GCM encrypted backup and restore.
- JUnit tests and GitHub Actions CI.

## Build
    mvn clean verify
    mvn package

The shaded runnable CLI is created at target/hashvault-1.0.0.jar.

## CLI
    java -jar target/hashvault-1.0.0.jar init ./vault
    HASHVAULT_PASSWORD='use-a-long-secret-phrase' java -jar target/hashvault-1.0.0.jar init ./secure-vault --encrypt
    HASHVAULT_PASSWORD='use-a-long-secret-phrase' java -jar target/hashvault-1.0.0.jar encrypt ./existing-vault
    HASHVAULT_PASSWORD='use-a-long-secret-phrase' java -jar target/hashvault-1.0.0.jar backup ./secure-vault ./secure-vault.hvb
    HASHVAULT_PASSWORD='use-a-long-secret-phrase' java -jar target/hashvault-1.0.0.jar restore ./secure-vault.hvb ./restored-vault
    java -jar target/hashvault-1.0.0.jar put ./vault ./file.bin
    java -jar target/hashvault-1.0.0.jar put ./vault ./file.bin --chunker=cdc --threads=4 --compression=auto
    java -jar target/hashvault-1.0.0.jar put ./vault ./file.bin --compression=gzip
    java -jar target/hashvault-1.0.0.jar put ./vault ./file.bin --compression=none
    java -jar target/hashvault-1.0.0.jar get ./vault MANIFEST_ID ./restored.bin
    java -jar target/hashvault-1.0.0.jar list ./vault
    java -jar target/hashvault-1.0.0.jar history ./vault file.bin
    java -jar target/hashvault-1.0.0.jar verify ./vault
    java -jar target/hashvault-1.0.0.jar scan ./vault
    java -jar target/hashvault-1.0.0.jar repair ./vault
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

Object hashes are calculated over uncompressed bytes. Each object has a magic header, codec id, original length, and raw or GZIP payload. Encrypted object codecs use AES-256-GCM with a fresh 96-bit nonce and authenticate the object hash, length and codec as additional data. Existing RAW and GZIP objects remain readable after encryption is enabled; `encrypt` migrates all current objects and can be rerun after an interrupted migration. The LRU cache stores uncompressed bytes and is bounded by `-Dhashvault.cacheBytes` (default 32 MiB; set to `0` to disable). Manifests preserve ordered hashes, chunk lengths, chunker settings, creation time and parent-version id. New manifests use format version 1 and a SHA-256 metadata checksum. Version 0 manifests from earlier HashVault builds remain readable and are validated using their original identity formula; new writes use version 1 without rewriting historical snapshots.

## Version semantics
A file is versioned by its basename within a vault. A changed import creates a new immutable manifest pointing to the previous version. Importing unchanged bytes with the same chunker returns the existing version. Use history to list snapshots and get with any manifest id to restore one.

## Safety and limitations
Objects are written to temporary files and published atomically where supported. A durable append-only WAL records BEGIN, PREPARE, COMMIT and ABORT transitions. On startup, recovery completes a prepared transaction when its immutable manifest exists, or records an abort when publication did not complete. A crash after object publication but before manifest publication can still leave unreferenced objects; garbage collection can identify these.

Mutations and garbage collection acquire an OS-backed exclusive lock file (`.hashvault.lock`), preventing simultaneous HashVault writers from changing the same vault. This is local-filesystem coordination, not distributed locking; all writers must use HashVault. Reads use immutable manifests and content-addressed objects. Encryption is optional. Passwords are read only from the `HASHVAULT_PASSWORD` environment variable, never from command-line arguments. Vault keys are derived with PBKDF2-HMAC-SHA256 (310,000 iterations) and a per-vault random salt; a verifier is stored in `vault.crypto`, not the key. Backups are separately encrypted with AES-256-GCM and their own salt/nonce. Encrypted object payloads are protected, but manifest filenames/metadata and the WAL remain plaintext; this is not full metadata encryption. Losing the password makes encrypted objects and backups unrecoverable. Do not store the password in shell history; prefer a secret manager or secure environment injection. Java cannot guarantee physical secure erasure of flash storage, so deleting objects is not a secure-erase guarantee. There is no remote replication or cross-host coordination. Run `scan` to audit every manifest and unique object, or `verify` to fail fast if any issue is found. `repair` replays pending WAL transactions and runs the audit; it deliberately does not fabricate missing chunks or overwrite corrupted objects. Restore damaged content from an independent backup. Keep independent backups of important vault data.
