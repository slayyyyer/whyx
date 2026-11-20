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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        } catch (SQLException e) {
            throw failure("db.query", e);
        }
    }

    /** Runs a statement and returns the number of affected rows. */
    public long exec(String sql, List<Object> args) {
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("db.exec", e);
        }
    }

    /** The first row of a query, or nil. */
    public Object get(String sql, List<Object> args) {
        List<Object> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Runs a {@code schema.sql} file, one {@code ;}-separated statement at a time. */
    public static void applyScript(Path dbFile, String script) {
        try (Db db = new Db(dbFile)) {
            for (String statement : script.split(";")) {
                if (!statement.isBlank()) db.exec(statement, List.of());
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
