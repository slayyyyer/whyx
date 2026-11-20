package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.lang.Db;

class SeedsTest {

    @TempDir
    Path dir;

    private void site() throws Exception {
        Files.writeString(dir.resolve(Seeds.FILE), """
                create table if not exists demo (id integer primary key, name text not null);
                insert or ignore into demo (id, name) values (1, 'alice');
                """, StandardCharsets.UTF_8);
    }

    private Object name(Path dbFile) throws Exception {
        try (Db db = new Db(dbFile)) {
            Map<?, ?> row = (Map<?, ?>) db.get("select name from demo where id = 1", List.of());
            return row == null ? null : row.get("name");
        }
    }

    private boolean hasTable(Path dbFile) throws Exception {
        try (Db db = new Db(dbFile)) {
            return db.get("select name from sqlite_master where type = 'table' and name = 'demo'",
                    List.of()) != null;
        }
    }

    @Test
    void aFreshDevDatabaseGetsTheSeed() throws Exception {
        site();
        Path dbFile = dir.resolve("data.db");

        assertTrue(Seeds.apply(dir, dbFile, true, true));
        assertEquals("alice", name(dbFile));
        assertFalse(Seeds.apply(dir, dbFile, false, true), "a persistent database is not reseeded");
        assertEquals("alice", name(dbFile));
    }

    @Test
    void productionAndOptOutRunsDoNotSeed() throws Exception {
        site();
        Path dbFile = dir.resolve("data.db");

        assertFalse(Seeds.apply(dir, dbFile, true, false), "--dev is the opt-in");
        assertFalse(hasTable(dbFile));
        assertFalse(Seeds.apply(dir, dbFile, false, false));
        assertFalse(hasTable(dbFile));
    }

    @Test
    void aMissingSeedIsNotAnError() throws Exception {
        assertFalse(Seeds.apply(dir, dir.resolve("data.db"), true, true));
    }
}
