package why.lang;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sqlite.ProgressHandler;

import why.AllocationLimits;
import why.RequestTimeout;
import why.WhyError;

/** A SQLite database handle. The connection is opened on first use and closed after the page runs. */
public final class Db implements AutoCloseable {
    /** How often SQLite checks whether the request was interrupted. */
    private static final int PROGRESS_VM_CALLS = 1_000;

    /** How long a writer waits for the write lock before SQLite reports it as busy. */
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private static final ProgressHandler ABORT_ON_INTERRUPT = new ProgressHandler() {
        @Override
        protected int progress() {
            return Thread.currentThread().isInterrupted() ? 1 : 0;
        }
    };

    private final Path file;
    private Connection conn;

    public Db(Path file) {
        this.file = file;
    }

    /** Rows as hashes keyed by column label. */
    public List<Object> query(String sql, List<Object> args) {
        return query(sql, args, Map.of());
    }

    /**
     * Rows as hashes, with {@code ?} bound from {@code args} in order and {@code :name} bound from
     * {@code named}. A name may appear more than once; each occurrence reuses the one value.
     */
    public List<Object> query(String sql, List<Object> args, Map<String, Object> named) {
        Bound bound = Bound.of(sql, args, named);
        try (PreparedStatement ps = connection().prepareStatement(bound.sql())) {
            bind(ps, bound.args());
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        } catch (SQLException e) {
            throw failure("db.query", e);
        }
    }

    /** Runs a statement and returns the number of affected rows. */
    public long exec(String sql, List<Object> args) {
        return exec(sql, args, Map.of());
    }

    /** Runs a statement with mixed {@code ?} and {@code :name} parameters. */
    public long exec(String sql, List<Object> args, Map<String, Object> named) {
        Bound bound = Bound.of(sql, args, named);
        try (PreparedStatement ps = connection().prepareStatement(bound.sql())) {
            bind(ps, bound.args());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("db.exec", e);
        }
    }

    /** The first row of a query, or nil. */
    public Object get(String sql, List<Object> args) {
        return get(sql, args, Map.of());
    }

    /** The first row of a query with mixed {@code ?} and {@code :name} parameters. */
    public Object get(String sql, List<Object> args, Map<String, Object> named) {
        List<Object> rows = query(sql, args, named);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** SQL with every named placeholder rewritten to {@code ?} and the values in placeholder order. */
    private record Bound(String sql, List<Object> args) {
        static Bound of(String sql, List<Object> positional, Map<String, Object> named) {
            Map<String, Object> values = named == null ? Map.of() : named;
            StringBuilder out = new StringBuilder(sql.length());
            List<Object> bound = new ArrayList<>();
            Set<String> used = new HashSet<>();
            int next = 0;
            int i = 0;
            while (i < sql.length()) {
                char c = sql.charAt(i);
                if (c == '\'' || c == '"' || c == '`') {
                    int end = quoted(sql, i, c);
                    out.append(sql, i, end);
                    i = end;
                } else if (c == '[') {
                    int end = sql.indexOf(']', i + 1);
                    end = end < 0 ? sql.length() : end + 1;
                    out.append(sql, i, end);
                    i = end;
                } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                    int end = sql.indexOf('\n', i + 2);
                    end = end < 0 ? sql.length() : end;
                    out.append(sql, i, end);
                    i = end;
                } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                    int end = sql.indexOf("*/", i + 2);
                    end = end < 0 ? sql.length() : end + 2;
                    out.append(sql, i, end);
                    i = end;
                } else if (c == '?') {
                    if (i + 1 < sql.length() && Character.isDigit(sql.charAt(i + 1))) {
                        throw new WhyError("numbered '?' parameters are not supported; use '?' or ':name'");
                    }
                    if (next >= positional.size()) {
                        throw new WhyError("db needs a value for every '?'");
                    }
                    out.append('?');
                    bound.add(positional.get(next++));
                    i++;
                } else if (c == ':' && i + 1 < sql.length() && nameStart(sql.charAt(i + 1))) {
                    int end = i + 2;
                    while (end < sql.length() && namePart(sql.charAt(end))) end++;
                    String name = sql.substring(i + 1, end);
                    if (!values.containsKey(name)) {
                        throw new WhyError("db needs a value for named parameter ':" + name + "'");
                    }
                    out.append('?');
                    bound.add(values.get(name));
                    used.add(name);
                    i = end;
                } else {
                    out.append(c);
                    i++;
                }
            }
            if (next < positional.size()) {
                throw new WhyError("db received more arguments than the SQL uses");
            }
            for (String name : values.keySet()) {
                if (!used.contains(name)) {
                    throw new WhyError("db named parameter '" + name + "' is not used in the SQL");
                }
            }
            return new Bound(out.toString(), bound);
        }

