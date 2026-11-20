package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.lang.Ast;
import why.lang.Db;
import why.lang.Interpreter;
import why.lang.Parser;
import why.lang.Scope;
import why.lang.Sql;

class NamedParamsTest {

    @TempDir
    Path dir;

    @Test
    void aNamedValueCanAppearMoreThanOnce() throws Exception {
        try (Db db = new Db(dir.resolve("named.db"))) {
            Map<?, ?> row = (Map<?, ?>) db.query(
                    "select :who as first, :who as second", List.of(), Map.of("who", 7)).get(0);
            assertEquals(7L, row.get("first"));
            assertEquals(7L, row.get("second"));
        }
    }

    @Test
    void positionalAndNamedParametersMixInPlaceholderOrder() throws Exception {
        try (Db db = new Db(dir.resolve("mixed.db"))) {
            db.exec("create table posts (id integer, owner integer)", List.of());
            db.exec("insert into posts values (1, 7), (2, 8)", List.of());

            Map<?, ?> row = (Map<?, ?>) db.query(
                    "select id from posts where id = ? and owner = :who",
                    List.of(1), Map.of("who", 7)).get(0);
            assertEquals(1L, row.get("id"));
        }
    }

    @Test
    void placeholdersInsideStringsAndCommentsAreLeftAlone() throws Exception {
        try (Db db = new Db(dir.resolve("literal.db"))) {
            Map<?, ?> row = (Map<?, ?>) db.query(
                    "select ':who' as literal, :who as value /* :ignored */",
                    List.of(), Map.of("who", 7)).get(0);
            assertEquals(":who", row.get("literal"));
            assertEquals(7L, row.get("value"));
        }
    }

    @Test
    void missingAndUnusedNamesAreErrors() throws Exception {
        try (Db db = new Db(dir.resolve("errors.db"))) {
            WhyError missing = assertThrows(WhyError.class,
                    () -> db.query("select :who", List.of(), Map.of()));
            assertTrue(missing.getMessage().contains(":who"), missing.getMessage());

            WhyError unused = assertThrows(WhyError.class,
                    () -> db.query("select 1", List.of(), Map.of("who", 7)));
            assertTrue(unused.getMessage().contains("not used"), unused.getMessage());

            WhyError extra = assertThrows(WhyError.class,
                    () -> db.query("select 1", List.of(7), Map.of()));
            assertTrue(extra.getMessage().contains("more arguments"), extra.getMessage());
        }
    }

    @Test
    void numberedQuestionMarkParametersAreRejected() throws Exception {
        try (Db db = new Db(dir.resolve("numbered.db"))) {
            WhyError error = assertThrows(WhyError.class,
                    () -> db.query("select ?1", List.of(7), Map.of()));
            assertTrue(error.getMessage().contains("numbered"), error.getMessage());
        }
    }

    @Test
    void pageSyntaxBindsANamedArgument() throws Exception {
        try (Db db = new Db(dir.resolve("page.db"))) {
            db.exec("create table posts (n integer)", List.of());
            db.exec("insert into posts values (7)", List.of());

            Scope scope = new Scope(null);
            scope.define("db", db);
            List<Ast.Stmt> body = new Parser(
                    "row = db.query(\"select n from posts where n = :who\", who: 7).first\n"
                            + "value = row.n\n",
                    "test.why", 1).parseProgram();
            new Interpreter(Map.of(), scope).exec(body, scope);

            assertEquals(7L, scope.get("value"));
        }
    }

    @Test
    void positionalAfterNamedIsRejected() {
        WhyError error = assertThrows(WhyError.class, () -> new Parser(
                "def pick(a)\n  a\nend\npick(who: 1, 2)\n", "test.why", 1).parseProgram());
        assertTrue(error.getMessage().contains("positional"), error.getMessage());
    }

    @Test
    void identAcceptsOnlyTheAllowlist() {
        Sql sql = new Sql();
        assertEquals("likes", sql.ident("likes", List.of("likes", "reposts"), 1));

        WhyError rejected = assertThrows(WhyError.class,
                () -> sql.ident("posts", List.of("likes", "reposts"), 1));
        assertTrue(rejected.getMessage().contains("likes, reposts"), rejected.getMessage());
        assertThrows(WhyError.class, () -> sql.ident("likes", List.of(), 1));
        assertThrows(WhyError.class, () -> sql.ident("likes", "likes", 1));
    }
}
