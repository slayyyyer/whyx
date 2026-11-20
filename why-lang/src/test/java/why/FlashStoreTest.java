package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class FlashStoreTest {

    @Test
    void staleEntriesAreRemoved() {
        AtomicLong now = new AtomicLong();
        FlashStore store = new FlashStore(8, 100, now::get);
        String id = store.put(Map.of("notice", "saved"));

        now.set(101);

        assertNull(store.take(id));
        assertEquals(0, store.size());
    }

    @Test
    void oldestEntriesAreEvictedAtTheCap() {
        AtomicLong now = new AtomicLong();
        FlashStore store = new FlashStore(2, 1_000_000, now::get);
        String first = store.put(Map.of("notice", "first"));
        String second = store.put(Map.of("notice", "second"));
        String third = store.put(Map.of("notice", "third"));

        assertNull(store.take(first));
        assertNotNull(store.take(second));
        assertNotNull(store.take(third));
        assertEquals(0, store.size());
    }
}
