package why.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SqlScriptTest {

    @Test
    void semicolonsInStringsAndCommentsDoNotSplit() {
        List<String> statements = SqlScript.statements("""
                insert into notes (body) values ('a;b');
                -- a comment with ; inside
                select "col;name" from notes;
                /* another ; comment */
                select 2
                """);

        assertEquals(3, statements.size());
        assertEquals("insert into notes (body) values ('a;b')", statements.get(0));
        assertEquals("select \"col;name\" from notes", statements.get(1));
        assertEquals("select 2", statements.get(2));
    }

    @Test
    void aTriggerBodyStaysInOneStatement() {
        List<String> statements = SqlScript.statements("""
                create temp trigger note_audit after insert on notes begin
                  insert into audit (body) values ('seen; ' || new.body);
                  update notes set body = case when new.id > 0 then 'a;b' else 'c;d' end;
                end;
                create table audit (body text);
                """);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("create temp trigger"), statements.get(0));
        assertTrue(statements.get(0).contains("case when"), statements.get(0));
        assertEquals("create table audit (body text)", statements.get(1));
    }

    @Test
    void aCommentsOnlyScriptProducesNoStatements() {
        assertEquals(0, SqlScript.statements("-- nothing here;\n/* or here; */").size());
    }
}
