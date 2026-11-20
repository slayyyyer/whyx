package why.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestServerTest {

    @TempDir
    Path dir;

    @Test
    void drivesAPageWithoutAServerProcess() throws Exception {
        Files.writeString(dir.resolve("index.why"),
                "controller:\nname = form.name\n\nview:\nhi {{name}}\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("add.why"), """
                controller:
                if request.method == "POST"
                  redirect("/?name=" + form.name)
                end

                view:
                form
                """, StandardCharsets.UTF_8);

        try (TestServer server = TestServer.start(dir)) {
            assertEquals(200, server.get("/").status());
            assertEquals("hi \n", server.get("/").body());

            TestServer.Response posted = server.post("/add", Map.of("name", "why"));
            assertEquals(303, posted.status());
            assertEquals("/?name=why", posted.header("location"));
            assertFalse(posted.header("x-request-id").isEmpty());
        }
    }
}
