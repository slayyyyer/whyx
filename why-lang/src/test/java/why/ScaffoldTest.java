package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScaffoldTest {

    @TempDir
    Path dir;

    @Test
    void writesASiteThatPassesCheck() throws Exception {
        Path target = dir.resolve("mysite");

        assertEquals(0, Scaffold.create(target));
        assertTrue(Files.isRegularFile(target.resolve("index.why")));
        assertTrue(Files.isRegularFile(target.resolve("schema.sql")));

        int problems = new Checker(new Site(target))
                .check(new PrintStream(OutputStream.nullOutputStream()));
        assertEquals(0, problems);
    }

    @Test
    void refusesANonEmptyFolder() throws Exception {
        Files.writeString(dir.resolve("taken.txt"), "x", StandardCharsets.UTF_8);
        assertEquals(1, Scaffold.create(dir));
    }
}
