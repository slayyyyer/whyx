package why;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * One-shot flash messages keyed by server-issued session IDs. Entries are capped,
 * expire lazily on access, and only IDs created here are accepted.
 */
final class FlashStore {
    private static final int DEFAULT_MAX_ENTRIES = 1024;
    private static final long DEFAULT_TTL_NANOS = 30L * 60 * 1_000_000_000L;

    private final int maxEntries;
    private final long ttlNanos;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private record Entry(Map<String, Object> messages, long expiresAtNanos) {}

    FlashStore() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_NANOS, System::nanoTime);
    }

    FlashStore(int maxEntries, long ttlNanos, LongSupplier clock) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlNanos = ttlNanos;
        this.clock = clock;
    }

    /** Creates a new unguessable session ID and stores a copy of the messages. */
    synchronized String put(Map<String, Object> messages) {
        long now = clock.getAsLong();
        cleanup(now);
        evictToFit();
        String id;
        do {
            id = newId();
        } while (entries.containsKey(id));
        entries.put(id, new Entry(new LinkedHashMap<>(messages), expiresAt(now)));
        return id;
    }

    /** Reuses an ID that was just validated by {@link #take(String)}. */
    synchronized void put(String id, Map<String, Object> messages) {
        if (id == null) return;
        long now = clock.getAsLong();
        cleanup(now);
        if (!entries.containsKey(id)) evictToFit();
        entries.put(id, new Entry(new LinkedHashMap<>(messages), expiresAt(now)));
    }

    /** Returns and removes a valid entry; expired or unknown IDs return null. */
    synchronized Map<String, Object> take(String id) {
        long now = clock.getAsLong();
        cleanup(now);
        Entry entry = entries.remove(id);
        return entry == null ? null : new LinkedHashMap<>(entry.messages());
    }

    synchronized int size() {
        cleanup(clock.getAsLong());
        return entries.size();
    }

    private void cleanup(long now) {
        entries.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtNanos());
    }

    private void evictToFit() {
        while (entries.size() >= maxEntries) {
            var iterator = entries.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private long expiresAt(long now) {
        long expires = now + ttlNanos;
        return expires < now ? Long.MAX_VALUE : expires;
    }

    private String newId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
