package why;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import why.lang.Db;

/**
 * Ordered {@code .sql} files under {@code migrations/}, applied once each in filename order and
 * recorded in {@code schema_migrations}. Each file runs in its own transaction, so a failure leaves
 * the database at the last good migration.
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

        int applied = 0;
        try (Db db = new Db(dbFile)) {
            db.exec("create table if not exists " + TABLE
                    + " (name text primary key, applied_at text not null default current_timestamp)",
                    List.of());
            Set<String> done = new HashSet<>();
            for (Object row : db.query("select name from " + TABLE, List.of())) {
                if (row instanceof Map<?, ?> map && map.get("name") != null) {
                    done.add(String.valueOf(map.get("name")));
                }
            }

            for (Path file : files) {
                String name = file.getFileName().toString();
                if (done.contains(name)) continue;
                String script;
                try {
                    script = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IOException("cannot read migration '" + name + "': " + e.getMessage(), e);
                }
                db.exec("begin", List.of());
                try {
                    for (String statement : script.split(";")) {
                        if (!statement.isBlank()) db.exec(statement, List.of());
                    }
                    db.exec("insert into " + TABLE + " (name) values (?)", List.of(name));
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
        }
        return applied;
    }
}
