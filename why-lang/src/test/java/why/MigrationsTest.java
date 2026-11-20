package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.lang.Db;

class MigrationsTest {

    @TempDir
    Path dir;

    private void migration(String name, String sql) throws Exception {
        Path folder = dir.resolve(Migrations.DIRECTORY);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(name), sql, StandardCharsets.UTF_8);
    }

    private List<Object> rows(Path database, String sql) {
        try (Db db = new Db(database)) {
            return db.query(sql, List.of());
        }
    }

    @Test
    void appliesFilesInOrderAndRecordsThem() throws Exception {
        migration("0002_seed.sql", "insert into notes (body) values ('second');");
        migration("0001_notes.sql", "create table notes (id integer primary key, body text);");
        Path database = dir.resolve("data.db");

        assertEquals(2, Migrations.apply(dir, database));
        assertEquals(1, rows(database, "select body from notes").size());
        assertEquals("second", ((java.util.Map<?, ?>) rows(database, "select body from notes").get(0)).get("body"));
    }

    @Test
    void secondRunAppliesNothing() throws Exception {
        migration("0001_notes.sql", "create table notes (id integer primary key, body text);");
        Path database = dir.resolve("data.db");

        assertEquals(1, Migrations.apply(dir, database));
        assertEquals(0, Migrations.apply(dir, database));
    }

    @Test
    void aFailedMigrationRollsBackAndIsNotRecorded() throws Exception {
        migration("0001_notes.sql", "create table notes (id integer primary key, body text);");
        migration("0002_broken.sql", "insert into notes (body) values ('kept'); this is not sql;");
        Path database = dir.resolve("data.db");

        assertThrows(WhyError.class, () -> Migrations.apply(dir, database));
        assertEquals(0, rows(database, "select body from notes").size(),
                "the failed migration should not keep its writes");
        assertTrue(rows(database, "select name from " + Migrations.TABLE).stream()
                        .noneMatch(row -> String.valueOf(((java.util.Map<?, ?>) row).get("name")).startsWith("0002")),
                "the failed migration should not be recorded");
    }

    @Test
    void aMissingFolderDoesNothing() throws Exception {
        Path database = dir.resolve("data.db");
        assertEquals(0, Migrations.apply(dir, database));
        assertTrue(Files.notExists(database), "no database should be created without migrations");
    }
}
