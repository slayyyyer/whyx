package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void aFileWithoutLabelsIsAllView() throws IOException {
        Path file = write("plain.why", "<h1>hello</h1>\n");
        Page page = new PageLoader(new Site(dir)).load(file);
        assertEquals("<h1>hello</h1>\n", page.viewSource());
        assertEquals(1, page.viewLine());
        assertTrue(page.imports().isEmpty());
    }

    @Test
    void sectionsCarryTheirStartingLine() throws IOException {
        Path file = write("page.why", """
                imports:
                header

                controller:
                title = "x"

                view:
                <h1>{{title}}</h1>
                """);
        Page page = new PageLoader(new Site(dir)).load(file);
        assertEquals("header", page.imports().get(0).name());
        assertEquals(5, page.controllerLine());
        assertEquals(8, page.viewLine());
        assertEquals("title = \"x\"\n\n", page.controllerSource());
    }

    @Test
    void labelsInsideTheViewAreJustText() throws IOException {
        Path file = write("page.why", "view:\ncontroller:\nstill text\n");
        Page page = new PageLoader(new Site(dir)).load(file);
        assertEquals("controller:\nstill text\n", page.viewSource());
    }

    @Test
    void outOfOrderSectionsAreRejected() throws IOException {
        Path file = write("bad.why", "controller:\nx = 1\n\nimports:\nheader\n");
        WhyError error = assertThrows(WhyError.class, () -> new PageLoader(new Site(dir)).load(file));
        assertEquals("bad.why", error.file());
        assertEquals(4, error.line());
        assertTrue(error.getMessage().contains("out of order"));
    }

    @Test
    void unknownLabelsBeforeTheViewAreRejected() throws IOException {
        Path file = write("bad.why", "layout:\n<div></div>\n");
        WhyError error = assertThrows(WhyError.class, () -> new PageLoader(new Site(dir)).load(file));
        assertEquals(1, error.line());
        assertTrue(error.getMessage().contains("unknown section"));
    }

    @Test
    void textBeforeTheFirstLabelIsRejected() throws IOException {
        Path file = write("bad.why", "hello\nview:\nhi\n");
        WhyError error = assertThrows(WhyError.class, () -> new PageLoader(new Site(dir)).load(file));
        assertEquals(1, error.line());
        assertTrue(error.getMessage().contains("before the first section"));
    }

    @Test
    void importsContributePartialsAndFunctions() throws IOException {
        write("header.why", "view:\n<header>why</header>\n");
        write("helpers.why", """
                controller:
                def shout(text)
                  text.upcase + "!"
                end

                view:
                helper view
                """);
        Path page = write("index.why", """
                imports:
                header
                helpers

                controller:
                title = shout("hello")

                view:
                {{> header}}
                <h1>{{title}}</h1>
                """);

        String html = new PageCompiler(new Site(dir)).compile(page).render(Map.of());
        assertEquals("<header>why</header>\n\n<h1>HELLO!</h1>\n", html);
    }

    @Test
    void symlinkedImportsCannotHideACycle() throws IOException {
        Path a = write("a.why", "imports:\nb\n\nview:\n{{> b}}\n");
        write("b.why", "imports:\nalias\n\nview:\n{{> alias}}\n");
        Path alias = dir.resolve("alias.why");
        Assumptions.assumeTrue(createFileLink(alias, a), "file symlinks are unavailable");

        try {
            WhyError error = assertThrows(WhyError.class, () -> new PageCompiler(new Site(dir)).compile(a));
            assertTrue(error.getMessage().contains("circular import 'alias'"), error.getMessage());
        } finally {
            Files.deleteIfExists(alias);
        }
    }

    /** A file symlink; false when the platform or account does not allow one. */
    private static boolean createFileLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }

    @Test
    void pagesLargerThanTheLimitAreRejected() throws IOException {
        Path file = write("big.why", "view:\n" + "x".repeat((int) AllocationLimits.PAGE_BYTES));

        WhyError error = assertThrows(WhyError.class, () -> new PageLoader(new Site(dir)).load(file));
        assertEquals("big.why", error.file());
        assertTrue(error.getMessage().contains("limit"), error.getMessage());
        assertTrue(error.getMessage().contains(String.valueOf(AllocationLimits.PAGE_BYTES)), error.getMessage());
    }

    @Test
    void oversizedImportsAreRejected() throws IOException {
        write("big.why", "view:\n" + "x".repeat((int) AllocationLimits.PAGE_BYTES));
        Path page = write("uses.why", "imports:\nbig\n\nview:\nok\n");

        WhyError error = assertThrows(WhyError.class, () -> new PageCompiler(new Site(dir)).compile(page));
        assertTrue(error.getMessage().contains("big.why"), error.getMessage());
        assertTrue(error.getMessage().contains("limit"), error.getMessage());
    }

    @Test
    void missingImportsAreReportedWithTheImportLine() throws IOException {
        Path page = write("index.why", "imports:\nnope\n\nview:\nhi\n");
        WhyError error = assertThrows(WhyError.class, () -> new PageCompiler(new Site(dir)).compile(page));
        assertEquals("index.why", error.file());
        assertEquals(2, error.line());
        assertTrue(error.getMessage().contains("cannot find import"));
    }
}
