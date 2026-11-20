package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckerTest {

    @TempDir
    Path dir;

    private record Result(int code, String output) {}

    private Result check() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int code = new Checker(new Site(dir)).check(out);
        return new Result(code, bytes.toString(StandardCharsets.UTF_8));
    }

    private void write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void reportsNoProblemsAndPrintsTheMap() throws IOException {
        write("index.why", """
                imports:
                header

                controller:
                name = str(1)

                view:
                {{> header}}
                <p>{{name}}</p>
                """);
        write("header.why", "view:\n<header>site</header>\n");
        write("about.why", "view:\n<p>about</p>\n");

        Result result = check();
        assertEquals(0, result.code());
        assertTrue(result.output().contains("why check: no problems in 3 pages"));
        assertTrue(result.output().contains("page map"));
        assertTrue(result.output().contains("index.why"));
        assertTrue(result.output().contains("about.why"));
        assertTrue(result.output().contains("imports: header"));
        assertTrue(result.output().contains("partials: header"));
    }

    @Test
    void importOnlyFilesAreNotPagesOrUrlCollisions() throws IOException {
        write("_about.why", "view:\n<p>private</p>\n");
        write("about.why", "view:\n<p>about</p>\n");
        write("page.why", "imports:\n_about\n\nview:\n{{> _about}}\n");

        Result result = check();
        assertEquals(0, result.code());
        assertTrue(result.output().contains("no problems in 2 pages"));
        assertFalse(result.output().contains("_about.why"));
        assertTrue(result.output().contains("imports: _about"));
    }

    @Test
    void reportsImportsPartialsDefsFunctionsAndUrlCollisions() throws IOException {
        write("index.why", "controller:\nshout()\n\nview:\nhi\n");
        write("broken.why", "imports:\nnope\n\nview:\nhi\n");
        write("header.why", "view:\n{{> footer}}\n");
        write("dups.why", "controller:\ndef shout()\n  \"a\"\nend\ndef shout()\n  \"b\"\nend\n\nview:\nhi\n");
        write("about.why", "view:\n<p>about</p>\n");
        write("about/index.why", "view:\n<p>about dir</p>\n");

        Result result = check();
        assertEquals(1, result.code());
        String out = result.output();
        assertTrue(out.contains("cannot find import 'nope'"));
        assertTrue(out.contains("unknown partial 'footer'"));
        assertTrue(out.contains("def 'shout' is defined 2 times"));
        assertTrue(out.contains("undefined function 'shout'"));
        assertTrue(out.contains("/about: about.why and about/index.why"));
        assertTrue(out.contains("(compile error)"));
    }
}
