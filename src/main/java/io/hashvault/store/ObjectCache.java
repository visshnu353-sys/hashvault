package io.hashvault.store;

import java.util.LinkedHashMap;
import java.util.Map;

/** Thread-safe access-ordered cache bounded by the total uncompressed payload bytes. */
public final class ObjectCache {
    private final long maxBytes;
    private long currentBytes;
    private long hits;
    private long misses;
    private final LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(16, 0.75f, true);

    public ObjectCache(long maxBytes) {
        if (maxBytes < 0) throw new IllegalArgumentException("cache capacity cannot be negative");
        this.maxBytes = maxBytes;
    }

    public synchronized byte[] get(String key) {
        byte[] value = entries.get(key);
        if (value == null) { misses++; return null; }
        hits++;
        return value.clone();
    }

    public synchronized void put(String key, byte[] value) {
        if (maxBytes == 0 || value.length > maxBytes) return;
        byte[] copy = value.clone();
        byte[] old = entries.put(key, copy);
        if (old != null) currentBytes -= old.length;
        currentBytes += copy.length;
        var iterator = entries.entrySet().iterator();
        while (currentBytes > maxBytes && iterator.hasNext()) {
            Map.Entry<String, byte[]> eldest = iterator.next();
            currentBytes -= eldest.getValue().length;
            iterator.remove();
        }
    }

    public synchronized void invalidate(String key) {
        byte[] removed = entries.remove(key);
        if (removed != null) currentBytes -= removed.length;
    }

    public synchronized Map<String, Long> stats() {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        result.put("capacityBytes", maxBytes);
        result.put("cachedBytes", currentBytes);
        result.put("entries", (long) entries.size());
        result.put("hits", hits);
        result.put("misses", misses);
        return result;
    }
}
