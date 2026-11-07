package io.hashvault;

import io.hashvault.server.HashVaultServer;
import io.hashvault.store.HashVaultEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class HashVaultServerTest {
    @TempDir Path temp;

    @Test void healthIsPublicButVaultEndpointsRequireBearerToken() throws Exception {
        HashVaultEngine engine = new HashVaultEngine(temp.resolve("vault"));
        String token = "test-api-token-that-is-long-enough-123456";
        try (HashVaultServer server = new HashVaultServer(engine, new InetSocketAddress("127.0.0.1", 0), token)) {
            server.start();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            String base = "http://127.0.0.1:" + server.address().getPort();

            HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());

            HttpResponse<String> denied = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/manifests")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode());

            HttpResponse<String> allowed = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/manifests"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowed.statusCode());
            assertEquals("[]", allowed.body());
        }
    }

    @Test void importsAndRestoresThroughAuthenticatedApi() throws Exception {
        HashVaultEngine engine = new HashVaultEngine(temp.resolve("vault"));
        String token = "test-api-token-that-is-long-enough-123456";
        byte[] payload = "HTTP upload round-trip payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (HashVaultServer server = new HashVaultServer(engine, new InetSocketAddress("127.0.0.1", 0), token)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.address().getPort();
            HttpResponse<String> imported = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/import"))
                .header("Authorization", "Bearer " + token)
                .header("X-Filename", "api-upload.txt")
                .header("X-Chunker", "fixed")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, imported.statusCode(), imported.body());
            String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(imported.body()).get("manifestId").asText();
            HttpResponse<byte[]> restored = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/restore/" + id))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, restored.statusCode());
            assertArrayEquals(payload, restored.body());
        }
    }

    @Test void rejectsPathTraversalInUploadFilename() throws Exception {
        HashVaultEngine engine = new HashVaultEngine(temp.resolve("vault"));
        String token = "test-api-token-that-is-long-enough-123456";
        try (HashVaultServer server = new HashVaultServer(engine, new InetSocketAddress("127.0.0.1", 0), token)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.address().getPort();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/import"))
                .header("Authorization", "Bearer " + token)
                .header("X-Filename", "../outside.txt")
                .POST(HttpRequest.BodyPublishers.ofString("bad")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertFalse(Files.exists(temp.resolve("outside.txt")));
        }
    }
}
