package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import why.lang.Db;

class DbTest {

    @TempDir
    Path dir;

    @Test
    @Timeout(15)
    void interruptingARequestAbortsARunningQuery() throws Exception {
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        try (Db db = new Db(dir.resolve("slow.db"))) {
            db.exec("create table t (id integer)", List.of());

            Thread request = Thread.ofVirtual().start(() -> {
                try {
                    db.query("""
                            with recursive cnt(x) as (
                              select 1 union all select x + 1 from cnt limit 1000000000
                            )
                            select sum(x) from cnt
                            """, List.of());
                    outcome.set(new AssertionError("query ran to completion instead of aborting"));
                } catch (Throwable e) {
                    outcome.set(e);
                }
            });

            Thread.sleep(250);
            request.interrupt();
            request.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(request.isAlive(), "SQLite kept running after the request was interrupted");
            assertInstanceOf(RequestTimeout.class, outcome.get());
        }
    }

    @Test
    @Timeout(20)
    void aSecondWriterWaitsForTheFirstInsteadOfFailing() throws Exception {
        Path file = dir.resolve("busy.db");
        try (Db first = new Db(file); Db second = new Db(file)) {
            first.exec("create table t (id integer)", List.of());
            first.exec("begin immediate", List.of());
            first.exec("insert into t values (1)", List.of());

            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread writer = Thread.ofVirtual().start(() -> {
                try {
                    second.exec("insert into t values (2)", List.of());
                } catch (Throwable e) {
                    outcome.set(e);
                }
            });

            Thread.sleep(300);
            assertTrue(writer.isAlive(), "the second writer did not wait for the write lock");
            first.exec("commit", List.of());
            writer.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse(writer.isAlive(), "the second writer never completed");
            assertNull(outcome.get(), () -> "the second writer failed: " + outcome.get());
        }
    }

    @Test
    void foreignKeysAreEnforcedAndCascade() throws Exception {
        try (Db db = new Db(dir.resolve("foreign.db"))) {
            db.exec("create table parents (id integer primary key)", List.of());
            db.exec("create table children (id integer primary key,"
                    + " parent_id integer not null references parents(id) on delete cascade)",
                    List.of());
            db.exec("insert into parents (id) values (1)", List.of());
            db.exec("insert into children (id, parent_id) values (1, 1)", List.of());

            assertThrows(WhyError.class,
                    () -> db.exec("insert into children (id, parent_id) values (2, 99)", List.of()));

            db.exec("delete from parents where id = 1", List.of());
            assertEquals(0, db.query("select id from children", List.of()).size(),
                    "the child should cascade with its parent");
        }
    }

    @Test
    void applyScriptKeepsTriggerBodiesAndStringsTogether() throws Exception {
        Path file = dir.resolve("script.db");
        Db.applyScript(file, """
                create table notes (id integer primary key, body text);
                create table audit (body text);
                create trigger note_audit after insert on notes begin
                  insert into audit (body) values ('seen; ' || new.body);
                end;
                insert into notes (body) values ('a;b');
                """);

        try (Db db = new Db(file)) {
            assertEquals(1, db.query("select body from notes where body = 'a;b'", List.of()).size());
            assertEquals("seen; a;b",
                    ((java.util.Map<?, ?>) db.get("select body from audit", List.of())).get("body"));
        }
    }

    @Test
    void queriesReturningTooManyRowsAreRejected() throws Exception {
        try (Db db = new Db(dir.resolve("rows.db"))) {
            db.exec("create table many (id integer)", List.of());
            db.exec("with recursive cnt(x) as (select 1 union all select x + 1 from cnt where x < "
                    + (AllocationLimits.DB_ROWS + 1)
                    + ") insert into many select x from cnt", List.of());

            WhyError error = assertThrows(WhyError.class,
                    () -> db.query("select * from many", List.of()));
            assertTrue(error.getMessage().contains("more than " + AllocationLimits.DB_ROWS + " rows"),
                    error.getMessage());
        }
    }

    @Test
    void queriesReturningTooManyBytesAreRejected() throws Exception {
        try (Db db = new Db(dir.resolve("bytes.db"))) {
            db.exec("create table blobs (data blob)", List.of());
            db.exec("insert into blobs values (zeroblob(" + (AllocationLimits.DB_BYTES + 1) + "))", List.of());

            WhyError error = assertThrows(WhyError.class,
                    () -> db.query("select data from blobs", List.of()));
            assertTrue(error.getMessage().contains(AllocationLimits.DB_BYTES + " bytes"), error.getMessage());
        }
    }
}
