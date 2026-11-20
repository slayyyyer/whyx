package why;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.testing.TestServer;

class SqlIdentPageTest {

    @TempDir
    Path dir;

    @Test
    void theSqlGlobalIsAvailableToAPageAndRejectsOtherNames() throws Exception {
        Files.writeString(dir.resolve("ident.why"), """
                controller:
                table = sql.ident(params.table, ["likes", "reposts"])

                view:
                {{table}}
                """, StandardCharsets.UTF_8);

        try (TestServer server = TestServer.start(dir)) {
            assertEquals("likes", server.get("/ident?table=likes").body().strip());
            assertEquals(500, server.get("/ident?table=posts").status());
        }
    }
}
