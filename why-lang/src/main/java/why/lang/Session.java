package why.lang;

import java.util.LinkedHashMap;
import java.util.Map;

import why.WhyError;

/**
 * The {@code session} global: small per-browser values kept on the server and keyed by an
 * HttpOnly cookie the server issues. A page reads and writes it with
 * {@code session.get("name")}, {@code session.set("name", value)} and {@code session.clear()}.
 *
 * <p>Only strings are stored, because the values live in one SQLite row per key. Values are
 * captured when the page finishes, so a redirect keeps whatever the page set.
 */
public final class Session {
    public static final int MAX_NAME = 64;
    public static final int MAX_VALUE = 4096;
    public static final int MAX_KEYS = 32;

    private final Map<String, Object> values = new LinkedHashMap<>();
    private boolean changed;
    private boolean rotated;

    /** The stored value for a name, or nil. */
    public Object get(String name) {
        return values.get(name);
    }

    /** Stores a value for the next request; nil removes the name. */
    public void set(String name, Object value) {
        String key = name == null ? "" : name.strip();
        if (key.isEmpty() || key.length() > MAX_NAME) {
            throw new WhyError("a session name must be 1 to " + MAX_NAME + " characters");
        }
        if (value == null) {
            if (values.remove(key) != null) changed = true;
            return;
        }
        String text = Value.str(value);
        if (text.length() > MAX_VALUE) {
            throw new WhyError("session value '" + key + "' is longer than " + MAX_VALUE + " characters");
        }
        if (!values.containsKey(key) && values.size() >= MAX_KEYS) {
            throw new WhyError("a session holds at most " + MAX_KEYS + " names");
        }
        values.put(key, text);
        changed = true;
    }

    /** Removes every name, which also expires the browser cookie. */
    public void clear() {
        if (values.isEmpty()) return;
        values.clear();
        changed = true;
    }

    /**
     * Asks for a fresh session ID on the next response and retires the current one.
     * A page calls this after signing someone in, so a session cookie issued before the
     * login cannot be replayed into the signed-in session.
     */
    public void rotate() {
        rotated = true;
        changed = true;
    }

    /** Whether the page asked for a new ID this request. */
    public boolean rotated() {
        return rotated;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Whether the page wrote to the session this request. */
    public boolean changed() {
        return changed;
    }

    /** Live values, in insertion order, for the server to persist. */
    public Map<String, Object> values() {
        return values;
    }

    /** Loads stored values at the start of a request without marking the session dirty. */
    public void load(Map<String, Object> stored) {
        values.putAll(stored);
    }
}
