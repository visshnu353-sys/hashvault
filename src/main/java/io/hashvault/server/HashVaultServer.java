package io.hashvault.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.hashvault.chunk.CdcChunker;
import io.hashvault.chunk.Chunker;
import io.hashvault.chunk.FixedChunker;
import io.hashvault.index.Manifest;
import io.hashvault.store.HashVaultEngine;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;

/** Small authenticated HTTP interface. Use a TLS reverse proxy for non-loopback deployments. */
public final class HashVaultServer implements AutoCloseable {
    private static final long MAX_UPLOAD_BYTES = 1L << 30;
    private final HttpServer server;
    private final HashVaultEngine engine;
    private final byte[] token;
    private final ObjectMapper mapper = new ObjectMapper();

    public HashVaultServer(HashVaultEngine engine, InetSocketAddress address, String bearerToken) throws IOException {
        if (bearerToken == null || bearerToken.length() < 32)
            throw new IllegalArgumentException("HASHVAULT_API_TOKEN must contain at least 32 characters");
        this.engine = Objects.requireNonNull(engine);
        this.token = bearerToken.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(address, 64);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors())));
    }

    public void start() { server.start(); }
    public InetSocketAddress address() { return server.getAddress(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/health".equals(path)) {
                sendJson(exchange, 200, Map.of("status", "ok", "service", "HashVault"));
                return;
            }
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendJson(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/v1/manifests".equals(path)) {
                List<Map<String, Object>> items = new ArrayList<>();
                for (Manifest m : engine.list()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", m.id); item.put("filename", m.filename);
                    item.put("size", m.originalSize); item.put("chunks", m.chunks.size());
                    item.put("createdAt", m.createdAt); item.put("previousVersionId", m.previousVersionId);
                    items.add(item);
                }
                sendJson(exchange, 200, items);
                return;
            }
            if ("GET".equals(method) && "/v1/stats".equals(path)) {
                sendJson(exchange, 200, engine.deduplicationReport());
                return;
            }
            if ("POST".equals(method) && "/v1/import".equals(path)) {
                importFile(exchange);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/v1/restore/")) {
                String id = path.substring("/v1/restore/".length());
                if (!id.matches("[a-f0-9]{64}")) {
                    sendJson(exchange, 400, Map.of("error", "invalid manifest id"));
                    return;
                }
                Path temp = Files.createTempFile("hashvault-http-restore-", ".bin");
                try {
                    engine.get(id, temp);
                    long size = Files.size(temp);
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"restore.bin\"");
                    exchange.sendResponseHeaders(200, size);
                    try (OutputStream out = exchange.getResponseBody()) { Files.copy(temp, out); }
                } finally { Files.deleteIfExists(temp); }
                return;
            }
            sendJson(exchange, 404, Map.of("error", "not found"));
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                sendJson(exchange, 500, Map.of("error", e.getMessage() == null ? "internal error" : e.getMessage()));
            }
        }
    }

    private void importFile(HttpExchange exchange) throws IOException {
        String filename = exchange.getRequestHeaders().getFirst("X-Filename");
        if (filename == null || filename.isBlank()) filename = "upload.bin";
        filename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
        if (filename.length() > 180 || filename.contains("/") || filename.contains("\\")
                || filename.equals(".") || filename.equals("..") || filename.indexOf('\0') >= 0)
            throw new IllegalArgumentException("X-Filename must be a simple filename without path components");
        String mode = Optional.ofNullable(exchange.getRequestHeaders().getFirst("X-Chunker")).orElse("fixed");
        Chunker chunker;
        if ("fixed".equalsIgnoreCase(mode)) chunker = new FixedChunker(1024 * 1024);
        else if ("cdc".equalsIgnoreCase(mode)) chunker = new CdcChunker(16 * 1024, 64 * 1024, 256 * 1024);
        else throw new IllegalArgumentException("X-Chunker must be fixed or cdc");

        Path dir = Files.createTempDirectory("hashvault-http-upload-");
        Path input = dir.resolve(filename);
        long received = 0;
        try {
            try (InputStream in = exchange.getRequestBody(); OutputStream out = Files.newOutputStream(input)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    received += n;
                    if (received > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("Upload exceeds 1 GiB limit");
                    out.write(buffer, 0, n);
                }
            }
            Manifest manifest = engine.put(input, chunker);
            sendJson(exchange, 201, Map.of("manifestId", manifest.id, "filename", manifest.filename,
                "size", manifest.originalSize, "chunks", manifest.chunks.size()));
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(dir);
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        byte[] supplied = header.substring(7).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, supplied);
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    @Override public void close() { server.stop(1); }
}
