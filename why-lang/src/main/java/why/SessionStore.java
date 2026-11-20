package why;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import why.lang.Db;
import why.lang.Session;

/**
 * Server-side session storage: one row per name in {@code why_sessions}, keyed by an ID the
 * server mints and sends as the HttpOnly {@code why_sid} cookie. A session expires after 30 idle
 * days: reading it resets that clock, so browsing keeps it alive. Expired rows are pruned
 * whenever a session is written.
 */
final class SessionStore {
    /** Idle lifetime of a session, and the lifetime of the cookie that names it. */
    static final long TTL_SECONDS = 30L * 24 * 60 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private volatile boolean ready;

    /**
     * Stored values for an ID, or an empty map when the ID is unknown or expired. Reading a live
     * session resets its idle clock.
     */
    synchronized Map<String, Object> load(Db db, String id) {
        ensure(db);
        Map<String, Object> values = new LinkedHashMap<>();
        List<Object> rows = db.query(
                "select name, value from why_sessions where id = ? and updated_at > strftime('%s','now') - ?",
                List.of(id, TTL_SECONDS));
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) continue;
            Object name = map.get("name");
            if (name == null) continue;
            values.put(String.valueOf(name), map.get("value"));
        }
        // A live session's clock restarts on every page request, so the TTL measures idleness and
        // not the age of the sign-in. Expired rows are left for the next save to prune.
        if (!values.isEmpty()) {
            db.exec("update why_sessions set updated_at = strftime('%s','now') where id = ?",
                    List.of(id));
        }
        return values;
    }

    /** Writes the session and returns the ID that identifies it from now on. */
    synchronized String save(Db db, String id, Session session) {
        ensure(db);
        String use = (id == null || session.rotated()) ? newId() : id;
        // A rotated session retires the ID it started with, so a pre-login cookie is useless.
        if (id != null && !id.equals(use)) {
            db.exec("delete from why_sessions where id = ?", List.of(id));
        }
        db.exec("delete from why_sessions where id = ?", List.of(use));
        for (Map.Entry<String, Object> entry : session.values().entrySet()) {
            db.exec("insert into why_sessions (id, name, value, updated_at)"
                    + " values (?, ?, ?, strftime('%s','now'))",
                    List.of(use, entry.getKey(), entry.getValue()));
        }
        db.exec("delete from why_sessions where updated_at < strftime('%s','now') - ?",
                List.of(TTL_SECONDS));
        return use;
    }

    private void ensure(Db db) {
        if (ready) return;
        db.exec("create table if not exists why_sessions ("
                + " id text not null,"
                + " name text not null,"
                + " value text not null,"
                + " updated_at integer not null,"
                + " primary key (id, name))", List.of());
        db.exec("create index if not exists why_sessions_age on why_sessions (updated_at)", List.of());
        ready = true;
    }

    /** A new unguessable session ID in the shape the cookie accepts. */
    static String newId() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
