# HashVault

A Java 17 content-addressable storage engine. Files are chunked, SHA-256 addressed, compressed when beneficial, deduplicated across imports, indexed by manifests, and reconstructed on demand.

## Features
- Fixed-size and content-defined chunking (rolling hash boundary detector)
- SHA-256 object identity and cross-file deduplication
- GZIP compression when it saves space
- JSON manifests as the persistent reconstruction index
- put, get, list, verify, and stats CLI commands
- JUnit 5 tests

## Requirements
- JDK 17+
- Maven 3.9+

## Build
    mvn test
    mvn package

## Usage
    java -jar target/hashvault-1.0.0.jar init ./vault
    java -jar target/hashvault-1.0.0.jar put ./vault ./sample.bin
    java -jar target/hashvault-1.0.0.jar list ./vault
    java -jar target/hashvault-1.0.0.jar get ./vault MANIFEST_ID ./restored.bin
    java -jar target/hashvault-1.0.0.jar verify ./vault
    java -jar target/hashvault-1.0.0.jar stats ./vault

Select a chunker with --chunker=fixed or --chunker=cdc, and set fixed size with --chunk-size=1048576.

## On-disk layout
    vault/
      objects/ab/<sha256>.blob
      manifests/<manifest-id>.json

Objects are immutable. A manifest stores the original filename, byte length, chunker configuration, and ordered object hashes and lengths. Repeated content reuses existing objects.

## Design notes
- Object keys are SHA-256 of the uncompressed chunk bytes. Compression does not affect identity.
- Each object has a binary header followed by its payload. Reads validate decompression length and SHA-256.
- Manifests are written through a temporary file and atomic move where supported.
- This is a local single-writer prototype. Concurrent writers, garbage collection, encryption, crash-recovery journaling, and remote replication are future phases.
