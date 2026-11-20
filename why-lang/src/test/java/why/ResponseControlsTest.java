package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.lang.Redirect;
import why.testing.TestServer;

class ResponseControlsTest {

    @TempDir
    Path dir;

    private TestServer server;

    @BeforeEach
    void start() throws IOException {
        write("status.why", """
                controller:
                status(404)

                view:
                missing
                """);
        write("halt.why", """
                controller:
                halt(404)

                view:
                never
                """);
        write("back.why", """
                controller:
                redirect_back("/fallback")

                view:
                never
                """);
        write("next.why", """
                controller:
                redirect("/next")

                view:
                never
                """);
        write("view-halt.why", """
                view:
                before{{halt(404)}}after
                """);
        write("bad.why", """
                controller:
                redirect("//evil.example")

                view:
                never
                """);
        server = TestServer.start(dir);
    }

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void statusSetsTheCodeAndStillRenders() throws Exception {
        TestServer.Response response = server.get("/status");
        assertEquals(404, response.status());
        assertTrue(response.body().contains("missing"), response.body());
    }

    @Test
    void haltSetsTheCodeAndStopsTheView() throws Exception {
        TestServer.Response response = server.get("/halt");
        assertEquals(404, response.status());
        assertFalse(response.body().contains("never"), response.body());
    }

    @Test
    void haltInAViewStopsItToo() throws Exception {
        TestServer.Response response = server.get("/view-halt");
        assertEquals(404, response.status());
        assertFalse(response.body().contains("after"), response.body());
    }

    @Test
    void redirectBackUsesTheFormThenTheFallback() throws Exception {
        assertEquals("/next",
                server.post("/back", Map.of("back", "/next")).header("location"));
        assertEquals("/fallback",
                server.post("/back", Map.of("back", "//evil.example")).header("location"));
        assertEquals("/fallback",
                server.post("/back", Map.of()).header("location"));
    }

    @Test
    void redirectStillAllowsALocalPath() throws Exception {
        TestServer.Response response = server.get("/next");
        assertEquals(303, response.status());
        assertEquals("/next", response.header("location"));
    }

    @Test
    void redirectRejectsProtocolRelativeAndAbsoluteTargets() throws Exception {
        assertEquals(500, server.get("/bad").status());
    }

    @Test
    void thePathRuleIsSingleSlashAndNoWhitespace() {
        assertTrue(Redirect.isSameOriginPath("/post?id=1"));
        assertFalse(Redirect.isSameOriginPath("//evil.example"));
        assertFalse(Redirect.isSameOriginPath("https://evil.example"));
        assertFalse(Redirect.isSameOriginPath("/a b"));
        assertFalse(Redirect.isSameOriginPath("/\\evil.example"));
        assertFalse(Redirect.isSameOriginPath(""));
    }
}
