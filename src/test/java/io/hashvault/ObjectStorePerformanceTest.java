package io.hashvault;

import io.hashvault.store.CompressionCodec;
import io.hashvault.store.ObjectCache;
import io.hashvault.store.ObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ObjectStorePerformanceTest {
    @TempDir Path temp;

    @Test void lruCacheEvictsLeastRecentlyUsedEntryAndTracksHits() {
        ObjectCache cache = new ObjectCache(6);
        cache.put("a", new byte[]{1, 2, 3});
        cache.put("b", new byte[]{4, 5, 6});
        assertArrayEquals(new byte[]{1, 2, 3}, cache.get("a")); // a becomes most recently used
        cache.put("c", new byte[]{7, 8, 9}); // evicts b
        assertNull(cache.get("b"));
        assertArrayEquals(new byte[]{1, 2, 3}, cache.get("a"));
        assertEquals(6L, cache.stats().get("cachedBytes"));
        assertEquals(2L, cache.stats().get("hits"));
    }

    @Test void supportsRawGzipAndAutoObjectsAndReadsEachBack() throws Exception {
        byte[] data = "compressible-payload-".repeat(500).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (CompressionCodec codec : CompressionCodec.values()) {
            Path vault = temp.resolve(codec.name());
            ObjectStore store = new ObjectStore(vault, 1024 * 1024);
            store.setCompression(codec);
            String hash = store.put(data);
            assertArrayEquals(data, store.get(hash));
            assertArrayEquals(data, store.get(hash)); // cached read
            assertTrue(store.cacheStats().get("hits") >= 1);
        }
    }

    @Test void cacheReturnsDefensiveCopies() {
        ObjectCache cache = new ObjectCache(10);
        cache.put("key", new byte[]{1, 2, 3});
        byte[] first = cache.get("key");
        first[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, cache.get("key"));
    }
}
