package why;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import why.lang.Db;
import why.lang.SqlScript;

/**
 * Ordered {@code .sql} files under {@code migrations/}, applied once each in filename order and
 * recorded in {@code schema_migrations} with a checksum. Each file runs in its own transaction, so
 * a failure leaves the database at the last good migration. Editing a file that already ran is an
 * error: add a new migration instead.
 */
public final class Migrations {
    /** Folder name, relative to the site root. */
    public static final String DIRECTORY = "migrations";

    static final String TABLE = "schema_migrations";

    private Migrations() {}

    /** Applies pending migrations and returns how many ran. A missing folder means nothing to do. */
    public static int apply(Path siteRoot, Path dbFile) throws IOException {
        Path directory = siteRoot.resolve(DIRECTORY);
        if (!Files.isDirectory(directory)) return 0;

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(files::add);
        }

        try (Db db = new Db(dbFile)) {
            // A migration may rebuild a table by dropping it while other tables still reference it.
            // Run the files without enforcement and check each finished migration instead.
            db.exec("pragma foreign_keys = off", List.of());
            db.exec("create table if not exists " + TABLE
                    + " (name text primary key, applied_at text not null default current_timestamp,"
                    + " checksum text)", List.of());
            if (!hasColumn(db, TABLE, "checksum")) {
                db.exec("alter table " + TABLE + " add column checksum text", List.of());
            }

            Map<String, String> recorded = new LinkedHashMap<>();
            for (Object row : db.query("select name, checksum from " + TABLE, List.of())) {
                if (row instanceof Map<?, ?> map && map.get("name") != null) {
                    recorded.put(String.valueOf(map.get("name")),
                            map.get("checksum") == null ? null : String.valueOf(map.get("checksum")));
                }
            }

            Map<Path, String> checksums = new LinkedHashMap<>();
            List<Path> pending = new ArrayList<>();
            for (Path file : files) {
                String name = file.getFileName().toString();
                String checksum = checksum(file);
                checksums.put(file, checksum);
                if (!recorded.containsKey(name)) {
                    pending.add(file);
                    continue;
                }
                String stored = recorded.get(name);
                if (stored == null || stored.isBlank()) {
                    // Rows recorded before checksums existed get a baseline on their first run.
                    db.exec("update " + TABLE + " set checksum = ? where name = ?",
                            List.of(checksum, name));
                } else if (!stored.equals(checksum)) {
                    throw new WhyError("migration '" + name + "' changed after it was applied"
                            + " (recorded " + stored + ", now " + checksum + "); add a new migration"
                            + " instead of editing this one");
                }
            }

            int applied = 0;
            for (Path file : pending) {
                String name = file.getFileName().toString();
                String script;
                try {
                    script = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IOException("cannot read migration '" + name + "': " + e.getMessage(), e);
                }
                db.exec("begin", List.of());
                try {
                    for (String statement : SqlScript.statements(script)) {
                        db.exec(statement, List.of());
                    }
                    checkForeignKeys(db);
                    db.exec("insert into " + TABLE + " (name, checksum) values (?, ?)",
                            List.of(name, checksums.get(file)));
                    db.exec("commit", List.of());
                } catch (RuntimeException e) {
                    try {
                        db.exec("rollback", List.of());
                    } catch (RuntimeException ignored) {
                        // The original failure is the useful one.
                    }
                    throw new WhyError("migration '" + name + "' failed: " + e.getMessage());
                }
                applied++;
            }
            db.exec("pragma foreign_keys = on", List.of());
            checkForeignKeys(db);
            return applied;
        }
    }

    /** A line-ending-normalized SHA-256 of the file, so a CRLF checkout is not treated as an edit. */
    private static String checksum(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new WhyError("SHA-256 is unavailable: " + e.getMessage());
        }
    }

    private static boolean hasColumn(Db db, String table, String column) {
        for (Object row : db.query("pragma table_info(" + table + ")", List.of())) {
            if (row instanceof Map<?, ?> map && column.equals(String.valueOf(map.get("name")))) {
                return true;
            }
        }
        return false;
    }

    /** Fails when a table holds a reference to a row that does not exist. */
    private static void checkForeignKeys(Db db) {
        List<Object> violations = db.query("pragma foreign_key_check", List.of());
        if (violations.isEmpty()) return;
        if (!(violations.get(0) instanceof Map<?, ?> first)) {
            throw new WhyError("foreign key violation");
        }
        throw new WhyError("foreign key violation in table '" + first.get("table")
                + "' at rowid " + first.get("rowid"));
    }
}
