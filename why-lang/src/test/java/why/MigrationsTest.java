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
    void aMigrationThatLeavesADanglingReferenceIsRolledBack() throws Exception {
        migration("0001_parents.sql", """
                create table parents (id integer primary key);
                create table children (id integer primary key,
                  parent_id integer not null references parents(id) on delete cascade);
                insert into parents (id) values (1);
                insert into children (id, parent_id) values (1, 1);
                """);
        migration("0002_broken.sql", "insert into children (id, parent_id) values (2, 99);");
        Path database = dir.resolve("data.db");

        assertThrows(WhyError.class, () -> Migrations.apply(dir, database));
        assertEquals(1, rows(database, "select id from children").size(),
                "a migration with a dangling reference should roll back");
        assertTrue(rows(database, "select name from " + Migrations.TABLE).stream()
                        .noneMatch(row -> "0002_broken.sql".equals(
                                ((java.util.Map<?, ?>) row).get("name"))),
                "the failed migration should not be recorded");
    }

    @Test
    void aTableRebuildKeepsRowsAndCascades() throws Exception {
        migration("0001_old.sql", """
                create table users (id integer primary key);
                create table posts (id integer primary key, user_id integer not null,
                  parent_id integer, body text not null);
                insert into users (id) values (1);
                insert into posts (id, user_id, parent_id, body) values (1, 1, null, 'root');
                insert into posts (id, user_id, parent_id, body) values (2, 1, 1, 'reply');
                """);
        migration("0002_keys.sql", """
                create table posts_new (
                  id integer primary key,
                  user_id integer not null references users(id) on delete cascade,
                  parent_id integer references posts(id) on delete cascade,
                  body text not null
                );
                insert into posts_new (id, user_id, parent_id, body)
                  select id, user_id, parent_id, body from posts;
                drop table posts;
                alter table posts_new rename to posts;
                """);
        Path database = dir.resolve("data.db");

        assertEquals(2, Migrations.apply(dir, database));
        assertEquals(2, rows(database, "select id from posts").size(),
                "the rebuild should keep every row");
        try (Db db = new Db(database)) {
            db.exec("delete from posts where id = 1", List.of());
            assertEquals(0, db.query("select id from posts", List.of()).size(),
                    "the rebuilt self-reference should cascade");
        }
    }

    @Test
    void anAppliedMigrationCannotBeEdited() throws Exception {
        migration("0001_notes.sql", "create table notes (id integer primary key);");
        Path database = dir.resolve("data.db");
        assertEquals(1, Migrations.apply(dir, database));

        String checksum = String.valueOf(((java.util.Map<?, ?>) rows(database,
                "select checksum from " + Migrations.TABLE + " where name = '0001_notes.sql'")
                .get(0)).get("checksum"));
        assertTrue(checksum.matches("[0-9a-f]{64}"), checksum);

        migration("0001_notes.sql", "create table notes (id integer primary key, body text);");
        migration("0002_more.sql", "create table more (id integer primary key);");

        WhyError error = assertThrows(WhyError.class, () -> Migrations.apply(dir, database));
        assertTrue(error.getMessage().contains("changed after it was applied"), error.getMessage());
        assertTrue(rows(database, "select name from " + Migrations.TABLE).stream()
                        .noneMatch(row -> "0002_more.sql".equals(
                                ((java.util.Map<?, ?>) row).get("name"))),
                "a pending migration ran before the checksum check");
    }

    @Test
    void aMigrationRecordedBeforeChecksumsGetsABaseline() throws Exception {
        migration("0001_notes.sql", "create table notes (id integer primary key);");
        Path database = dir.resolve("data.db");
        try (Db db = new Db(database)) {
            db.exec("create table notes (id integer primary key)", List.of());
            db.exec("create table " + Migrations.TABLE
                    + " (name text primary key, applied_at text not null default current_timestamp)",
                    List.of());
            db.exec("insert into " + Migrations.TABLE + " (name) values ('0001_notes.sql')",
                    List.of());
        }

        assertEquals(0, Migrations.apply(dir, database));
        String checksum = String.valueOf(((java.util.Map<?, ?>) rows(database,
                "select checksum from " + Migrations.TABLE + " where name = '0001_notes.sql'")
                .get(0)).get("checksum"));
        assertTrue(checksum.matches("[0-9a-f]{64}"), checksum);
    }

    @Test
    void aTriggerBodyIsOneStatement() throws Exception {
        migration("0001_notes.sql", """
                create table notes (id integer primary key, body text);
                create table audit (body text);
                create trigger note_audit after insert on notes begin
                  insert into audit (body) values ('seen; ' || new.body);
                end;
                """);
        Path database = dir.resolve("data.db");
        assertEquals(1, Migrations.apply(dir, database));

        try (Db db = new Db(database)) {
            db.exec("insert into notes (body) values ('a;b')", List.of());
            assertEquals("seen; a;b",
                    ((java.util.Map<?, ?>) db.get("select body from audit", List.of())).get("body"));
        }
    }

    @Test
    void aMissingFolderDoesNothing() throws Exception {
        Path database = dir.resolve("data.db");
        assertEquals(0, Migrations.apply(dir, database));
        assertTrue(Files.notExists(database), "no database should be created without migrations");
    }
}