        /** The end of a quoted string or identifier, including doubled quote escapes. */
        private static int quoted(String sql, int start, char quote) {
            int i = start + 1;
            while (i < sql.length()) {
                if (sql.charAt(i) == quote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                        i += 2;
                        continue;
                    }
                    return i + 1;
                }
                i++;
            }
            return sql.length();
        }

        private static boolean nameStart(char c) {
            return Character.isLetter(c) || c == '_';
        }

        private static boolean namePart(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }
    }

    /** Runs a {@code schema.sql} file, one top-level statement at a time. */
    public static void applyScript(Path dbFile, String script) {
        try (Db db = new Db(dbFile)) {
            for (String statement : SqlScript.statements(script)) {
                db.exec(statement, List.of());
            }
        }
    }

    @Override
    public void close() {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException ignored) {
            // Nothing useful to do while the page response is being finished.
        }
        conn = null;
    }

    private Connection connection() {
        if (conn != null) return conn;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {
            // The service loader in the shaded jar registers the driver.
        }
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            // WAL lets readers proceed during a write; the timeout makes a second writer wait instead
            // of failing with "database is locked".
            try (Statement statement = conn.createStatement()) {
                statement.execute("pragma journal_mode = WAL");
                statement.execute("pragma busy_timeout = " + BUSY_TIMEOUT_MILLIS);
                // SQLite defaults foreign keys off. Every page connection enforces the schema's
                // declared references, including on delete cascades.
                statement.execute("pragma foreign_keys = on");
            }
            ProgressHandler.setHandler(conn, PROGRESS_VM_CALLS, ABORT_ON_INTERRUPT);
            return conn;
        } catch (SQLException e) {
            close();
            throw new WhyError("cannot open database '" + file + "': " + e.getMessage());
        }
    }

    /** SQLite reports an aborted statement as a SQL error; a request timeout should not look like one. */
    private static RuntimeException failure(String operation, SQLException e) {
        if (Thread.currentThread().isInterrupted()) return new RequestTimeout();
        return new WhyError(operation + " failed: " + e.getMessage());
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object value = args.get(i);
            if (value == null) ps.setObject(i + 1, null);
            else if (value instanceof Boolean b) ps.setObject(i + 1, b ? 1 : 0);
            else ps.setObject(i + 1, value);
        }
    }

    private static List<Object> rows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        List<Object> rows = new ArrayList<>();
        long bytes = 0;
        while (rs.next()) {
            if (rows.size() >= AllocationLimits.DB_ROWS) {
                throw new WhyError("db.query returned more than " + AllocationLimits.DB_ROWS + " rows");
            }
            Map<Object, Object> row = new LinkedHashMap<>();
            for (int c = 1; c <= columns; c++) {
                String label = meta.getColumnLabel(c);
                Object value = normalize(rs.getObject(c));
                row.put(label, value);
                bytes += utf8Length(label) + valueBytes(value);
            }
            if (bytes > AllocationLimits.DB_BYTES) {
                throw new WhyError("db.query result exceeds " + AllocationLimits.DB_BYTES + " bytes");
            }
            rows.add(row);
        }
        return rows;
    }

    private static long valueBytes(Object value) {
        if (value == null) return 0;
        if (value instanceof byte[] bytes) return bytes.length;
        if (value instanceof String text) return utf8Length(text);
        if (value instanceof Number || value instanceof Boolean) return 8;
        return utf8Length(String.valueOf(value));
    }

    private static long utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Object normalize(Object value) {
        return value instanceof Integer i ? i.longValue() : value;
    }
}
